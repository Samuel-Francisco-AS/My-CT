package dev.myct

import com.termux.terminal.TerminalSession
import org.junit.Assert.*
import org.junit.Test

class TerminalPasteTest {
    private val sent = mutableListOf<ByteArray>()
    private fun session() = TerminalSession(object : TerminalSession.Transport {
        override fun send(data: ByteArray) { sent.add(data) }
        override fun resize(columns: Int, rows: Int, widthPixels: Int, heightPixels: Int) = Unit
    }, SessionCallbacks())
    private fun output() = sent.flatMap { it.toList() }.toByteArray().toString(Charsets.UTF_8)

    @Test fun simpleAndMultilinePasteAreSentBeforeRequestReturnsWithoutConfirmation() {
        val target = session()
        assertEquals(PasteResult.SENT, pasteTerminalClipboard(target, { target }, { true }) { "olá" })
        assertEquals("olá", output())
        assertEquals(PasteResult.SENT, pasteTerminalClipboard(target, { target }, { true }) { "a\nb\r\nc" })
        assertEquals("oláa\rb\rc", output())
    }

    @Test fun bracketedPasteAndUpstreamSanitizationRemainInEffect() {
        val target = session()
        target.append("\u001b[?2004h".toByteArray())
        assertEquals(PasteResult.SENT, pasteTerminalClipboard(target, { target }, { true }) { "a\n\u001bb\u0085c" })
        assertEquals("\u001b[200~a\rbc\u001b[201~", output())
    }

    @Test fun invalidClipboardIsRejectedWithoutTruncation() {
        val target = session()
        listOf(null, "", "x".repeat(65_537), "😀".repeat(16_385)).forEach { text ->
            assertEquals(PasteResult.INVALID_CLIPBOARD, pasteTerminalClipboard(target, { target }, { true }) { text })
        }
        assertTrue(sent.isEmpty())
        assertEquals(PasteResult.SENT, pasteTerminalClipboard(target, { target }, { true }) { "x".repeat(65_536) })
        assertEquals(65_536, sent.sumOf { it.size })
    }

    @Test fun disconnectedMissingReplacedAndFinishedSessionsDoNotEvenReadClipboard() {
        val target = session(); val other = session()
        fun noRead(): String? { fail("Clipboard must not be read"); return null }
        assertEquals(PasteResult.SESSION_CHANGED, pasteTerminalClipboard(target, { target }, { false }, ::noRead))
        assertEquals(PasteResult.SESSION_CHANGED, pasteTerminalClipboard(null, { target }, { true }, ::noRead))
        assertEquals(PasteResult.SESSION_CHANGED, pasteTerminalClipboard(target, { null }, { true }, ::noRead))
        assertEquals(PasteResult.SESSION_CHANGED, pasteTerminalClipboard(target, { other }, { true }, ::noRead))
        target.finish()
        assertEquals(PasteResult.SESSION_CHANGED, pasteTerminalClipboard(target, { target }, { true }, ::noRead))
        assertTrue(sent.isEmpty())
    }

    @Test fun replacementDisconnectOrFinishDuringClipboardReadCannotRedirectInput() {
        repeat(3) { change ->
            val original = session(); val replacement = session()
            var current = original; var connected = true
            assertEquals(PasteResult.SESSION_CHANGED, pasteTerminalClipboard(original, { current }, { connected }) {
                when (change) {
                    0 -> current = replacement
                    1 -> connected = false
                    else -> original.finish()
                }
                "never sent"
            })
        }
        assertTrue(sent.isEmpty())
    }

    @Test fun clipboardReadFailureSendsNothingAndDoesNotRetainARequest() {
        val target = session()
        assertThrows(SecurityException::class.java) {
            pasteTerminalClipboard(target, { target }, { true }) { throw SecurityException() }
        }
        assertTrue(sent.isEmpty())
        assertEquals(PasteResult.SENT, pasteTerminalClipboard(target, { target }, { true }) { "new request" })
        assertEquals("new request", output())
    }
}
