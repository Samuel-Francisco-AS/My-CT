package dev.myct

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets

enum class VaultPhase {
    NO_SSH, IDLE, CHECKING, LOCKED, UNLOCKED, UNAVAILABLE, PREPARING, PASSWORD, UNLOCKING, ERROR
}

data class VaultViewState(val phase: VaultPhase = VaultPhase.NO_SSH, val compatible: Boolean = false) {
    val message: String get() = when (phase) {
        VaultPhase.NO_SSH -> "Conecte ao Fedora pela aba Terminal para consultar o cofre."
        VaultPhase.IDLE -> "SSH conectado. Consulte o estado do cofre."
        VaultPhase.CHECKING -> "Consultando GNOME Keyring…"
        VaultPhase.LOCKED -> if (compatible) "Cofre login bloqueado." else
            "Cofre bloqueado, mas o mecanismo de desbloqueio não é compatível. Use o terminal para diagnóstico."
        VaultPhase.UNLOCKED -> "Cofre login desbloqueado."
        VaultPhase.UNAVAILABLE -> "GNOME Keyring indisponível nesta sessão. Não é necessário criar outro cofre."
        VaultPhase.PREPARING -> "Abrindo terminal SSH privado para o desbloqueio…"
        VaultPhase.PASSWORD -> "Digite a senha do cofre login."
        VaultPhase.UNLOCKING -> "Desbloqueando cofre…"
        VaultPhase.ERROR -> "Não foi possível consultar ou desbloquear. Confira myct-vault no Fedora."
    }
    val busy get() = phase in setOf(VaultPhase.CHECKING, VaultPhase.PREPARING, VaultPhase.UNLOCKING)
}

/** Process-scoped state. Never persists password and never gives the Core access to it. */
class VaultModel internal constructor(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val query: (SshConnection) -> VaultStatus = { it.vaultStatus() },
    private val opener: (SshConnection) -> VaultUnlockChannel = { it.openVaultUnlock() }
) {
    private var connection: SshConnection? = null
    private var generation = 0
    private var job: Job? = null
    @Volatile private var unlockChannel: VaultUnlockChannel? = null
    var state by mutableStateOf(VaultViewState())
        private set

    fun attach(value: SshConnection?) {
        generation++
        job?.cancel(); job = null
        unlockChannel?.close(); unlockChannel = null
        connection = value
        state = VaultViewState(if (value == null) VaultPhase.NO_SSH else VaultPhase.IDLE)
    }

    fun refresh() {
        val current = connection ?: return
        if (state.busy || state.phase == VaultPhase.PASSWORD) return
        val id = generation
        state = VaultViewState(VaultPhase.CHECKING)
        job = scope.launch {
            try {
                val status = withContext(Dispatchers.IO) { query(current) }
                if (id == generation) showStatus(status)
            } catch (_: CancellationException) {
            } catch (_: Exception) {
                if (id == generation) state = VaultViewState(VaultPhase.ERROR)
            }
        }
    }

    private fun showStatus(status: VaultStatus) {
        state = when (status.kind) {
            VaultKind.UNLOCKED -> VaultViewState(VaultPhase.UNLOCKED)
            VaultKind.LOCKED -> VaultViewState(VaultPhase.LOCKED, status.compatible)
            VaultKind.UNAVAILABLE -> VaultViewState(VaultPhase.UNAVAILABLE)
        }
    }

    fun beginUnlock() {
        val current = connection ?: return
        if (state.phase != VaultPhase.LOCKED || !state.compatible) return
        val id = generation
        state = VaultViewState(VaultPhase.PREPARING)
        job = scope.launch {
            var channel: VaultUnlockChannel? = null
            try {
                val asksPassword = withContext(Dispatchers.IO) {
                    val opened = opener(current)
                    channel = opened
                    unlockChannel = opened
                    opened.awaitPasswordPrompt()
                }
                if (id != generation) { channel?.close(); return@launch }
                if (asksPassword) {
                    state = VaultViewState(VaultPhase.PASSWORD)
                } else {
                    channel?.close()
                    unlockChannel = null
                    state = VaultViewState(VaultPhase.IDLE)
                    refresh()
                }
            } catch (_: CancellationException) {
                channel?.close()
            } catch (_: Exception) {
                channel?.close()
                if (id == generation) state = VaultViewState(VaultPhase.ERROR)
            }
        }
    }

    fun submitPassword(secret: CharArray) {
        val current = connection
        val channel = unlockChannel
        if (current == null || channel == null || state.phase != VaultPhase.PASSWORD) {
            secret.fill('\u0000')
            return
        }
        val id = generation
        state = VaultViewState(VaultPhase.UNLOCKING)
        job = scope.launch {
            try {
                val succeeded = withContext(Dispatchers.IO) {
                    val chars = StandardCharsets.UTF_8.encode(CharBuffer.wrap(secret))
                    val bytes = ByteArray(chars.remaining()).also { chars.get(it) }
                    if (chars.hasArray()) chars.array().fill(0)
                    secret.fill('\u0000')
                    try {
                        channel.submitSecret(bytes)
                        channel.awaitCompletion()
                    } finally {
                        bytes.fill(0)
                        channel.close()
                    }
                }
                if (id != generation) return@launch
                unlockChannel = null
                val status = withContext(Dispatchers.IO) { query(current) }
                if (id == generation) {
                    showStatus(status)
                    if (!succeeded && status.kind != VaultKind.UNLOCKED) state = VaultViewState(VaultPhase.ERROR)
                }
            } catch (_: CancellationException) {
                channel.close()
            } catch (_: Exception) {
                channel.close()
                if (id == generation) state = VaultViewState(VaultPhase.ERROR)
            } finally {
                secret.fill('\u0000')
                if (unlockChannel === channel) unlockChannel = null
            }
        }
    }

    fun cancelUnlock() {
        if (state.phase !in setOf(VaultPhase.PREPARING, VaultPhase.PASSWORD, VaultPhase.UNLOCKING)) return
        generation++
        job?.cancel(); job = null
        unlockChannel?.close(); unlockChannel = null
        state = VaultViewState(if (connection == null) VaultPhase.NO_SSH else VaultPhase.IDLE)
    }
}
