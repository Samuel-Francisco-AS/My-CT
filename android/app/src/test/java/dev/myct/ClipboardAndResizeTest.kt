package dev.myct

import com.termux.terminal.TerminalSession
import org.junit.Assert.*
import org.junit.Test

class ClipboardAndResizeTest {
    @Test fun explicitSelectionWorksButRemoteOscAndQueriesNeverReachClipboard() {
        var copies = 0
        var pastes = 0
        val sent = mutableListOf<ByteArray>()
        val session = TerminalSession(object : TerminalSession.Transport {
            override fun send(data: ByteArray) { sent.add(data) }
            override fun resize(columns: Int, rows: Int, widthPixels: Int, heightPixels: Int) = Unit
        }, SessionCallbacks())
        session.setUserClipboard(object : TerminalSession.UserClipboard {
            override fun copy(text: String) { assertEquals("selected", text); copies++ }
            override fun requestPaste() { pastes++ }
        })
        session.copySelectionToClipboard("selected")
        session.requestUserPaste()
        assertEquals(1, copies); assertEquals(1, pastes)
        // Feed a real OSC 52 write into the unmodified upstream parser.
        session.append("\u001b]52;c;c2VydmVyLXRleHQ=\u0007".toByteArray())
        session.append("\u001b]52;c;?\u0007".toByteArray())
        session.onCopyTextToClipboard("remote")
        session.onPasteTextFromClipboard()
        assertEquals(1, copies); assertEquals(1, pastes)
        assertTrue(sent.isEmpty())
        val latest = object : TerminalSession.UserClipboard {
            override fun copy(text: String) { copies++ }
            override fun requestPaste() { pastes++ }
        }
        session.setUserClipboard(latest)
        val old = object : TerminalSession.UserClipboard {
            override fun copy(text: String) = Unit
            override fun requestPaste() = Unit
        }
        session.clearUserClipboard(old)
        session.copySelectionToClipboard("new UI remains attached")
        assertEquals(2, copies)
        session.clearUserClipboard(latest)
        session.copySelectionToClipboard("detached")
        assertEquals(2, copies)
    }
    @Test fun clipboardLimitUsesUtf8BytesAndNeverSilentlyTruncates() {
        assertTrue(ClipboardPolicy.allowed("x".repeat(65_536)))
        assertFalse(ClipboardPolicy.allowed("x".repeat(65_537)))
        assertFalse(ClipboardPolicy.allowed("😀".repeat(20_000)))
        assertFalse(ClipboardPolicy.allowed(""))
        assertEquals(300, ClipboardPolicy.preview("a".repeat(500)).length)
    }
    @Test fun repeatedSizeIsDeduplicatedAndResizeCarriesPixelDimensions() {
        val sizes = mutableListOf<List<Int>>()
        val session = TerminalSession(object : TerminalSession.Transport {
            override fun send(data: ByteArray) = Unit
            override fun resize(columns: Int, rows: Int, widthPixels: Int, heightPixels: Int) {
                sizes.add(listOf(columns, rows, widthPixels, heightPixels))
            }
        }, SessionCallbacks())
        session.updateSize(51, 17, 8, 16)
        session.updateSize(51, 17, 8, 16)
        assertEquals(1, sizes.size)
        assertEquals(listOf(51, 17, 408, 272), sizes.last())
        session.updateSize(51, 32, 8, 16)
        assertEquals(32, session.emulator.mRows)
        assertEquals(2, sizes.size)
        session.requestPtyResize()
        assertEquals(listOf(51, 32, 408, 512), sizes.last())
        assertEquals(3, sizes.size)
        session.finish()
        session.requestPtyResize()
        assertEquals(3, sizes.size)
    }
}
