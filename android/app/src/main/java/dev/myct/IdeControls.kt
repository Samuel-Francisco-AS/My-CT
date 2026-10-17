package dev.myct

import android.view.KeyEvent
import androidx.compose.runtime.*

internal data class IdeKeyStroke(val code: Int, val ctrl: Boolean = false, val alt: Boolean = false)
internal enum class IdeShortcut(val label: String, val stroke: IdeKeyStroke, val edits: Boolean = false) {
    ALL("A · Tudo", IdeKeyStroke(KeyEvent.KEYCODE_A, ctrl = true)),
    COPY("C · Copiar", IdeKeyStroke(KeyEvent.KEYCODE_C, ctrl = true)),
    CUT("X · Cortar", IdeKeyStroke(KeyEvent.KEYCODE_X, ctrl = true), true),
    PASTE("V · Colar", IdeKeyStroke(KeyEvent.KEYCODE_V, ctrl = true), true),
    UNDO("Z · Desfazer", IdeKeyStroke(KeyEvent.KEYCODE_Z, ctrl = true), true),
    REDO("Y · Refazer", IdeKeyStroke(KeyEvent.KEYCODE_Y, ctrl = true), true),
    WORD("W · Palavra", IdeKeyStroke(KeyEvent.KEYCODE_W, ctrl = true)),
    DUPLICATE("D · Duplicar", IdeKeyStroke(KeyEvent.KEYCODE_D, ctrl = true), true),
    LINE_ABOVE("CTRL+ALT+ENTER · Linha acima", IdeKeyStroke(KeyEvent.KEYCODE_ENTER, ctrl = true, alt = true), true)
}

/** Editor-only target: deliberately has no SSH/session/transport capability. */
internal interface IdeKeyTarget {
    fun focus()
    fun key(stroke: IdeKeyStroke)
    fun text(value: String)
}
internal class IdeModifiers {
    var ctrl by mutableStateOf(false); private set
    var alt by mutableStateOf(false); private set
    fun clear() { ctrl = false; alt = false }
    fun press(key: TerminalExtraKey, target: IdeKeyTarget) {
        when (key) {
            TerminalExtraKey.CTRL -> { target.focus(); ctrl = !ctrl; return }
            TerminalExtraKey.ALT -> { target.focus(); alt = !alt; return }
            else -> Unit
        }
        val stroke = key.keyCode?.let { IdeKeyStroke(it, ctrl, alt) }
        val modified = ctrl || alt
        clear() // One-shot, including unsupported combinations and failures.
        target.focus()
        if (stroke != null) target.key(stroke)
        else if (!modified) key.codePoint?.let { target.text(it.toChar().toString()) }
    }
    fun shortcut(action: IdeShortcut, target: IdeKeyTarget) {
        clear(); target.focus(); target.key(action.stroke)
    }
}

/** In-memory visual state, separate from content, selection and undo. */
internal data class IdeViewport(val x: Int, val y: Int) {
    fun bounded(maxX: Int, maxY: Int) = IdeViewport(x.coerceIn(0, maxX.coerceAtLeast(0)), y.coerceIn(0, maxY.coerceAtLeast(0)))
}

/** Same named-anchor policy as Files, with its own bounded history; no FilesModel instance. */
internal class IdeScrollHistory {
    private val positions = object : java.util.LinkedHashMap<String, FilesScrollAnchor>(16, .75f, true) {
        override fun removeEldestEntry(entry: MutableMap.MutableEntry<String, FilesScrollAnchor>?) = size > 64
    }
    fun remember(path: String, entries: List<RemoteEntry>, index: Int, offset: Int) {
        positions[path] = FilesScrollAnchor(entries.getOrNull(index)?.name, index.coerceAtLeast(0), offset.coerceAtLeast(0))
    }
    fun position(path: String): FilesScrollAnchor? = positions[path]
    fun clear() = positions.clear()
}
