package dev.myct

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import android.graphics.Rect
import io.github.rosemoe.sora.widget.CodeEditor

/** Use Sora's own key handler for selection, indent, pages, clipboard and undo.
 * Direct dispatch stays inside this View; unsupported bindings cannot bubble to the Activity/PTTY.
 * Gboard's InputConnection and composing protocol are unchanged.
 */
internal class IdeEditorView(context: Context) : CodeEditor(context), IdeKeyTarget {
    var onEditorFocusLost: (() -> Unit)? = null
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus) onEditorFocusLost?.invoke()
    }
    override fun focus() { requestFocus() }
    override fun text(value: String) {
        if (isEditable) { commitText(value); notifyIMEExternalCursorChange() }
    }
    override fun key(stroke: IdeKeyStroke) {
        val now = SystemClock.uptimeMillis()
        val meta = (if (stroke.ctrl) KeyEvent.META_CTRL_ON else 0) or (if (stroke.alt) KeyEvent.META_ALT_ON else 0)
        fun event(action: Int, code: Int, flags: Int) = KeyEvent(now, now, action, code, 0, flags)
        try {
            // Sora tracks ALT/SHIFT through MetaKeyKeyListener; metaState on the letter alone
            // is sufficient for CTRL but is not sufficient for its ALT state.
            if (stroke.ctrl) onKeyDown(KeyEvent.KEYCODE_CTRL_LEFT, event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, meta))
            if (stroke.alt) onKeyDown(KeyEvent.KEYCODE_ALT_LEFT, event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT, meta))
            onKeyDown(stroke.code, event(KeyEvent.ACTION_DOWN, stroke.code, meta))
            onKeyUp(stroke.code, event(KeyEvent.ACTION_UP, stroke.code, meta))
        } finally {
            if (stroke.alt) onKeyUp(KeyEvent.KEYCODE_ALT_LEFT, event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ALT_LEFT, if (stroke.ctrl) KeyEvent.META_CTRL_ON else 0))
            if (stroke.ctrl) onKeyUp(KeyEvent.KEYCODE_CTRL_LEFT, event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, 0))
            keyMetaStates.clearMetaStates(KeyEvent.META_ALT_ON or KeyEvent.META_CTRL_ON)
        }
    }
    fun rememberViewport() = IdeViewport(offsetX, offsetY)
    fun restoreViewport(viewport: IdeViewport?) {
        if (viewport == null) ensureSelectionVisible()
        else {
            val position = viewport.bounded(scrollMaxX, scrollMaxY)
            scroller.startScroll(position.x, position.y, 0, 0, 0)
            scroller.abortAnimation(); invalidate()
        }
    }
}
