package dev.myct

import android.view.KeyEvent
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class TerminalExtraKeysTest {
    private val sent = mutableListOf<ByteArray>()
    private val session = TerminalSession(object : TerminalSession.Transport {
        override fun send(data: ByteArray) { sent.add(data) }
        override fun resize(columns: Int, rows: Int, widthPixels: Int, heightPixels: Int) = Unit
    }, SessionCallbacks())
    private val modifiers = TerminalModifiers()
    private fun output() = sent.flatMap { it.toList() }.toByteArray().toString(Charsets.UTF_8)

    // Exercise the actual vendored inputCodePoint algorithm without an Android View constructor.
    // This fixture tests terminal bytes only, never rendering, touch, IME integration or haptics.
    private fun inputView(): TerminalView {
        val allocator = Class.forName("sun.misc.Unsafe")
        val field = allocator.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val view = allocator.getMethod("allocateInstance", Class::class.java)
            .invoke(field.get(null), TerminalView::class.java) as TerminalView
        view.mTermSession = session
        view.mEmulator = session.emulator
        view.mClient = Proxy.newProxyInstance(TerminalViewClient::class.java.classLoader,
            arrayOf(TerminalViewClient::class.java)) { _, method, _ ->
            when (method.name) {
                "readControlKey" -> modifiers.ctrl
                "readAltKey" -> modifiers.alt
                "onCodePoint" -> { modifiers.clear(); false }
                else -> if (method.returnType == Boolean::class.javaPrimitiveType) false else null
            }
        } as TerminalViewClient
        return view
    }

    @Test fun exactFourteenPositionsAndActions() {
        assertEquals(listOf(listOf("ESC", "/", "−", "HOME", "↑", "END", "PGUP"),
            listOf("⇥", "CTRL", "ALT", "←", "↓", "→", "PGDN")), TerminalExtraKey.rows.map { row -> row.map { it.label } })
        val codes = listOf(KeyEvent.KEYCODE_ESCAPE, null, null, KeyEvent.KEYCODE_MOVE_HOME,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_MOVE_END, KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_TAB, null, null, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PAGE_DOWN)
        assertEquals(codes, TerminalExtraKey.entries.map { it.keyCode })
        val view = inputView()
        TerminalExtraKey.entries.forEach { key -> modifiers.press(key, session) { view.inputCodePoint(it, false, false) } }
        assertEquals("\u001b/-\u001b[H\u001b[A\u001b[F\u001b[5~\t\u001b[1;7D\u001b[B\u001b[C\u001b[6~", output())
        assertFalse(modifiers.ctrl); assertFalse(modifiers.alt)
    }

    @Test fun navigationUsesNormalAndApplicationCursorModes() {
        val keys = listOf(TerminalExtraKey.HOME, TerminalExtraKey.END, TerminalExtraKey.PGUP,
            TerminalExtraKey.PGDN, TerminalExtraKey.UP, TerminalExtraKey.DOWN, TerminalExtraKey.LEFT, TerminalExtraKey.RIGHT)
        keys.forEach { modifiers.press(it, session) { fail("Not a character") } }
        assertEquals("\u001b[H\u001b[F\u001b[5~\u001b[6~\u001b[A\u001b[B\u001b[D\u001b[C", output())
        sent.clear()
        session.append("\u001b[?1h".toByteArray())
        keys.forEach { modifiers.press(it, session) { fail("Not a character") } }
        assertEquals("\u001bOH\u001bOF\u001b[5~\u001b[6~\u001bOA\u001bOB\u001bOD\u001bOC", output())
    }

    @Test fun literalSlashAndVisualMinusInsertAsciiThroughUpstreamView() {
        val view = inputView()
        listOf(TerminalExtraKey.SLASH, TerminalExtraKey.MINUS).forEach { key ->
            modifiers.press(key, session) { view.inputCodePoint(it, false, false) }
        }
        assertArrayEquals(byteArrayOf(47, 45), sent.flatMap { it.toList() }.toByteArray())
        modifiers.toggleCtrl()
        modifiers.press(TerminalExtraKey.SLASH, session) { view.inputCodePoint(it, false, false) }
        assertEquals(31, sent.last().single().toInt())
        modifiers.toggleAlt()
        modifiers.press(TerminalExtraKey.MINUS, session) { view.inputCodePoint(it, false, false) }
        assertArrayEquals(byteArrayOf(27, 45), sent.last())
    }

    @Test fun ctrlCAltAndCombinedModifiersAreOneShotForImeCodePoints() {
        val view = inputView()
        modifiers.toggleCtrl()
        view.inputCodePoint('c'.code, false, false)
        view.inputCodePoint('c'.code, false, false)
        modifiers.toggleAlt()
        view.inputCodePoint('b'.code, false, false)
        view.inputCodePoint('f'.code, false, false)
        modifiers.toggleCtrl(); modifiers.toggleAlt()
        view.inputCodePoint('C'.code, false, false)
        assertEquals("\u0003c\u001bbf\u001b\u0003", output())
        assertFalse(modifiers.ctrl); assertFalse(modifiers.alt)
    }

    @Test fun repeatedReadsAndPhysicalModifiersDoNotLeaveVirtualModifiersArmed() {
        val view = inputView()
        modifiers.toggleCtrl(); modifiers.toggleAlt()
        repeat(2) { assertTrue(modifiers.ctrl); assertTrue(modifiers.alt) }
        // Equivalent to onKeyDown's captured event flags, then inputCodePoint reading again.
        view.inputCodePoint('c'.code, true, true)
        view.inputCodePoint('x'.code, false, false)
        assertEquals("\u001b\u0003x", output())
        modifiers.toggleCtrl(); modifiers.toggleCtrl()
        modifiers.toggleAlt(); modifiers.toggleAlt()
        assertEquals(0, modifiers.keyMode())
    }

    @Test fun auxiliaryAndAndroidMappedKeysShareModifierSemantics() {
        modifiers.toggleCtrl()
        modifiers.press(TerminalExtraKey.HOME, session) { fail() }
        modifiers.toggleAlt()
        modifiers.press(TerminalExtraKey.END, session) { fail() }
        modifiers.toggleAlt()
        assertTrue(modifiers.sendKey(session, KeyEvent.KEYCODE_DPAD_RIGHT, KeyHandler.KEYMOD_CTRL))
        assertEquals("\u001b[1;5H\u001b[1;3F\u001b[1;7C", output())
        assertEquals(0, modifiers.keyMode())
        modifiers.toggleAlt()
        assertFalse(modifiers.sendKey(session, KeyEvent.KEYCODE_ALT_LEFT))
        assertTrue(modifiers.alt) // Pressing only a physical modifier does not consume a latch.
        modifiers.clear()
        assertEquals(0, modifiers.keyMode())
    }

    @Test fun imeAuxiliaryAndPasteKeepInputOrder() {
        val view = inputView()
        session.beginInputEvent()
        "ab".forEach { view.inputCodePoint(it.code, false, false) }
        session.endInputEvent()
        modifiers.press(TerminalExtraKey.HOME, session) { fail() }
        assertEquals(PasteResult.SENT, pasteTerminalClipboard(session, { session }, { true }) { "x\ny" })
        modifiers.toggleCtrl()
        view.inputCodePoint('c'.code, false, false)
        modifiers.toggleAlt()
        view.inputCodePoint('f'.code, false, false)
        assertEquals("ab\u001b[Hx\ry\u0003\u001bf", output())
    }

    @Test fun finishedSessionNeverReceivesExtraKeysOrKeepsModifiers() {
        session.finish()
        modifiers.toggleCtrl(); modifiers.toggleAlt()
        TerminalExtraKey.entries.forEach { modifiers.press(it, session) { fail() } }
        assertEquals("", output()); assertEquals(0, modifiers.keyMode())
    }
}
