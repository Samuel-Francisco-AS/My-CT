package dev.myct

import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession

internal enum class TerminalExtraKey(val label: String, val description: String,
                                    val keyCode: Int? = null, val codePoint: Int? = null) {
    ESC("ESC", "Escape", KeyEvent.KEYCODE_ESCAPE),
    SLASH("/", "Barra", codePoint = '/'.code),
    MINUS("−", "Hífen", codePoint = '-'.code),
    HOME("HOME", "Início", KeyEvent.KEYCODE_MOVE_HOME),
    UP("↑", "Seta para cima", KeyEvent.KEYCODE_DPAD_UP),
    END("END", "Fim", KeyEvent.KEYCODE_MOVE_END),
    PGUP("PGUP", "Página acima", KeyEvent.KEYCODE_PAGE_UP),
    TAB("⇥", "Tabulação", KeyEvent.KEYCODE_TAB),
    CTRL("CTRL", "Control: aplicar à próxima tecla"),
    ALT("ALT", "Alt: aplicar à próxima tecla"),
    LEFT("←", "Seta para a esquerda", KeyEvent.KEYCODE_DPAD_LEFT),
    DOWN("↓", "Seta para baixo", KeyEvent.KEYCODE_DPAD_DOWN),
    RIGHT("→", "Seta para a direita", KeyEvent.KEYCODE_DPAD_RIGHT),
    PGDN("PGDN", "Página abaixo", KeyEvent.KEYCODE_PAGE_DOWN);

    companion object { val rows = entries.chunked(7) }
}

/** Read without consuming: TerminalView may read twice for the same Android KeyEvent.
 * Consume only when a mapped key or code point is actually accepted. No locked mode. */
internal class TerminalModifiers {
    var ctrl by mutableStateOf(false)
        private set
    var alt by mutableStateOf(false)
        private set
    fun toggleCtrl() { ctrl = !ctrl }
    fun toggleAlt() { alt = !alt }
    fun clear() { ctrl = false; alt = false }
    fun keyMode() = (if (ctrl) KeyHandler.KEYMOD_CTRL else 0) or (if (alt) KeyHandler.KEYMOD_ALT else 0)

    fun sendKey(session: TerminalSession, keyCode: Int, eventMode: Int = 0): Boolean {
        if (!session.isRunning) return false
        val emulator = session.emulator
        val sequence = KeyHandler.getCode(keyCode, keyMode() or eventMode,
            emulator.isCursorKeysApplicationMode, emulator.isKeypadApplicationMode) ?: return false
        emulator.setCursorBlinkState(true)
        clear()
        session.write(sequence)
        return true
    }

    fun press(key: TerminalExtraKey, session: TerminalSession, inputCodePoint: (Int) -> Unit) {
        if (!session.isRunning) { clear(); return }
        when (key) {
            TerminalExtraKey.CTRL -> toggleCtrl()
            TerminalExtraKey.ALT -> toggleAlt()
            else -> key.keyCode?.let { sendKey(session, it) }
                ?: key.codePoint?.let { inputCodePoint(it) }
        }
    }
}
