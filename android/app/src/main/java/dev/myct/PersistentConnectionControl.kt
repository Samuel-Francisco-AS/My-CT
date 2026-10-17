package dev.myct

import com.termux.terminal.TerminalSession

/** Presentation guards only. Permission, notification and service state stay with Activity/FGS. */
internal fun requestPersistenceChange(connected: Boolean, persistent: Boolean, requested: Boolean,
                                      enable: () -> Unit, confirmStop: () -> Unit) {
    if (!connected || requested == persistent) return
    if (requested) enable() else confirmStop()
}

internal fun canConfirmPersistenceStop(connected: Boolean, persistent: Boolean,
                                        expected: TerminalSession?, current: TerminalSession?): Boolean =
    connected && persistent && expected != null && expected === current

internal fun confirmPersistenceStop(connected: Boolean, persistent: Boolean,
                                     expected: TerminalSession?, current: TerminalSession?, stop: () -> Unit) {
    if (canConfirmPersistenceStop(connected, persistent, expected, current)) stop()
}
