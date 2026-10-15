package dev.myct

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class VaultProtocolTest {
    private val prompt = "Senha do cofre login (SSH privado, sem eco): "
    private val begin = "\r\nMYCT-VAULT-BEGIN:test\r\n"
    private fun channel(reply: String, output: ByteArrayOutputStream = ByteArrayOutputStream(),
                        closed: () -> Unit = {}) =
        VaultUnlockChannel(ByteArrayInputStream(reply.toByteArray()), output, closed, "test")

    @Test fun secretOnlyAfterFreshPromptAndOnlyOnce() {
        val out = ByteArrayOutputStream()
        val transport = channel(begin + prompt + "\r\nMYCT-VAULT-DONE:test:0\r\n", out)
        val secret = "synthetic-only-ação".toByteArray()
        assertTrue(runCatching { transport.submitSecret(secret) }.isFailure)
        assertTrue(transport.awaitPasswordPrompt())
        val command = out.toString("UTF-8")
        assertFalse(command.contains("synthetic-only"))
        assertTrue(command.contains("\"\$HOME/.local/bin/myct-vault\" unlock"))
        assertTrue(command.endsWith("; exit\n"))
        transport.submitSecret(secret)
        assertEquals(command + "synthetic-only-ação\n", out.toString("UTF-8"))
        assertTrue(runCatching { transport.submitSecret(secret) }.isFailure)
        assertTrue(transport.awaitCompletion())
        transport.close()
    }
    @Test fun bannerPromptOrOldMarkerCannotAuthorizePassword() {
        listOf(prompt, "\nMYCT-VAULT-BEGIN:old\n$prompt", "x".repeat(8193)).forEach { reply ->
            val transport = channel(reply)
            assertTrue(runCatching { transport.awaitPasswordPrompt() }.isFailure)
            assertTrue(runCatching { transport.submitSecret("synthetic".toByteArray()) }.isFailure)
            transport.close()
        }
    }
    @Test fun earlyExitAlreadyUnlockedAndRefusalNeverAcceptSecret() {
        for (status in listOf(0, 1, 127)) {
            val transport = channel(begin + "\r\nMYCT-VAULT-DONE:test:$status\r\n")
            val result = runCatching { transport.awaitPasswordPrompt() }
            if (status == 0) assertEquals(false, result.getOrThrow()) else assertTrue(result.isFailure)
            assertTrue(runCatching { transport.submitSecret("synthetic".toByteArray()) }.isFailure)
            transport.close()
        }
    }
    @Test fun remoteHelperDenialExposesOnlySanitizedCodeAndDoesNotPermitPassword() {
        val out = ByteArrayOutputStream()
        val reply = begin + "\r\n" +
            """{"version": 1, "ok": false, "error_code": "authenticated_ssh_session_required", "category": "credentials"}""" +
            "\r\nMYCT-VAULT-DONE:test:1\r\n"
        val transport = channel(reply, out)
        val failure = runCatching { transport.awaitPasswordPrompt() }.exceptionOrNull()
        assertTrue(failure is VaultUnlockDenied)
        assertEquals("authenticated_ssh_session_required", (failure as VaultUnlockDenied).safeCode)
        val sent = out.toString("UTF-8")
        assertFalse(sent.contains("test-password"))
        assertTrue(runCatching { transport.submitSecret("test-password".toByteArray()) }.isFailure)
        assertEquals(sent, out.toString("UTF-8"))
        transport.close()
    }

    @Test fun controlBytesAndCanonicalOverflowNeverGoToWire() {
        val invalid = (0..31).map { byteArrayOf(65, it.toByte(), 66) } +
            listOf(byteArrayOf(127), ByteArray(0), ByteArray(4096) { 65 })
        for (secret in invalid) {
            val out = ByteArrayOutputStream()
            val transport = channel(begin + prompt, out)
            assertTrue(transport.awaitPasswordPrompt())
            val size = out.size()
            assertTrue(runCatching { transport.submitSecret(secret) }.isFailure)
            assertEquals(size, out.size())
            transport.close()
        }
    }
    @Test fun failureAndMissingCompletionDoNotReportSuccess() {
        for (reply in listOf("\r\nMYCT-VAULT-DONE:test:1\r\n", "\r\nMYCT-VAULT-DONE:old:0\r\n", "")) {
            val transport = channel(begin + prompt + reply)
            assertTrue(transport.awaitPasswordPrompt())
            transport.submitSecret("synthetic".toByteArray())
            assertFalse(runCatching { transport.awaitCompletion() }.getOrDefault(false))
            transport.close()
        }
    }
    @Test fun cancelledAttemptClosesLateChannelAndCloseIsIdempotent() {
        var closes = 0
        val late = channel(begin + prompt, closed = { closes++ })
        val attempt = VaultUnlockAttempt()
        attempt.close()
        assertTrue(runCatching { attempt.register(late) }.isFailure)
        assertEquals(1, closes)
        late.close(); attempt.close()
        assertEquals(1, closes)
        assertTrue(runCatching { late.submitSecret("synthetic".toByteArray()) }.isFailure)
    }
}
