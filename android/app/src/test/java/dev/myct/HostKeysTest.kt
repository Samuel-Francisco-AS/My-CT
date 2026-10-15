package dev.myct

import com.jcraft.jsch.HostKeyRepository
import org.junit.Assert.*
import org.junit.Test

class HostKeysTest {
    @Test fun acceptsOnlyPinnedHostAndKey() {
        val key = "synthetic test host key blob".toByteArray()
        val pin = PinnedHostKeys.fingerprint(key)
        val repo = PinnedHostKeys("[test.example]:2222", pin)
        assertEquals(HostKeyRepository.OK, repo.check("[test.example]:2222", key))
        assertTrue(repo.verified)
        assertEquals(HostKeyRepository.CHANGED, repo.check("[test.example]:2222", key + 0))
        assertTrue(repo.rejected)
        assertEquals(HostKeyRepository.CHANGED, repo.check("other.example", key))
    }
    @Test fun rejectsMissingOrMalformedPin() {
        assertFalse(PinnedHostKeys.validFingerprint(""))
        assertFalse(PinnedHostKeys.matches("SHA256:short", byteArrayOf(1)))
        assertEquals("test.example", PinnedHostKeys.hostId("test.example", 22))
        assertEquals("[::1]:2222", PinnedHostKeys.hostId("::1", 2222))
    }
}
