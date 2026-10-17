package dev.myct

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test

class IdeControlsTest {
    private class Target : IdeKeyTarget {
        val keys = mutableListOf<IdeKeyStroke>()
        val text = StringBuilder()
        var focused = 0
        override fun focus() { focused++ }
        override fun key(stroke: IdeKeyStroke) { keys += stroke }
        override fun text(value: String) { text.append(value) }
    }
    @Test fun sameApprovedGridTargetsLocalNativeKeyCodesAndLiteralCharacters() {
        val modifiers = IdeModifiers(); val target = Target()
        val expected = listOf(KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_MOVE_END, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PAGE_DOWN)
        TerminalExtraKey.entries.filter { it.keyCode != null }.forEach { modifiers.press(it, target) }
        assertEquals(expected, target.keys.map { it.code })
        assertTrue(target.keys.all { !it.ctrl && !it.alt })
        modifiers.press(TerminalExtraKey.SLASH, target); modifiers.press(TerminalExtraKey.MINUS, target)
        assertEquals("/-", target.text.toString())
        assertEquals(12, target.focused)
        assertEquals(2, TerminalExtraKey.rows.size); assertTrue(TerminalExtraKey.rows.all { it.size == 7 })
    }
    @Test fun controlNavigationAndAltShortcutAreOneShotAndIndependentOfTerminalModifiers() {
        val modifiers = IdeModifiers(); val terminal = TerminalModifiers(); terminal.toggleCtrl()
        val target = Target()
        modifiers.press(TerminalExtraKey.CTRL, target)
        modifiers.press(TerminalExtraKey.HOME, target)
        modifiers.press(TerminalExtraKey.END, target)
        assertEquals(listOf(IdeKeyStroke(KeyEvent.KEYCODE_MOVE_HOME, ctrl = true), IdeKeyStroke(KeyEvent.KEYCODE_MOVE_END)), target.keys)
        assertFalse(modifiers.ctrl); assertTrue(terminal.ctrl)
        modifiers.press(TerminalExtraKey.ALT, target)
        modifiers.shortcut(IdeShortcut.LINE_ABOVE, target)
        assertEquals(IdeKeyStroke(KeyEvent.KEYCODE_ENTER, ctrl = true, alt = true), target.keys.last())
        assertFalse(modifiers.alt); assertFalse(modifiers.ctrl)
    }
    @Test fun explicitShortcutsMapToSoraBindingsAndUnsupportedLiteralDoesNotInsertControlBytes() {
        val modifiers = IdeModifiers(); val target = Target()
        for (shortcut in IdeShortcut.entries) {
            modifiers.press(TerminalExtraKey.CTRL, target); modifiers.shortcut(shortcut, target)
            assertEquals(shortcut.stroke, target.keys.last()); assertFalse(modifiers.ctrl); assertFalse(modifiers.alt)
        }
        modifiers.press(TerminalExtraKey.CTRL, target); modifiers.press(TerminalExtraKey.SLASH, target)
        assertEquals("", target.text.toString()); assertFalse(modifiers.ctrl)
    }
    @Test fun modifiersCanBeCancelledOnTypingFocusNavigationAndBackgroundWithoutStickyState() {
        val modifiers = IdeModifiers(); val target = Target()
        repeat(4) {
            modifiers.press(TerminalExtraKey.CTRL, target); modifiers.press(TerminalExtraKey.ALT, target)
            modifiers.clear(); assertFalse(modifiers.ctrl); assertFalse(modifiers.alt)
        }
        modifiers.press(TerminalExtraKey.CTRL, target); modifiers.press(TerminalExtraKey.CTRL, target)
        assertFalse(modifiers.ctrl)
        val broken = object : IdeKeyTarget {
            override fun focus() = Unit
            override fun key(stroke: IdeKeyStroke) { throw IllegalStateException() }
            override fun text(value: String) = Unit
        }
        modifiers.press(TerminalExtraKey.CTRL, target)
        assertThrows(IllegalStateException::class.java) { modifiers.press(TerminalExtraKey.RIGHT, broken) }
        assertFalse(modifiers.ctrl)
    }
    @Test fun viewportRestorationClampsOnlyToTheNewLayoutBounds() {
        assertEquals(IdeViewport(12, 700), IdeViewport(12, 700).bounded(200, 900))
        assertEquals(IdeViewport(0, 400), IdeViewport(-1, 700).bounded(200, 400))
        assertEquals(IdeViewport(0, 0), IdeViewport(12, 700).bounded(-1, -1))
    }
}
