package dev.myct

import com.jcraft.jsch.JSch
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/** Optional real OpenSSH negotiation test; no login, port, account or service is created. */
class FedoraHandshakeTest {
    @Test fun realHostKeyMismatchAndTrustedNegotiationBeforeAuthentication() {
        assumeTrue(System.getenv("MYCT_TEST_LOCAL_SSH") == "1")
        val file = File("/etc/ssh/ssh_host_ed25519_key.pub")
        assumeTrue(file.canRead())
        val key = Base64.getDecoder().decode(file.readText().trim().split(Regex("\\s+"))[1])
        fun attempt(repo: PinnedHostKeys) {
            val client = JSch()
            client.hostKeyRepository = repo
            val session = client.getSession(System.getProperty("user.name"), "localhost", 22)
            session.setConfig("StrictHostKeyChecking", "yes")
            session.setConfig("server_host_key", "ssh-ed25519")
            session.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
            session.setConfig("xdh", "com.jcraft.jsch.bc.XDH")
            session.setConfig("PreferredAuthentications", "none")
            try {
                session.connect(5000)
                fail("No credentials were provided; a shell must not be opened")
            } catch (_: com.jcraft.jsch.JSchException) {
                // Assertions below distinguish key rejection from successful authenticated KEX.
            } finally { session.disconnect() }
        }
        val wrong = PinnedHostKeys("localhost", PinnedHostKeys.fingerprint("wrong-key".toByteArray()))
        attempt(wrong)
        assertTrue("Changed key must be rejected", wrong.rejected)
        assertFalse(wrong.verified)
        val trusted = PinnedHostKeys("localhost", PinnedHostKeys.fingerprint(key))
        attempt(trusted)
        assertTrue("Authoritative local host key must pass real SSH negotiation", trusted.verified)
        assertFalse(trusted.rejected)
    }
}
