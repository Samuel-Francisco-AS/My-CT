package dev.myct

import android.content.Context
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Selection
import android.text.TextUtils
import android.view.KeyEvent
import android.view.inputmethod.*
import java.text.Normalizer

/** Small local editor mirror of client-typed text only. No remote text/clipboard or persistent state. */
internal class DesktopInputConnection(private val target: DesktopView, private val model: DesktopModel) : BaseInputConnection(target, true) {
    private val localBuffer = SpannableStringBuilder().apply { Selection.setSelection(this, 0) }
    override fun getEditable(): Editable = localBuffer
    private val attempt = model.attempt
    private var closed = false
    private var batch = 0
    private var remoteTail = ""
    private fun valid() = !closed && target.acceptsInput(this, attempt)
    private fun composing() = getComposingSpanStart(editable) >= 0 && getComposingSpanEnd(editable) > getComposingSpanStart(editable)
    private fun atEnd() = Selection.getSelectionStart(editable) == editable.length && Selection.getSelectionEnd(editable) == editable.length
    private fun wellFormed(text: CharSequence): Boolean {
        var i = 0
        while (i < text.length) {
            val c = text[i++]
            if (Character.isHighSurrogate(c)) {
                if (i >= text.length || !Character.isLowSurrogate(text[i++])) return false
            } else if (Character.isLowSurrogate(c)) return false
        }
        return true
    }
    private fun notifySelection() {
        if (batch != 0 || !valid()) return
        (target.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).updateSelection(target,
            Selection.getSelectionStart(editable), Selection.getSelectionEnd(editable), getComposingSpanStart(editable), getComposingSpanEnd(editable))
    }
    private fun flushRemote() {
        if (!valid() || batch != 0 || composing() || !atEnd()) return
        val normalized = Normalizer.normalize(editable.toString(), Normalizer.Form.NFC)
        val delta = DesktopTextDelta.between(remoteTail, normalized)
        // Control/Alt text is a shortcut, not a reliable mirror of the remote document.
        if (model.ctrl || model.alt) {
            if (delta.suffix.isNotEmpty()) model.replaceTypedTail(0, delta.suffix)
            editable.clear(); remoteTail = ""; Selection.setSelection(editable, 0)
            return
        }
        if (delta.backspaces != 0 || delta.suffix.isNotEmpty()) model.replaceTypedTail(delta.backspaces, delta.suffix)
        val points = normalized.codePoints().toArray()
        remoteTail = String(points, (points.size - 1024).coerceAtLeast(0), points.size.coerceAtMost(1024))
        editable.replace(0, editable.length, remoteTail); Selection.setSelection(editable, editable.length)
    }
    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (!valid() || newCursorPosition != 1 || text == null || text.length > 4096 || !wellFormed(text)) return false
        if (!super.commitText(text, newCursorPosition)) return false
        flushRemote(); notifySelection(); return true
    }
    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (!valid() || newCursorPosition != 1 || text == null || text.length > 4096 || !wellFormed(text)) return false
        val result = super.setComposingText(text, newCursorPosition); notifySelection(); return result
    }
    override fun setComposingRegion(start: Int, end: Int): Boolean {
        // Tail replacement is supported; arbitrary remote cursor/selection is unknown.
        if (!valid() || !atEnd()) return false
        val a = minOf(start, end).coerceIn(0, editable.length); val b = maxOf(start, end).coerceIn(0, editable.length)
        if (a == b) return finishComposingText()
        if (b != editable.length || a > 0 && Character.isHighSurrogate(editable[a - 1]) && Character.isLowSurrogate(editable[a])) return false
        return super.setComposingRegion(a, b)
    }
    override fun finishComposingText(): Boolean {
        if (!valid()) return false
        super.finishComposingText(); flushRemote(); notifySelection(); return true
    }
    override fun setSelection(start: Int, end: Int): Boolean {
        if (!valid() || start != editable.length || end != start) return false
        return super.setSelection(start, end)
    }
    private fun delete(before: Int, after: Int, codePoints: Boolean): Boolean {
        if (!valid() || !atEnd() || before < 0 || after < 0) return false
        val b = before.coerceAtMost(256); val a = after.coerceAtMost(256)
        val unknown = editable.isEmpty() && !composing()
        // Reject UTF-16 ranges that split a surrogate; the code-points API delegates to Android.
        val cut = (editable.length - b).coerceAtLeast(0)
        if (!codePoints && cut > 0 && cut < editable.length && Character.isHighSurrogate(editable[cut - 1]) && Character.isLowSurrogate(editable[cut])) return false
        val ok = if (codePoints) super.deleteSurroundingTextInCodePoints(b, a) else super.deleteSurroundingText(b, a)
        if (!ok) return false
        if (unknown) {
            // No document mirror is available. One explicit erase is safe; never guess a word length.
            if (b > 0) model.press(DesktopKeys.BACKSPACE)
            if (a > 0) model.press(DesktopKeys.DELETE)
        } else {
            flushRemote()
            if (a > 0 && !composing()) model.press(DesktopKeys.DELETE)
        }
        notifySelection(); return true
    }
    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int) = delete(beforeLength, afterLength, false)
    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int) = delete(beforeLength, afterLength, true)
    override fun beginBatchEdit(): Boolean { if (!valid()) return false; batch++; return true }
    override fun endBatchEdit(): Boolean { if (!valid() || batch == 0) return false; batch--; if (batch == 0) flushRemote(); notifySelection(); return batch > 0 }
    override fun getCursorCapsMode(reqModes: Int) = if (valid()) TextUtils.getCapsMode(editable, editable.length, reqModes) else 0
    override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence? = if (valid()) super.getTextBeforeCursor(length.coerceIn(0, 2048), flags) else null
    override fun getTextAfterCursor(length: Int, flags: Int): CharSequence? = if (valid()) super.getTextAfterCursor(length.coerceIn(0, 2048), flags) else null
    override fun getSelectedText(flags: Int): CharSequence? = if (valid()) super.getSelectedText(flags) else null
    override fun sendKeyEvent(event: KeyEvent): Boolean = valid() && target.remoteKey(event)
    override fun performEditorAction(actionCode: Int): Boolean {
        if (!valid()) return false
        finishComposingText(); target.specialKey(DesktopKeys.ENTER); return true
    }
    override fun commitCorrection(correctionInfo: CorrectionInfo?) = valid() // Informational; commitText applies the change.
    private fun clearLocal() { closed = true; remoteTail = ""; editable.clear() }
    fun discard() { clearLocal(); super.closeConnection() }
    override fun closeConnection() {
        if (valid()) finishComposingText()
        clearLocal(); super.closeConnection()
    }
}
