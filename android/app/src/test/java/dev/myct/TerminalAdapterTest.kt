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

    @Test fun utf8BoundariesAltAndInvalidCodepoints() {
        val points = intArrayOf(0, 32, 127, 128, 0x7FF, 0x800, 0xD7FF, 0xE000, 0xFFFF, 0x10000, 0x10FFFF)
        for (point in points) {
            val expected = String(Character.toChars(point)).toByteArray(Charsets.UTF_8)
            session.writeCodePoint(false, point)
            assertArrayEquals(expected, sent.last())
            session.writeCodePoint(true, point)
            assertArrayEquals(byteArrayOf(27) + expected, sent.last())
        }
        val count = sent.size
        for (invalid in intArrayOf(-1, 0xD800, 0xDFFF, 0x110000)) session.writeCodePoint(false, invalid)
        assertEquals(count, sent.size)
    }

    @Test fun committedTextSharesOneSendWithoutWaitingForAnotherEvent() {
        val text = "ação e\u0301 😀 " + "x".repeat(100)
        session.beginInputEvent()
        text.codePoints().forEach { session.writeCodePoint(false, it) }
        session.endInputEvent()
        assertEquals(1, sent.size) // Previously >64 queued actions from this single IME commit.
        assertArrayEquals(text.toByteArray(), sent.single())
        session.writeCodePoint(false, ' '.code)
        assertArrayEquals(byteArrayOf(32), sent.last()) // A separate space is immediate.
    }

    @Test fun controlsFlushImmediatelyAndKeepByteOrder() {
        session.beginInputEvent()
        session.writeCodePoint(false, 'a'.code)
        session.writeCodePoint(false, ' '.code)
        session.writeCodePoint(false, 127)
        assertArrayEquals("a ".toByteArray(), sent[0])
        assertArrayEquals(byteArrayOf(127), sent[1])
        session.writeCodePoint(false, 13)
        assertArrayEquals(byteArrayOf(13), sent.last())
        session.writeCodePoint(false, 3)
        assertArrayEquals(byteArrayOf(3), sent.last())
        session.writeCodePoint(false, 'b'.code)
        session.writeCodePoint(true, 'f'.code)
        assertArrayEquals("b".toByteArray(), sent[sent.size - 2])
        assertArrayEquals(byteArrayOf(27, 102), sent.last())
        session.endInputEvent()
        assertArrayEquals(byteArrayOf(97, 32, 127, 13, 3, 98, 27, 102), sent.flatMap { it.toList() }.toByteArray())
    }

    @Test fun rapidKeysMappedBackspacesAndCallerBuffersRemainIndependent() {
        val bytes = byteArrayOf(0, 97, 98, 0)
        session.write(bytes, 1, 2)
        bytes.fill(0)
        assertArrayEquals("ab".toByteArray(), sent.single())
        repeat(128) { session.writeCodePoint(false, 'x'.code) }
        val delete = com.termux.terminal.KeyHandler.getCode(android.view.KeyEvent.KEYCODE_DEL, 0, false, false)
        val enter = com.termux.terminal.KeyHandler.getCode(android.view.KeyEvent.KEYCODE_ENTER, 0, false, false)
        repeat(128) { session.write(delete) }
        session.write(enter)
        assertArrayEquals(("ab" + "x".repeat(128) + "\u007f".repeat(128) + "\r").toByteArray(),
            sent.flatMap { it.toList() }.toByteArray())
    }

    @Test fun boundedEventAndMappedWritesPreserveOrder() {
        val text = "á😀".repeat(3000)
        session.beginInputEvent()
        text.codePoints().forEach { session.writeCodePoint(false, it) }
        session.write("\u001b[A")
        session.endInputEvent()
        assertTrue(sent.all { it.size <= 4099 })
        assertArrayEquals((text + "\u001b[A").toByteArray(), sent.flatMap { it.toList() }.toByteArray())
        session.beginInputEvent()
        session.writeCodePoint(false, 97)
        session.finish()
        val count = sent.size
        session.endInputEvent()
        session.writeCodePoint(false, 98)
        assertEquals(count, sent.size)
    }

    @Test fun appendUsesOnlyReadLengthAndCanReuseBufferForSplitUnicode() {
        val bytes = byteArrayOf(0xC3.toByte(), 0x41, 0x41)
        session.append(bytes, 1)
        bytes[0] = 0xA7.toByte()
        session.append(bytes, 1)
        assertEquals("ç", session.emulator.getSelectedText(0, 0, 0, 0))
        bytes[0] = 'x'.code.toByte()
        session.append(bytes, 1)
        assertEquals("çx", session.emulator.getSelectedText(0, 0, 1, 0))
    }
}
