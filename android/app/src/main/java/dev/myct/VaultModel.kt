package dev.myct

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets

enum class VaultPhase {
    NO_SSH, IDLE, CHECKING, LOCKED, UNLOCKED, UNAVAILABLE, PREPARING, PASSWORD, UNLOCKING, INPUT_REJECTED, ERROR
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
        VaultPhase.INPUT_REJECTED -> "Senha não enviada: caracteres de controle ou mais de 4095 bytes não são aceitos neste terminal. Consulte o estado antes de tentar novamente."
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
    private var unlockAttempt: VaultUnlockAttempt? = null
    var state by mutableStateOf(VaultViewState())
        private set

    fun attach(value: SshConnection?) {
        generation++
        job?.cancel(); job = null
        unlockAttempt?.close(); unlockAttempt = null
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
                val status = withTimeout(15_000) { runInterruptible(Dispatchers.IO) { query(current) } }
                if (id == generation) showStatus(status)
            } catch (_: TimeoutCancellationException) {
                if (id == generation) state = VaultViewState(VaultPhase.ERROR)
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
        val attempt = VaultUnlockAttempt()
        unlockAttempt = attempt
        job = scope.launch {
            try {
                val asksPassword = withTimeout(15_000) {
                    runInterruptible(Dispatchers.IO) {
                        val opened = opener(current)
                        attempt.register(opened)
                        opened.awaitPasswordPrompt()
                    }
                }
                if (id != generation) { attempt.close(); return@launch }
                if (asksPassword) {
                    state = VaultViewState(VaultPhase.PASSWORD)
                } else {
                    attempt.close()
                    unlockAttempt = null
                    state = VaultViewState(VaultPhase.IDLE)
                    refresh()
                }
            } catch (_: TimeoutCancellationException) {
                attempt.close()
                if (id == generation) state = VaultViewState(VaultPhase.ERROR)
            } catch (_: CancellationException) {
                attempt.close()
            } catch (_: Exception) {
                attempt.close()
                if (id == generation) state = VaultViewState(VaultPhase.ERROR)
            }
        }
    }

    fun submitPassword(secret: CharArray) {
        val current = connection
        val attempt = unlockAttempt
        val channel = attempt?.channel()
        if (current == null || channel == null || state.phase != VaultPhase.PASSWORD) {
            secret.fill('\u0000')
            return
        }
        // Encode and wipe the caller's buffer before launching: even a scope
        // cancelled before the coroutine starts must not retain that CharArray.
        val bytes = try {
            val chars = StandardCharsets.UTF_8.encode(CharBuffer.wrap(secret))
            ByteArray(chars.remaining()).also { chars.get(it); if (chars.hasArray()) chars.array().fill(0) }
        } finally { secret.fill('\u0000') }
        val id = generation
        state = VaultViewState(VaultPhase.UNLOCKING)
        job = scope.launch {
            try {
                val succeeded = withTimeout(15_000) {
                    runInterruptible(Dispatchers.IO) {
                        try {
                            channel.submitSecret(bytes)
                            channel.awaitCompletion()
                        } finally { bytes.fill(0); attempt.close() }
                    }
                }
                if (id != generation) return@launch
                unlockAttempt = null
                val status = withTimeout(15_000) { runInterruptible(Dispatchers.IO) { query(current) } }
                if (id == generation) {
                    showStatus(status)
                    if (!succeeded && status.kind != VaultKind.UNLOCKED) state = VaultViewState(VaultPhase.ERROR)
                }
            } catch (_: VaultInputRejected) {
                if (id == generation) state = VaultViewState(VaultPhase.INPUT_REJECTED)
            } catch (_: TimeoutCancellationException) {
                if (id == generation) state = VaultViewState(VaultPhase.ERROR)
            } catch (_: CancellationException) {
            } catch (_: Exception) {
                if (id == generation) state = VaultViewState(VaultPhase.ERROR)
            } finally {
                bytes.fill(0)
                attempt.close()
                if (unlockAttempt === attempt) unlockAttempt = null
            }
        }.also { task -> task.invokeOnCompletion { bytes.fill(0); attempt.close() } }
    }

    fun cancelUnlock() {
        if (state.phase !in setOf(VaultPhase.PREPARING, VaultPhase.PASSWORD, VaultPhase.UNLOCKING)) return
        generation++
        job?.cancel(); job = null
        unlockAttempt?.close(); unlockAttempt = null
        state = VaultViewState(if (connection == null) VaultPhase.NO_SSH else VaultPhase.IDLE)
    }
}
