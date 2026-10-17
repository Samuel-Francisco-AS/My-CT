package dev.myct

import android.view.KeyEvent
import android.view.MotionEvent
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalViewClient

/** Never log terminal bytes, passwords, IME events, or remote exception messages. */
open class SessionCallbacks(private val diagnostics: (() -> TerminalDiagnostics)? = null) : TerminalSessionClient {
    var screenChanged: (() -> Unit)? = null
    override fun onTextChanged(session: TerminalSession) {
        val metrics = diagnostics?.invoke()?.takeIf { it.isSampling }
        val started = if (metrics != null) System.nanoTime() else 0L
        try { screenChanged?.invoke() }
        finally { metrics?.record(TerminalDiagnostics.Stage.TEXT, started, System.nanoTime() - started) }
    }
    override fun onTitleChanged(session: TerminalSession) = Unit
    override fun onSessionFinished(session: TerminalSession) { screenChanged?.invoke() }
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) = Unit
    override fun onPasteTextFromClipboard(session: TerminalSession) = Unit
    override fun onBell(session: TerminalSession) = Unit
    override fun onColorsChanged(session: TerminalSession) { screenChanged?.invoke() }
    override fun onTerminalCursorStateChange(state: Boolean) = Unit
    override fun getTerminalCursorStyle(): Int? = null
    override fun logError(tag: String, message: String) = Unit
    override fun logWarn(tag: String, message: String) = Unit
    override fun logInfo(tag: String, message: String) = Unit
    override fun logDebug(tag: String, message: String) = Unit
    override fun logVerbose(tag: String, message: String) = Unit
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) = Unit
    override fun logStackTrace(tag: String, e: Exception) = Unit
}

class ViewCallbacks(private val model: TerminalModel, private val showKeyboard: () -> Unit) : TerminalViewClient {
    override fun shouldMeasurePerformance() = model.diagnostics.isSampling
    override fun onInputTiming(kind: Int, startedAt: Long, duration: Long, left: Int, right: Int) {
        when (kind) {
            0 -> model.diagnostics.record(TerminalDiagnostics.Stage.COMPOSE, startedAt, duration)
            1 -> model.diagnostics.record(TerminalDiagnostics.Stage.COMMIT, startedAt, duration)
            2 -> model.diagnostics.deletion(startedAt, duration, left, right)
        }
    }
    override fun onKeyTiming(startedAt: Long, duration: Long, age: Long, delete: Boolean, repeat: Boolean) {
        model.diagnostics.key(startedAt, duration, age, delete, repeat)
    }
    override fun onScreenUpdateTiming(startedAt: Long, duration: Long) {
        model.diagnostics.record(TerminalDiagnostics.Stage.UPDATE, startedAt, duration)
    }
    override fun onDrawTiming(startedAt: Long, duration: Long, updateAt: Long) {
        model.diagnostics.record(TerminalDiagnostics.Stage.DRAW, startedAt, duration)
        if (updateAt != 0L) model.diagnostics.record(TerminalDiagnostics.Stage.DRAW_WAIT, updateAt, startedAt - updateAt)
    }
    override fun onScale(scale: Float) = 1f
    override fun onSingleTapUp(e: MotionEvent) { showKeyboard() }
    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = model.enforceCharBasedInput
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession) = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
    override fun onLongPress(e: MotionEvent) = false
    override fun readControlKey(): Boolean = model.consumeCtrl()
    override fun readAltKey() = false
    override fun readShiftKey() = false
    override fun readFnKey() = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession) = false
    override fun onEmulatorSet() = Unit
    override fun logError(tag: String, message: String) = Unit
    override fun logWarn(tag: String, message: String) = Unit
    override fun logInfo(tag: String, message: String) = Unit
    override fun logDebug(tag: String, message: String) = Unit
    override fun logVerbose(tag: String, message: String) = Unit
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) = Unit
    override fun logStackTrace(tag: String, e: Exception) = Unit
}
