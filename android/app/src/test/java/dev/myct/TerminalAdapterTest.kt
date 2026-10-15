package dev.myct

import com.termux.terminal.TerminalSession
import org.junit.Assert.*
import org.junit.Test

class TerminalAdapterTest {
    private val sent = mutableListOf<ByteArray>()
    private val sizes = mutableListOf<Pair<Int, Int>>()
    private val session = TerminalSession(object : TerminalSession.Transport {
        override fun send(data: ByteArray) { sent.add(data) }
        override fun resize(columns: Int, rows: Int, widthPixels: Int, heightPixels: Int) { sizes.add(columns to rows) }
    }, SessionCallbacks())
    private fun feed(text: String) { session.append(text.toByteArray(Charsets.UTF_8)) }

    @Test fun ansiCursorAlternateScreenAndResize() {
        session.updateSize(80, 24, 8, 16)
        feed("main\u001b[?1049h\u001b[2J\u001b[H\u001b[31mRED\u001b[0m")
        assertTrue(session.emulator.isAlternateBufferActive)
        assertEquals("RED", session.emulator.getSelectedText(0, 0, 2, 0))
        feed("\u001b[?1049l")
        assertFalse(session.emulator.isAlternateBufferActive)
        assertEquals("main", session.emulator.getSelectedText(0, 0, 3, 0))
        session.updateSize(40, 12, 8, 16)
        assertEquals(40, session.emulator.mColumns)
        assertEquals(12, session.emulator.mRows)
        assertEquals(40 to 12, sizes.last())
    }
    @Test fun unicodeInputControlAndClosedSession() {
        session.writeCodePoint(false, 0x1F600)
        assertArrayEquals("😀".toByteArray(), sent.last())
        session.write("\u0003")
        assertArrayEquals(byteArrayOf(3), sent.last())
        val count = sent.size
        session.finish()
        session.write("ignored")
        assertEquals(count, sent.size)
    }
}
