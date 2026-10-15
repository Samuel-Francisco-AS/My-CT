package dev.myct

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

internal class VaultInputRejected : IllegalArgumentException("Unsupported password input")

/** Remote helper already emitted this code before requesting any password. */
internal class VaultUnlockDenied(val safeCode: String) : IllegalStateException("Vault unlock refused")

/** A separate, short-lived interactive PTY. Password bytes are never shell arguments. */
internal class VaultUnlockChannel(
    private val input: InputStream,
    private val output: OutputStream,
    private val disconnect: () -> Unit,
    private val token: String = java.util.UUID.randomUUID().toString()
) : AutoCloseable {
    constructor(shell: ChannelShell, onClosed: () -> Unit) : this(
        shell.inputStream, shell.outputStream,
        { try { shell.disconnect() } finally { onClosed() } })

    private var started = false
    private var passwordReady = false
    private var finished = false
    private val closed = java.util.concurrent.atomic.AtomicBoolean()
    private val begin = "MYCT-VAULT-BEGIN:$token"
    private val exit = Regex("(?:^|\\n)MYCT-VAULT-DONE:$token:([0-9]+)\\r?\\n$")

    /** The helper checks human SSH/TTY and disables echo before this prompt. */
    fun awaitPasswordPrompt(): Boolean {
        check(!started && !closed.get())
        started = true
        // Ignore banners/command echo until a fresh, exact marker line. Always
        // exit this dedicated shell when the helper ends: never interpret a late
        // password as a new shell command after helper rejection or termination.
        val command = "printf '\\n$begin\\n'; \"\$HOME/.local/bin/myct-vault\" unlock; " +
            "printf '\\nMYCT-VAULT-DONE:$token:%d\\n' \"\$?\"; exit\n"
        output.write(command.toByteArray(StandardCharsets.US_ASCII))
        output.flush()
        val text = readUntilSignal(allowPrompt = true, awaitBegin = true)
        return when {
            text.endsWith(PROMPT) -> true.also { passwordReady = true }
            exitCode(text) == 0 -> false // already unlocked
            else -> throw VaultUnlockDenied(
                SAFE_HELPER_CODES.find(text)?.groupValues?.get(1) ?: "unlock_helper_refused"
            )
        }
    }

    /** Canonical Linux PTY: refuse control bytes and lines that could be truncated. */
    fun submitSecret(bytes: ByteArray) {
        check(passwordReady && !finished && !closed.get())
        if (bytes.isEmpty() || bytes.size > 4095 || bytes.any {
            (it.toInt() and 0xff) < 32 || it == 127.toByte()
        }) throw VaultInputRejected()
        passwordReady = false
        finished = true // No retry after even a partial write.
        output.write(bytes)
        output.write(10)
        output.flush()
    }

    /** Only exit status leaves this object; terminal output is never shown/logged. */
    fun awaitCompletion(): Boolean {
        check(finished && !closed.get())
        return exitCode(readUntilSignal(allowPrompt = false)) == 0
    }

    private fun readUntilSignal(allowPrompt: Boolean, awaitBegin: Boolean = false): String {
        val chars = StringBuilder()
        var begun = !awaitBegin
        repeat(8192) {
            check(!closed.get())
            val next = input.read()
            if (next < 0) error("SSH unlock terminal closed")
            chars.append(next.toChar())
            if (!begun) {
                if (chars.endsWith("\n$begin\r\n") || chars.endsWith("\n$begin\n")) {
                    begun = true
                    chars.setLength(0)
                }
            } else if ((allowPrompt && chars.endsWith(PROMPT)) || exit.containsMatchIn(chars)) {
                return chars.toString()
            }
        }
        error("SSH unlock response exceeded limit")
    }

    private fun exitCode(reply: String): Int? = exit.find(reply)?.groupValues?.get(1)?.toIntOrNull()

    override fun close() {
        if (closed.compareAndSet(false, true)) runCatching { disconnect() }
    }

    companion object {
        private const val PROMPT = "Senha do cofre login (SSH privado, sem eco): "
        // The helper prints sanitized JSON codes; never expose PTY stdout/stderr.
        private val SAFE_HELPER_CODES = Regex("\"error_code\"\\s*:\\s*\"([a-z][a-z0-9_]{2,63})\"")
    }
}

/** A cancelled attempt rejects and closes a transport that finishes opening late. */
internal class VaultUnlockAttempt : AutoCloseable {
    private var cancelled = false
    private var channel: VaultUnlockChannel? = null
    @Synchronized fun register(value: VaultUnlockChannel) {
        if (cancelled) { value.close(); throw kotlinx.coroutines.CancellationException() }
        check(channel == null)
        channel = value
    }
    @Synchronized fun channel(): VaultUnlockChannel? = channel
    @Synchronized override fun close() {
        cancelled = true
        channel?.close()
        channel = null
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
    return VaultStatus.parse(String(bytes.toByteArray(), StandardCharsets.UTF_8))
}
