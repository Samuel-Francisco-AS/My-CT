package dev.myct

import android.content.Context
import android.graphics.*
import android.text.InputType
import android.view.*
import android.view.inputmethod.*
import java.io.Closeable
import kotlin.math.abs
import kotlin.math.hypot

/** One reusable bitmap, decoded on IO, drawn on UI; never hold a monitor while reading the network. */
internal class DesktopImage : Closeable {
    private val lock = Any()
    private var bitmap: Bitmap? = null
    private var closed = false
    private var attempt = -1
    var changed: (() -> Unit)? = null
    fun update(frame: RfbFramebuffer, token: Int = -1) = synchronized(lock) {
        if (closed) return@synchronized
        synchronized(frame) {
            var target = bitmap
            if (target == null || target.width != frame.width || target.height != frame.height) {
                target?.recycle()
                target = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
                bitmap = target
            }
            target!!.setPixels(frame.pixels, 0, frame.width, 0, 0, frame.width, frame.height)
        }
        attempt = token
        changed?.invoke()
    }
    fun draw(canvas: Canvas, view: DesktopView): Int? = synchronized(lock) {
        val current = bitmap ?: return@synchronized null
        view.frameSize(current.width, current.height)
        val vp = view.viewport()
        view.destination.set(vp.left, vp.top, vp.left + current.width * vp.scale, vp.top + current.height * vp.scale)
        canvas.drawBitmap(current, null, view.destination, view.paint)
        attempt
    }
    fun clear() = synchronized(lock) { bitmap?.recycle(); bitmap = null; changed?.invoke() }
    override fun close() = synchronized(lock) { closed = true; bitmap?.recycle(); bitmap = null; changed = null }
}

internal class DesktopView(context: Context, private val model: DesktopModel, val image: DesktopImage) : View(context) {
    internal val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    internal val destination = RectF()
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(239, 194, 84); strokeWidth = 2f }
    internal var frameWidth = model.framebufferWidth
    internal var frameHeight = model.framebufferHeight
    private var zoom = model.transform.zoom; private var panX = 0f; private var panY = 0f
    private var displayAttempt = model.attempt
    private var released = false
    private val cursorX get() = model.cursorX.toFloat()
    private val cursorY get() = model.cursorY.toFloat()
    private var relativeX = 0f; private var relativeY = 0f
    private var lastX = 0f; private var lastY = 0f
    private var inputGeneration = 0
    private var gestureInteraction = DesktopInteraction.DIRECT
    private var downX = 0f; private var downY = 0f
    private var lastMidY = 0f; private var lastMidX = 0f; private var lastSpan = 0f
    private var twoFinger: DesktopTwoFinger? = null
    private var scrolling = 0f
    private var moved = false; private var multi = false; private var held = false
    private var rightClicked = false; private var zooming = false; private var validDown = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var editor: DesktopInputConnection? = null
    private var keyAttempt = model.attempt
    private var deadAccent = 0
    private val longPress = Runnable {
        if (!released && model.ownsSurface(image) && inputGeneration == model.inputGeneration && !moved && !multi && validDown && !model.drag) {
            forgetEditor(finish = true)
            if (gestureInteraction == DesktopInteraction.DIRECT) point(downX, downY)
            model.click(4); rightClicked = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }
    init {
        isFocusable = true; isFocusableInTouchMode = true
        contentDescription = "Desktop Fedora. Toque: clique. Segure: clique direito. Dois dedos: rolar. Pinça: zoom."
        image.changed = { postInvalidateOnAnimation() }
    }
    internal fun viewport() = DesktopViewport(width.toFloat(), height.toFloat(), frameWidth, frameHeight, zoom, panX, panY)
    fun resetZoom() { zoom = 1f; panX = 0f; panY = 0f; saveTransform(); invalidate() }
    private fun saveTransform() {
        val vp = viewport()
        if (vp.scale > 0 && !released) model.transform = DesktopTransform(zoom,
            (.5f - panX / (frameWidth * vp.scale)).coerceIn(0f, 1f), (.5f - panY / (frameHeight * vp.scale)).coerceIn(0f, 1f))
    }
    private fun restoreTransform() {
        zoom = model.transform.zoom
        val vp = viewport()
        panX = (.5f - model.transform.centerX) * frameWidth * vp.scale
        panY = (.5f - model.transform.centerY) * frameHeight * vp.scale
        clampPan()
    }
    internal fun frameSize(w: Int, h: Int) {
        if (w != frameWidth || h != frameHeight) { frameWidth = w; frameHeight = h; restoreTransform() }
    }
    fun updateInteractionDescription() {
        contentDescription = "Desktop Fedora. ${model.interaction.label}. Toque: clique. Segure: clique direito. Dois dedos: ${if (model.localPan) "pan local" else "rolar"}. Pinça: zoom."
        invalidate()
    }
    // UI-only adjustments share the renderer/input viewport. Gesture interpretation is unchanged.
    fun zoomBy(factor: Float) {
        val old = zoom
        zoom = (zoom * factor).coerceIn(1f, 4f)
        panX *= zoom / old; panY *= zoom / old
        clampPan(); saveTransform(); invalidate()
    }
    fun panBy(horizontal: Float, vertical: Float) {
        panX += width * horizontal; panY += height * vertical
        clampPan(); saveTransform(); invalidate()
    }
    private fun clampPan() {
        val vp = viewport()
        val x = maxOf(0f, (frameWidth * vp.scale - width) / 2)
        val y = maxOf(0f, (frameHeight * vp.scale - height) / 2)
        panX = panX.coerceIn(-x, x); panY = panY.coerceIn(-y, y)
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw > 0 && oldh > 0 && (w != oldw || h != oldh)) cancelInput(clearEditor = false)
        restoreTransform()
    }
    override fun onDraw(canvas: Canvas) {
        // drawColor fills the Canvas clip, NOT the View bounds. AndroidView does not
        // guarantee that clip: without this it painted over Compose's toolbar in rc1.
        val saved = canvas.save()
        try {
            if (displayAttempt != model.attempt) {
                displayAttempt = model.attempt; forgetEditor(restart = false); restoreTransform()
            }
            canvas.clipRect(0, 0, width, height)
            canvas.drawColor(Color.BLACK)
            val token = image.draw(canvas, this)
            if (model.link == DesktopLink.CONNECTED) {
                val vp = viewport(); val x = vp.left + cursorX * vp.scale; val y = vp.top + cursorY * vp.scale
                canvas.drawLine(x - 8, y, x + 8, y, cursorPaint); canvas.drawLine(x, y - 8, x, y + 8, cursorPaint)
            }
            if (token != null) post { model.framePresented(token, image) }
        } catch (_: Exception) {
            val token = model.attempt
            post { model.renderingFailed(token, image) }
        } finally { canvas.restoreToCount(saved) }
    }
    private fun point(x: Float, y: Float, mask: Int = 0, clamp: Boolean = false): Boolean {
        val p = viewport().remote(x, y, clamp) ?: return false
        model.pointer(p.first, p.second, mask); invalidate(); return true
    }
    private fun insideView(x: Float, y: Float) = x >= 0 && y >= 0 && x < width && y < height
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (released || model.link != DesktopLink.CONNECTED || !model.ownsSurface(image)) return true
        parent?.requestDisallowInterceptTouchEvent(true)
        if ((0 until event.pointerCount).any { !event.getX(it).isFinite() || !event.getY(it).isFinite() }) {
            cancelInput(clearEditor = false); return true
        }
        if (event.actionMasked != MotionEvent.ACTION_DOWN && inputGeneration != model.inputGeneration) {
            removeCallbacks(longPress); held = false; validDown = false; multi = true
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus(); inputGeneration = model.inputGeneration
                moved = false; multi = false; rightClicked = false; held = false; zooming = false
                downX = event.x; downY = event.y; lastX = event.x; lastY = event.y
                relativeX = cursorX; relativeY = cursorY; gestureInteraction = model.interaction
                validDown = if (gestureInteraction == DesktopInteraction.TRACKPAD) insideView(event.x, event.y) else viewport().remote(event.x, event.y) != null
                // Delay pointer/press until intent is known. A pinch starting as one contact sends no click.
                if (validDown) postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(longPress); multi = true
                if (held) { model.releaseInput(); inputGeneration = model.inputGeneration; held = false }
                if (event.pointerCount == 2) {
                    lastMidX = (event.getX(0) + event.getX(1)) / 2; lastMidY = (event.getY(0) + event.getY(1)) / 2
                    lastSpan = hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1)); scrolling = 0f
                    twoFinger = DesktopTwoFinger(lastSpan, lastMidY, slop.toFloat(), model.localPan, lastMidX)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 2 && twoFinger != null) {
                    val mx = (event.getX(0) + event.getX(1)) / 2; val my = (event.getY(0) + event.getY(1)) / 2
                    val span = hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
                    val previousPairMode = twoFinger!!.mode
                    val pairMode = twoFinger!!.update(span, my, mx)
                    if (pairMode != DesktopTwoFinger.Mode.WAITING) {
                        when (pairMode) {
                            DesktopTwoFinger.Mode.ZOOM -> {
                                val old = viewport(); val rx = (lastMidX - old.left) / old.scale; val ry = (lastMidY - old.top) / old.scale
                                zoom = (zoom * (span / lastSpan.coerceAtLeast(1f))).coerceIn(1f, 4f)
                                val next = viewport(); panX += mx - (next.left + rx * next.scale); panY += my - (next.top + ry * next.scale)
                            }
                            DesktopTwoFinger.Mode.PAN -> { panX += mx - lastMidX; panY += my - lastMidY }
                            DesktopTwoFinger.Mode.SCROLL -> {
                                if (gestureInteraction == DesktopInteraction.DIRECT && viewport().remote(mx, my) == null) return true
                                if (previousPairMode == DesktopTwoFinger.Mode.WAITING) {
                                    forgetEditor(finish = true)
                                    if (gestureInteraction == DesktopInteraction.DIRECT) point(mx, my)
                                }
                                scrolling += my - lastMidY
                                val step = 40 * resources.displayMetrics.density
                                repeat(minOf(8, (abs(scrolling) / step).toInt())) {
                                    model.scroll(scrolling > 0); scrolling += if (scrolling > 0) -step else step
                                }
                            }
                            else -> Unit
                        }
                        clampPan(); saveTransform(); lastSpan = span; lastMidX = mx; lastMidY = my; invalidate()
                    }
                } else if (!multi && validDown && !rightClicked) {
                    if (!moved && hypot(event.x - downX, event.y - downY) > slop) {
                        moved = true; removeCallbacks(longPress)
                        forgetEditor(finish = true)
                        if (model.drag) {
                            if (gestureInteraction == DesktopInteraction.DIRECT) point(downX, downY, 1)
                            else model.pointer(model.cursorX, model.cursorY, 1)
                            held = true
                        }
                    }
                    if (moved) {
                        if (gestureInteraction == DesktopInteraction.TRACKPAD) {
                            val gain = model.trackpadGain / resources.displayMetrics.density
                            relativeX = (relativeX + (event.x - lastX) * gain).coerceIn(0f, (frameWidth - 1).toFloat())
                            relativeY = (relativeY + (event.y - lastY) * gain).coerceIn(0f, (frameHeight - 1).toFloat())
                            model.movePointer(relativeX.toInt(), relativeY.toInt(), if (held) 1 else 0)
                        } else viewport().remote(event.x, event.y, clamp = true)?.let {
                            model.movePointer(it.first, it.second, if (held) 1 else 0)
                        }
                        lastX = event.x; lastY = event.y; invalidate()
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> { twoFinger = null; multi = true }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (held) {
                    if (gestureInteraction == DesktopInteraction.DIRECT) point(event.x, event.y, 0, clamp = true)
                    else model.pointer(model.cursorX, model.cursorY, 0)
                    held = false
                } else if (validDown && !multi && !moved && !rightClicked &&
                    (if (gestureInteraction == DesktopInteraction.TRACKPAD) insideView(event.x, event.y) else viewport().remote(event.x, event.y) != null)) {
                    forgetEditor(finish = true)
                    if (gestureInteraction == DesktopInteraction.DIRECT) point(event.x, event.y)
                    model.click(); performClick()
                }
                twoFinger = null; parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> cancelInput(clearEditor = false)
        }
        return true
    }
    fun showKeyboard() {
        requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }
    override fun onCheckIsTextEditor() = !released && model.link == DesktopLink.CONNECTED && model.ownsSurface(image)
    override fun onCreateInputConnection(out: EditorInfo): InputConnection {
        editor?.closeConnection(); deadAccent = 0; keyAttempt = model.attempt
        out.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        out.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_ACTION_NONE
        out.initialSelStart = 0; out.initialSelEnd = 0
        return DesktopInputConnection(this, model).also { editor = it }
    }
    internal fun acceptsInput(connection: DesktopInputConnection, attempt: Int): Boolean =
        !released && editor === connection && model.attempt == attempt && model.link == DesktopLink.CONNECTED && model.ownsSurface(image)
    private fun forgetEditor(restart: Boolean = true, finish: Boolean = false) {
        if (finish) editor?.finishComposingText()
        editor?.discard(); editor = null; deadAccent = 0
        if (restart && !released && hasFocus())
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).restartInput(this)
    }
    fun finishEditorComposition() { editor?.finishComposingText() }
    fun specialKey(sym: Int) {
        if (released || model.link != DesktopLink.CONNECTED || !model.ownsSurface(image)) return
        val control = model.ctrl; val alternate = model.alt; val shifted = model.shift
        editor?.finishComposingText(); forgetEditor(); model.press(sym, control, alternate, shifted)
    }
    private fun systemKey(event: KeyEvent) = event.isSystem || event.isMetaPressed || event.keyCode in listOf(
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH,
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_POWER)
    internal fun remoteKey(event: KeyEvent): Boolean {
        if (systemKey(event) || released || model.link != DesktopLink.CONNECTED || !model.ownsSurface(image)) return false
        if (event.action != KeyEvent.ACTION_DOWN) return true // Each remote chord already releases its keys.
        if (keyAttempt != model.attempt) { forgetEditor(); keyAttempt = model.attempt }
        val control = model.ctrl || event.isCtrlPressed; val alternate = model.alt || event.isAltPressed
        val shifted = model.shift || event.isShiftPressed
        if (event.keyCode == KeyEvent.KEYCODE_DEL && !control && !alternate && editor != null)
            return editor!!.deleteSurroundingTextInCodePoints(1, 0)
        val key = when (event.keyCode) {
            KeyEvent.KEYCODE_DEL -> DesktopKeys.BACKSPACE; KeyEvent.KEYCODE_FORWARD_DEL -> DesktopKeys.DELETE
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> DesktopKeys.ENTER
            KeyEvent.KEYCODE_TAB -> DesktopKeys.TAB; KeyEvent.KEYCODE_ESCAPE -> DesktopKeys.ESC
            KeyEvent.KEYCODE_DPAD_LEFT -> 0xff51; KeyEvent.KEYCODE_DPAD_UP -> 0xff52
            KeyEvent.KEYCODE_DPAD_RIGHT -> 0xff53; KeyEvent.KEYCODE_DPAD_DOWN -> 0xff54
            KeyEvent.KEYCODE_MOVE_HOME -> 0xff50; KeyEvent.KEYCODE_MOVE_END -> 0xff57
            KeyEvent.KEYCODE_PAGE_UP -> 0xff55; KeyEvent.KEYCODE_PAGE_DOWN -> 0xff56
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT, KeyEvent.KEYCODE_ALT_LEFT,
            KeyEvent.KEYCODE_ALT_RIGHT, KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> return true
            else -> {
                var unicode = event.getUnicodeChar(event.metaState and KeyEvent.META_CTRL_MASK.inv() and KeyEvent.META_ALT_MASK.inv())
                if (unicode == 0) return false
                if (unicode and KeyCharacterMap.COMBINING_ACCENT != 0) {
                    deadAccent = unicode and KeyCharacterMap.COMBINING_ACCENT_MASK; return true
                }
                if (deadAccent != 0) {
                    val combined = KeyCharacterMap.getDeadChar(deadAccent, unicode)
                    if (combined != 0) unicode = combined else model.text(String(Character.toChars(deadAccent)))
                    deadAccent = 0
                }
                if (!control && !alternate && !model.shift) {
                    val value = String(Character.toChars(unicode))
                    return editor?.commitText(value, 1) ?: run { model.text(value); true }
                }
                DesktopKeys.unicode(unicode)
            }
        }
        editor?.finishComposingText(); forgetEditor()
        model.press(key, control, alternate, shifted)
        return true
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        if (systemKey(event)) super.onKeyDown(keyCode, event) else remoteKey(event)
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (systemKey(event)) super.onKeyUp(keyCode, event) else remoteKey(event)
    override fun onDetachedFromWindow() { cancelInput(); super.onDetachedFromWindow() }
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus) {
            if (!released && model.ownsSurface(image)) editor?.finishComposingText()
            cancelInput()
        }
    }
    fun cancelInput(clearEditor: Boolean = true) {
        if (clearEditor) forgetEditor(restart = false)
        removeCallbacks(longPress); held = false; validDown = false; multi = true; twoFinger = null
        parent?.requestDisallowInterceptTouchEvent(false)
        if (model.ownsSurface(image)) {
            if (clearEditor) model.releaseInput() else model.releasePointer()
        }
    }
    fun release() {
        cancelInput(); released = true
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken, 0)
        image.close()
    }
}
