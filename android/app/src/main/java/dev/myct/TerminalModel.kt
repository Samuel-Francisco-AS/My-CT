package dev.myct

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.*
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets

/** Process-scoped SSH owner shared by Activity and the opt-in foreground service. */
class TerminalModel(application: Application) {
    internal val localTerminal = LocalTerminalModel(java.io.File(application.filesDir, "ide-local"))
    internal val terminalSelection = TerminalSelection()
    internal val selectedTerminal get() = if (terminalSelection.environment == IdeEnvironment.LOCAL) localTerminal.terminal else terminal
    internal val selectedRunning get() = if (terminalSelection.environment == IdeEnvironment.LOCAL) localTerminal.running else phase == Phase.CONNECTED
    internal val selectedModifiers get() = if (terminalSelection.environment == IdeEnvironment.LOCAL) localTerminal.modifiers else modifiers
    internal val selectedCallbacks get() = if (terminalSelection.environment == IdeEnvironment.LOCAL) localTerminal.callbacks else callbacks
    internal fun terminalInput() = terminalSelection.bind(selectedTerminal, selectedModifiers, { selectedTerminal }, { selectedRunning })
    internal fun selectTerminal(value: IdeEnvironment) {
        modifiers.clear(); localTerminal.modifiers.clear()
        terminalSelection.select(value)
    }
    internal fun clearTerminalModifiers() { modifiers.clear(); localTerminal.modifiers.clear() }
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
    internal val modifiers = TerminalModifiers()
    val callbacks = SessionCallbacks { diagnostics }
    private val prefs = application.getSharedPreferences("ssh-pins", 0)
    var host by mutableStateOf(prefs.getString("last-host", "") ?: "")
    var port by mutableStateOf(prefs.getString("last-port", "22") ?: "22")
    var user by mutableStateOf(prefs.getString("last-user", "") ?: "")
    var protectScreenshots by mutableStateOf(prefs.getBoolean("protect-capture", true))
        private set
    var fontSizeSp by mutableIntStateOf(prefs.getInt("font-size-sp", 12).coerceIn(10, 20))
        private set
    var diagnostics = TerminalDiagnostics()
        private set
    val files = FilesModel(application)
    internal val ide = IdeModel(java.io.File(application.filesDir, "ide-local"),
        shared = SharedIdeModel(SharedWorkspace(AndroidSharedDocuments(application.contentResolver), AndroidSharedTreeMemory(application))),
        preferredStorage = runCatching { IdeStorage.valueOf(prefs.getString("ide-storage", "PRIVATE") ?: "PRIVATE") }.getOrDefault(IdeStorage.PRIVATE),
        rememberStorage = { value -> prefs.edit { putString("ide-storage", value.name) } },
        preferred = runCatching { IdeEnvironment.valueOf(prefs.getString("ide-environment", "LOCAL") ?: "LOCAL") }.getOrDefault(IdeEnvironment.LOCAL)) { mode ->
        prefs.edit { putString("ide-environment", mode.name) }
    }
    val health = HealthModel()
    val vault = VaultModel()
    internal val antivirusSignatures = AntivirusSignaturesModel()
    val downloads = DownloadsModel()
    internal val torrents = TorrentsModel()
    internal val media = MediaModel()
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
    private var pending: TerminalActionQueue? = null
    private var transport: TerminalIo? = null
    fun pinForHost(): String? = prefs.getString("pin:${host.trim()}:${port.toIntOrNull()}", null)
    fun forgetPin() {
        prefs.edit { remove("pin:${host.trim()}:${port.toIntOrNull()}") }
        message = "Pareamento removido. Confira novamente o fingerprint diretamente no Fedora."
    }

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
        diagnostics = TerminalDiagnostics()
        val metrics = diagnostics // A retiring connection cannot contaminate the next sample.
        val id = ++generation
        val current = SshConnection()
        connection = current
        val queue = TerminalActionQueue(metrics)
        pending = queue
        val pins = PinnedHostKeys(PinnedHostKeys.hostId(hostname, sshPort), expected)
        val buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password))
        val secret = ByteArray(buffer.remaining()).also { buffer.get(it) }
        if (buffer.hasArray()) buffer.array().fill(0)
        password.fill('\u0000')
        val screen = TerminalSession(object : TerminalSession.Transport {
            override fun send(data: ByteArray) {
                if (id == generation && phase == Phase.CONNECTED && !queue.write(data)) {
                    disconnect("Entrada excedeu a fila; sessão encerrada para evitar travamento.")
                }
            }
            override fun resize(columns: Int, rows: Int, widthPixels: Int, heightPixels: Int) {
                if (id == generation && !queue.resize(columns, rows, widthPixels, heightPixels)) {
                    disconnect("Não foi possível redimensionar; reconecte.")
                }
            }
        }, callbacks)
        terminal = screen
        updatePhase(Phase.CONNECTING)
        message = "Verificando host e autenticando…"
        job = scope.launch {
            var workers: TerminalIo? = null
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
                ide.attach(current, "$hostname:$sshPort/$username/$expected")
                health.attach(current)
                vault.attach(current)
                downloads.attach(current); torrents.attach(current); media.attach(current); antivirusSignatures.attach(current)
                message = "SSH conectado · host verificado · PTY real"
                // Apply actual view dimensions even if they were measured before channel.connect().
                screen.requestPtyResize()
                val completed = CompletableDeferred<Unit>()
                val handler = Handler(Looper.getMainLooper())
                val incoming = TerminalOutputQueue(metrics,
                    post = { task -> check(handler.post(task)) },
                    append = { bytes, count -> if (id == generation) screen.append(bytes, count) },
                    finished = { completed.complete(Unit) },
                    failed = { error -> completed.completeExceptionally(error) })
                workers = TerminalIo(queue, incoming,
                    read = { bytes -> current.input.read(bytes) },
                    write = { bytes, count -> current.output.write(bytes, 0, count); current.output.flush() },
                    resize = { cols, rows, width, height -> current.resize(cols, rows, width, height) },
                    failed = { error -> completed.completeExceptionally(error) })
                transport = workers
                workers.start()
                completed.await() // Suspends main; reader never waits for main per read.
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
                workers?.close(); queue.close()
                // Close the socket before joining blocking workers, always off main.
                withContext(NonCancellable + Dispatchers.IO) { current.close(); workers?.awaitStopped() }
                if (id == generation) { transport = null; screen.finish(); connection = null; pending = null; modifiers.clear(); files.attach(null); ide.attach(null); health.attach(null); vault.attach(null); downloads.attach(null); torrents.attach(null); media.attach(null); antivirusSignatures.attach(null) }
            }
        }
    }

    fun disconnect(reason: String = "Sessão encerrada. Você pode reconectar.") {
        files.attach(null); ide.attach(null)
        health.attach(null)
        vault.attach(null); downloads.attach(null); torrents.attach(null); media.attach(null); antivirusSignatures.attach(null)
        generation++
        val old = connection
        connection = null
        transport?.close(); transport = null
        pending?.close(); pending = null
        job?.cancel(); job = null
        terminal?.finish()
        modifiers.clear()
        updatePhase(Phase.ENDED)
        message = reason
        if (old != null) scope.launch(Dispatchers.IO) { old.close() }
    }
    private fun updatePhase(value: Phase) {
        if (value != Phase.CONNECTED) modifiers.clear()
        if (value != Phase.CONNECTED) { health.attach(null); vault.attach(null); downloads.attach(null); torrents.attach(null); media.attach(null); antivirusSignatures.attach(null) }
        phase = value
        onConnectionStateChanged?.invoke()
    }
}
