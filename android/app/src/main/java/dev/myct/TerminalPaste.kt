package dev.myct

import com.termux.terminal.TerminalSession

internal enum class PasteResult { SENT, INVALID_CLIPBOARD, SESSION_CHANGED }

/** Synchronous UI action. Capture the target before reading the system clipboard and check
 * it again afterwards. Never retain clipboard content or forward it to a replacement session. */
internal fun pasteTerminalClipboard(expected: TerminalSession?, current: () -> TerminalSession?,
                                   connected: () -> Boolean, readText: () -> String?): PasteResult {
    fun sameSession() = expected != null && expected === current() && expected.isRunning && connected()
    if (!sameSession()) return PasteResult.SESSION_CHANGED
    val text = readText()
    if (text == null || !ClipboardPolicy.allowed(text)) return PasteResult.INVALID_CLIPBOARD
    if (!sameSession()) return PasteResult.SESSION_CHANGED
    expected!!.emulator.paste(text)
    return PasteResult.SENT
}
