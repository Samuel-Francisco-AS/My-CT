package dev.myct

import android.app.Application
import androidx.core.content.edit
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets

/** Process-scoped SSH owner shared by Activity and the opt-in foreground service. */
class TerminalModel(application: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var onConnectionStateChanged: (() -> Unit)? = null
    var backgroundEnabled by mutableStateOf(false)
        internal set
    enum class Phase { IDLE, CONNECTING, CONNECTED, ENDED, ERROR }
    var phase by mutableStateOf(Phase.IDLE)
        private set
    var message by mutableStateOf("Informe a conexão e confira a identidade do servidor.")
        private set
    var terminal by mutableStateOf<TerminalSession?>(null)
        private set
    var ctrl by mutableStateOf(false)
        private set
    val callbacks = SessionCallbacks()
    private val prefs = application.getSharedPreferences("ssh-pins", 0)
    var host by mutableStateOf(prefs.getString("last-host", "") ?: "")
    var port by mutableStateOf(prefs.getString("last-port", "22") ?: "22")
    var user by mutableStateOf(prefs.getString("last-user", "") ?: "")
    var protectScreenshots by mutableStateOf(prefs.getBoolean("protect-capture", true))
        private set
    var fontSizeSp by mutableIntStateOf(prefs.getInt("font-size-sp", 12).coerceIn(10, 20))
        private set
    val diagnostics = TerminalDiagnostics()
    val files = FilesModel(application)
    val health = HealthModel()
    val vault = VaultModel()
    internal val antivirusSignatures = AntivirusSignaturesModel()
    val downloads = DownloadsModel()
    internal val torrents = TorrentsModel()
    var enforceCharBasedInput by mutableStateOf(prefs.getBoolean("char-based-input", true))
        private set
    fun setCharBasedInput(enabled: Boolean) {
        enforceCharBasedInput = enabled
        prefs.edit { putBoolean("char-based-input", enabled) }
    }
    fun setCaptureProtection(enabled: Boolean) {
        protectScreenshots = enabled
        prefs.edit { putBoolean("protect-capture", enabled) }
    }
    fun changeFont(delta: Int) {
        fontSizeSp = (fontSizeSp + delta).coerceIn(10, 20)
        prefs.edit { putInt("font-size-sp", fontSizeSp) }
    }
    private var generation = 0
    private var connection: SshConnection? = null
    private var job: Job? = null
    private var pending: Channel<Action>? = null
    private sealed interface Action {
        data class Write(val bytes: ByteArray, val queuedAt: Long = System.nanoTime()) : Action
        data class Resize(val cols: Int, val rows: Int, val width: Int, val height: Int) : Action
    }
    fun pinForHost(): String? = prefs.getString("pin:${host.trim()}:${port.toIntOrNull()}", null)
    fun forgetPin() {
        prefs.edit { remove("pin:${host.trim()}:${port.toIntOrNull()}") }
        message = "Pareamento removido. Confira novamente o fingerprint diretamente no Fedora."
    }
    fun toggleCtrl() { ctrl = !ctrl }
    fun consumeCtrl(): Boolean = ctrl.also { ctrl = false }
    fun sendKey(keyCode: Int) {
        val session = terminal ?: return
        val emulator = session.emulator
        val sequence = KeyHandler.getCode(keyCode, 0, emulator.isCursorKeysApplicationMode, emulator.isKeypadApplicationMode)
        if (sequence != null) session.write(sequence)
    }
    fun sendCtrlC() { ctrl = false; terminal?.write("\u0003") }

    fun connect(password: CharArray, newPin: String) {
        // Reattaching UI or rapid duplicate taps must never create another connection.
        if (phase == Phase.CONNECTED || phase == Phase.CONNECTING) { password.fill('\u0000'); return }
        val hostname = host.trim()
        val username = user.trim()
        val sshPort = port.toIntOrNull()
        val expected = pinForHost() ?: newPin.trim()
        if (hostname.isBlank() || hostname.any { it.isWhitespace() || it == '/' } ||
            username.isBlank() || sshPort == null || sshPort !in 1..65535 ||
            !PinnedHostKeys.validFingerprint(expected) || password.isEmpty()) {
            password.fill('\u0000')
            updatePhase(Phase.ERROR)
            message = "Confira endereço, porta (1–65535), usuário, senha e fingerprint SHA256."
            return
        }
        disconnect("Iniciando nova conexão.")
        val id = ++generation
        val current = SshConnection()
        connection = current
        val queue = Channel<Action>(64)
        pending = queue
        val pins = PinnedHostKeys(PinnedHostKeys.hostId(hostname, sshPort), expected)
        val buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password))
        val secret = ByteArray(buffer.remaining()).also { buffer.get(it) }
        if (buffer.hasArray()) buffer.array().fill(0)
        password.fill('\u0000')
        val screen = TerminalSession(object : TerminalSession.Transport {
            override fun send(data: ByteArray) {
                if (id == generation && phase == Phase.CONNECTED && queue.trySend(Action.Write(data)).isFailure) {
                    disconnect("Entrada excedeu a fila; sessão encerrada para evitar travamento.")
                }
            }
            override fun resize(columns: Int, rows: Int, widthPixels: Int, heightPixels: Int) {
                if (id == generation && queue.trySend(Action.Resize(columns, rows, widthPixels, heightPixels)).isFailure) {
                    disconnect("Não foi possível redimensionar; reconecte.")
                }
            }
        }, callbacks)
        terminal = screen
        updatePhase(Phase.CONNECTING)
        message = "Verificando host e autenticando…"
        job = scope.launch {
            try {
                withContext(Dispatchers.IO) { current.connect(hostname, sshPort, username, secret, pins, 80, 24) }
                if (id != generation) return@launch
                prefs.edit {
                    putString("pin:$hostname:$sshPort", expected)
                    putString("last-host", hostname); putString("last-port", "$sshPort")
                    putString("last-user", username)
                }
                updatePhase(Phase.CONNECTED)
                files.attach(current)
                health.attach(current)
                vault.attach(current)
                downloads.attach(current); torrents.attach(current); antivirusSignatures.attach(current)
                message = "SSH conectado · host verificado · PTY real"
                // Apply actual view dimensions even if they were measured before channel.connect().
                screen.requestPtyResize()
                coroutineScope {
                    val writer = launch(Dispatchers.IO) {
                        try {
                            for (action in queue) when (action) {
                                is Action.Write -> { diagnostics.wrote(action.queuedAt); current.output.write(action.bytes); current.output.flush() }
                                is Action.Resize -> current.resize(action.cols, action.rows, action.width, action.height)
                            }
                        } catch (error: Exception) {
                            // Unblock a concurrent blocking read immediately if writing fails.
                            current.close()
                            throw error
                        }
                    }
                    try {
                        withContext(Dispatchers.IO) {
                            val bytes = ByteArray(8192)
                            while (isActive) {
                                val count = current.input.read(bytes)
                                if (count < 0) break
                                if (count > 0) {
                                    val chunk = bytes.copyOf(count)
                                    val queuedAt = System.nanoTime()
                                    withContext(Dispatchers.Main) {
                                        diagnostics.delivered(queuedAt)
                                        if (id == generation) screen.append(chunk)
                                    }
                                }
                            }
                        }
                    } finally { queue.close(); writer.cancel() }
                }
                if (id == generation) { updatePhase(Phase.ENDED); message = "Sessão encerrada pelo servidor. Reconecte quando desejar." }
            } catch (_: CancellationException) {
                // Explicit disconnect already supplies the UI state; stale attempts cannot overwrite it.
            } catch (_: Exception) {
                if (id == generation) {
                    updatePhase(Phase.ERROR)
                    message = if (pins.rejected) "Chave do host divergente. Conexão recusada antes da autenticação."
                    else if (!pins.verified) "Falha de conexão ou negociação SSH. Confira endereço, rede e fingerprint."
                    else "Falha de autenticação ou conexão interrompida. Confira a senha e reconecte."
                }
            } finally {
                secret.fill(0)
                withContext(NonCancellable + Dispatchers.IO) { current.close() }
                if (id == generation) { screen.finish(); connection = null; pending = null; ctrl = false; files.attach(null); health.attach(null); vault.attach(null); downloads.attach(null); torrents.attach(null); antivirusSignatures.attach(null) }
            }
        }
    }

    fun disconnect(reason: String = "Sessão encerrada. Você pode reconectar.") {
        files.attach(null)
        health.attach(null)
        vault.attach(null); downloads.attach(null); torrents.attach(null); antivirusSignatures.attach(null)
        generation++
        val old = connection
        connection = null
        pending?.close(); pending = null
        job?.cancel(); job = null
        terminal?.finish()
        ctrl = false
        updatePhase(Phase.ENDED)
        message = reason
        if (old != null) scope.launch(Dispatchers.IO) { old.close() }
    }
    private fun updatePhase(value: Phase) {
        if (value != Phase.CONNECTED) { health.attach(null); vault.attach(null); downloads.attach(null); torrents.attach(null); antivirusSignatures.attach(null) }
        phase = value
        onConnectionStateChanged?.invoke()
    }
}
