package dev.myct

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelShell
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/** Only public metadata of the existing GNOME login keyring, never secret items. */
enum class VaultKind { LOCKED, UNLOCKED, UNAVAILABLE }

data class VaultStatus(val kind: VaultKind, val compatible: Boolean = false) {
    companion object {
        fun parse(body: String): VaultStatus {
            val data = JSONObject(body.trim())
            return when (data.getString("state")) {
                "unlocked" -> {
                    require(data.getBoolean("service_available") && data.getBoolean("login_unlocked"))
                    VaultStatus(VaultKind.UNLOCKED)
                }
                "locked" -> {
                    require(data.getBoolean("service_available") && !data.getBoolean("login_unlocked"))
                    VaultStatus(VaultKind.LOCKED, data.optBoolean("unlock_backend_compatible", false))
                }
                "unavailable" -> VaultStatus(VaultKind.UNAVAILABLE)
                else -> error("Invalid vault state")
            }
        }
    }
}

/** A separate, short-lived interactive PTY. Password bytes are never shell arguments. */
internal class VaultUnlockChannel(
    private val shell: ChannelShell,
    private val onClosed: () -> Unit
) : AutoCloseable {
    private val input: InputStream = shell.inputStream
    private val output: OutputStream = shell.outputStream
    private var started = false
    private var finished = false

    /**
     * The existing myct-vault deliberately accepts only a DIRECT interactive SSH
     * shell. Launch it in an independent ChannelShell, not ChannelExec/-c.
     * The helper disables terminal echo BEFORE emitting this exact prompt.
     */
    fun awaitPasswordPrompt(): Boolean {
        check(!started)
        started = true
        val command = "\"\$HOME/.local/bin/myct-vault\" unlock; printf '\\nMYCT-VAULT-DONE:%d\\n' \"\$?\"\n"
        output.write(command.toByteArray(StandardCharsets.US_ASCII))
        output.flush()
        val text = readUntilAny(
            listOf(PROMPT, "MYCT-VAULT-DONE:0", "MYCT-VAULT-DONE:1"),
            8192
        )
        return when {
            PROMPT in text -> true
            "MYCT-VAULT-DONE:0" in text -> false // already unlocked
            else -> error("Unlock terminal refused or helper unavailable")
        }
    }

    /** Caller passes a mutable buffer and clears it immediately afterwards. */
    fun submitSecret(bytes: ByteArray) {
        check(started && !finished)
        require(bytes.isNotEmpty() && bytes.size <= 8192 && bytes.none { it == 0.toByte() || it == 10.toByte() || it == 13.toByte() })
        output.write(bytes)
        output.write(10)
        output.flush()
        finished = true
    }

    /** Only exit status leaves this object; all terminal output is discarded. */
    fun awaitCompletion(): Boolean {
        check(finished)
        val text = readUntilAny(listOf("MYCT-VAULT-DONE:0", "MYCT-VAULT-DONE:1"), 8192)
        return "MYCT-VAULT-DONE:0" in text
    }

    private fun readUntilAny(markers: List<String>, maximum: Int): String {
        val chars = StringBuilder()
        repeat(maximum) {
            val next = input.read()
            if (next < 0) error("SSH unlock terminal closed")
            // All protocol markers are ASCII, irrespective of locale/output text.
            chars.append(next.toChar())
            if (markers.any { chars.endsWith(it) }) return chars.toString()
        }
        error("SSH unlock prompt exceeded limit")
    }

    override fun close() {
        runCatching { shell.disconnect() }
        onClosed()
    }

    companion object {
        private const val PROMPT = "Senha do cofre login (SSH privado, sem eco): "
    }
}

/** Read a finite metadata response; never decode secret values. */
internal fun readVaultStatus(input: InputStream, maximum: Int = 4096): VaultStatus {
    val bytes = ByteArrayOutputStream()
    val buffer = ByteArray(256)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        require(bytes.size() + count <= maximum) { "Vault metadata too large" }
        bytes.write(buffer, 0, count)
    }
    return VaultStatus.parse(bytes.toString(StandardCharsets.UTF_8))
}
