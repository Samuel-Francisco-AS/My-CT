package dev.myct

import androidx.compose.runtime.*
import com.termux.terminal.TerminalSession

/** A UI attachment captures an epoch. Even switching away and back invalidates old IME actions. */
internal class TerminalSelection {
    var environment by mutableStateOf(IdeEnvironment.LOCAL); private set
    private var epoch = 0
    fun select(value: IdeEnvironment) { if (value != environment) { epoch++; environment = value } }
    fun bind(session: TerminalSession?, modifiers: TerminalModifiers, current: () -> TerminalSession?, running: () -> Boolean): TerminalInputTarget {
        val revision = epoch
        return TerminalInputTarget(session, modifiers) { revision == epoch && session != null && session === current() && session.isRunning && running() }
    }
}

internal class TerminalInputTarget(private val expected: TerminalSession?, val modifiers: TerminalModifiers, private val valid: () -> Boolean) {
    fun accepts(session: TerminalSession? = expected) = session === expected && valid()
}
