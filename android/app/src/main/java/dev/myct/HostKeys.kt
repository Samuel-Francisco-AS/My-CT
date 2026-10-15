package dev.myct

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import java.security.MessageDigest
import java.util.Base64

/** Pins the complete public host-key blob, not an algorithm name or an unverified scan. */
class PinnedHostKeys(private val host: String, private val expected: String) : HostKeyRepository {
    @Volatile var verified = false
        private set
    @Volatile var rejected = false
        private set

    override fun check(host: String, key: ByteArray): Int {
        val match = host == this.host && matches(expected, key)
        verified = match
        rejected = !match
        return if (match) HostKeyRepository.OK else HostKeyRepository.CHANGED
    }
    override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
    override fun remove(host: String?, type: String?) = Unit
    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
    override fun getKnownHostsRepositoryID() = "My-CT SHA256 pins"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()

    companion object {
        fun validFingerprint(value: String) = value.matches(Regex("SHA256:[A-Za-z0-9+/]{43}"))
        fun fingerprint(key: ByteArray): String = "SHA256:" + Base64.getEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
        fun matches(expected: String, key: ByteArray): Boolean {
            if (!validFingerprint(expected)) return false
            val digest = Base64.getDecoder().decode(expected.removePrefix("SHA256:"))
            return MessageDigest.isEqual(digest, MessageDigest.getInstance("SHA-256").digest(key))
        }
        fun hostId(host: String, port: Int) = if (port == 22) host else "[$host]:$port"
    }
}
