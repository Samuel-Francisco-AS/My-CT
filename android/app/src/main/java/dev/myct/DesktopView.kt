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
        var target = bitmap
        if (target == null || target.width != frame.width || target.height != frame.height) {
            target?.recycle()
            target = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
            bitmap = target
        }
        synchronized(frame) { target!!.setPixels(frame.pixels, 0, frame.width, 0, 0, frame.width, frame.height) }
        attempt = token
        changed?.invoke()
    }
    fun draw(canvas: Canvas, view: DesktopView): Int? = synchronized(lock) {
        val current = bitmap ?: return@synchronized null
        view.frameWidth = current.width; view.frameHeight = current.height
        val vp = view.viewport()
        canvas.drawBitmap(current, null, RectF(vp.left, vp.top, vp.left + current.width * vp.scale,
            vp.top + current.height * vp.scale), view.paint)
        attempt
    }
    fun clear() = synchronized(lock) { bitmap?.recycle(); bitmap = null; changed?.invoke() }
    override fun close() = synchronized(lock) { closed = true; bitmap?.recycle(); bitmap = null; changed = null }
}

internal class DesktopView(context: Context, private val model: DesktopModel, val image: DesktopImage) : View(context) {
    internal val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(239, 194, 84); strokeWidth = 2f }
    internal var frameWidth = 1280
    internal var frameHeight = 720
    private var zoom = 1f; private var panX = 0f; private var panY = 0f
    private var cursorX = 0f; private var cursorY = 0f
    private var downX = 0f; private var downY = 0f
    private var lastMidY = 0f; private var lastMidX = 0f; private var lastSpan = 0f
    private var twoFinger: DesktopTwoFinger? = null
    private var scrolling = 0f
    private var moved = false; private var multi = false; private var held = false
    private var rightClicked = false; private var zooming = false; private var validDown = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val composition = DesktopComposition(model::text) { model.press(DesktopKeys.BACKSPACE) }
    private val longPress = Runnable {
        if (!moved && !multi && validDown && !model.drag) {
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
    fun resetZoom() { zoom = 1f; panX = 0f; panY = 0f; invalidate() }
    // UI-only adjustments share the renderer/input viewport. Gesture interpretation is unchanged.
    fun zoomBy(factor: Float) {
        val old = zoom
        zoom = (zoom * factor).coerceIn(1f, 4f)
        panX *= zoom / old; panY *= zoom / old
        clampPan(); invalidate()
    }
    fun panBy(horizontal: Float, vertical: Float) {
        panX += width * horizontal; panY += height * vertical
        clampPan(); invalidate()
    }
    private fun clampPan() {
        val vp = viewport()
        val x = maxOf(0f, (frameWidth * vp.scale - width) / 2)
        val y = maxOf(0f, (frameHeight * vp.scale - height) / 2)
        panX = panX.coerceIn(-x, x); panY = panY.coerceIn(-y, y)
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clampPan()
    }
    override fun onDraw(canvas: Canvas) {
        // drawColor fills the Canvas clip, NOT the View bounds. AndroidView does not
        // guarantee that clip: without this it painted over Compose's toolbar in rc1.
        val saved = canvas.save()
        try {
            canvas.clipRect(0, 0, width, height)
            canvas.drawColor(Color.BLACK)
            val token = image.draw(canvas, this)
            if (model.link == DesktopLink.CONNECTED) {
                val vp = viewport(); val x = vp.left + cursorX * vp.scale; val y = vp.top + cursorY * vp.scale
                canvas.drawLine(x - 8, y, x + 8, y, cursorPaint); canvas.drawLine(x, y - 8, x, y + 8, cursorPaint)
            }
            if (token != null) post { model.framePresented(token) }
        } catch (_: Exception) {
            val token = model.attempt
            post { model.renderingFailed(token) }
        } finally { canvas.restoreToCount(saved) }
    }
    private fun point(x: Float, y: Float, mask: Int = 0, clamp: Boolean = false): Boolean {
        val p = viewport().remote(x, y, clamp) ?: return false
        cursorX = p.first.toFloat(); cursorY = p.second.toFloat()
        model.pointer(p.first, p.second, mask); invalidate(); return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (model.link != DesktopLink.CONNECTED) return true
        // AppShell disables its edge observer for Desktop; AndroidView exclusively owns the image.
        parent?.requestDisallowInterceptTouchEvent(true)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus(); moved = false; multi = false; rightClicked = false; held = false; zooming = false
                downX = event.x; downY = event.y
                validDown = point(event.x, event.y)
                if (model.drag && validDown) { point(event.x, event.y, 1); held = true }
                if (validDown) postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(longPress); multi = true
                if (held) { model.releaseInput(); held = false }
                if (event.pointerCount == 2) {
                    lastMidX = (event.getX(0) + event.getX(1)) / 2
                    lastMidY = (event.getY(0) + event.getY(1)) / 2
                    lastSpan = hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
                    scrolling = 0f
                    twoFinger = DesktopTwoFinger(lastSpan, lastMidY, slop.toFloat())
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 2) {
                    val mx = (event.getX(0) + event.getX(1)) / 2; val my = (event.getY(0) + event.getY(1)) / 2
                    val span = hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
                    val gestureMode = twoFinger?.update(span, my) ?: DesktopTwoFinger.Mode.WAITING
                    zooming = gestureMode == DesktopTwoFinger.Mode.ZOOM
                    if (zooming) {
                        val old = viewport()
                        val rx = (mx - old.left) / old.scale; val ry = (my - old.top) / old.scale
                        zoom = (zoom * (span / lastSpan.coerceAtLeast(1f))).coerceIn(1f, 4f)
                        val next = viewport()
                        panX += mx - (next.left + rx * next.scale); panY += my - (next.top + ry * next.scale)
                    } else if (gestureMode == DesktopTwoFinger.Mode.SCROLL) {
                        scrolling += my - lastMidY
                        val step = 40 * resources.displayMetrics.density
                        repeat(minOf(8, (abs(scrolling) / step).toInt())) {
                            model.scroll(scrolling > 0); scrolling += if (scrolling > 0) -step else step
                        }
                        // Horizontal two-finger movement pans only the local viewport when zoomed.
                        if (zoom > 1) panX += mx - lastMidX
                    }
                    // Clamp pan so the framebuffer can always be recovered by Fit.
                    val vp = viewport()
                    panX = panX.coerceIn(-maxOf(0f, (frameWidth * vp.scale - width) / 2), maxOf(0f, (frameWidth * vp.scale - width) / 2))
                    panY = panY.coerceIn(-maxOf(0f, (frameHeight * vp.scale - height) / 2), maxOf(0f, (frameHeight * vp.scale - height) / 2))
                    lastSpan = span; lastMidX = mx; lastMidY = my; invalidate()
                } else if (!multi && validDown) {
                    if (hypot(event.x - downX, event.y - downY) > slop) { moved = true; removeCallbacks(longPress) }
                    if (!rightClicked) point(event.x, event.y, if (held) 1 else 0, clamp = true)
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (held) { point(event.x, event.y, 0, clamp = true); held = false }
                else if (validDown && !multi && !moved && !rightClicked) { model.click(); performClick() }
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(longPress); held = false; model.releaseInput() }
        }
        return true
    }
    fun showKeyboard() {
        requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }
    override fun onCheckIsTextEditor() = true
    override fun onCreateInputConnection(out: EditorInfo): InputConnection {
        out.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        out.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_ACTION_NONE
        out.initialSelStart = 0; out.initialSelEnd = 0
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                composition.commit(text?.toString() ?: ""); return true
            }
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                composition.compose(text?.toString() ?: ""); return true
            }
            override fun finishComposingText(): Boolean { composition.finish(); return true }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                composition.delete(beforeLength); repeat(afterLength.coerceIn(0, 256)) { model.press(DesktopKeys.DELETE) }; return true
            }
            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int) = deleteSurroundingText(beforeLength, afterLength)
            override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence = composition.composing.takeLast(length.coerceIn(0, 4096))
            override fun getTextAfterCursor(length: Int, flags: Int): CharSequence = ""
            override fun sendKeyEvent(event: KeyEvent): Boolean = handleKey(event)
        }
    }
    private fun handleKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return true // Every sent key is already paired.
        val key = when (event.keyCode) {
            KeyEvent.KEYCODE_DEL -> DesktopKeys.BACKSPACE; KeyEvent.KEYCODE_FORWARD_DEL -> DesktopKeys.DELETE
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> DesktopKeys.ENTER
            KeyEvent.KEYCODE_TAB -> DesktopKeys.TAB; KeyEvent.KEYCODE_ESCAPE -> DesktopKeys.ESC
            KeyEvent.KEYCODE_DPAD_LEFT -> 0xff51; KeyEvent.KEYCODE_DPAD_UP -> 0xff52
            KeyEvent.KEYCODE_DPAD_RIGHT -> 0xff53; KeyEvent.KEYCODE_DPAD_DOWN -> 0xff54
            KeyEvent.KEYCODE_MOVE_HOME -> 0xff50; KeyEvent.KEYCODE_MOVE_END -> 0xff57
            KeyEvent.KEYCODE_PAGE_UP -> 0xff55; KeyEvent.KEYCODE_PAGE_DOWN -> 0xff56
            else -> {
                val unicode = event.getUnicodeChar(event.metaState and KeyEvent.META_CTRL_MASK.inv() and KeyEvent.META_ALT_MASK.inv())
                if (unicode == 0) return true
                DesktopKeys.unicode(unicode)
            }
        }
        model.press(key, model.ctrl || event.isCtrlPressed, model.alt || event.isAltPressed, model.shift || event.isShiftPressed)
        return true
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
            super.onKeyDown(keyCode, event) else handleKey(event)
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
            super.onKeyUp(keyCode, event) else true
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus) { composition.clear(); removeCallbacks(longPress); model.releaseInput() }
    }
    fun release() {
        composition.clear(); removeCallbacks(longPress); model.releaseInput()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken, 0)
        image.close()
    }
}
