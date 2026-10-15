package dev.myct

import android.app.Application
import androidx.core.content.edit
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets

class TerminalModel(application: Application) : AndroidViewModel(application) {
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
    private var generation = 0
    private var connection: SshConnection? = null
    private var job: Job? = null
    private var pending: Channel<Action>? = null
    private sealed interface Action {
        data class Write(val bytes: ByteArray) : Action
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
        val hostname = host.trim()
        val username = user.trim()
        val sshPort = port.toIntOrNull()
        val expected = pinForHost() ?: newPin.trim()
        if (hostname.isBlank() || hostname.any { it.isWhitespace() || it == '/' } ||
            username.isBlank() || sshPort == null || sshPort !in 1..65535 ||
            !PinnedHostKeys.validFingerprint(expected) || password.isEmpty()) {
            password.fill('\u0000')
            phase = Phase.ERROR
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
        phase = Phase.CONNECTING
        message = "Verificando host e autenticando…"
        job = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { current.connect(hostname, sshPort, username, secret, pins, 80, 24) }
                if (id != generation) return@launch
                prefs.edit {
                    putString("pin:$hostname:$sshPort", expected)
                    putString("last-host", hostname); putString("last-port", "$sshPort")
                    putString("last-user", username)
                }
                phase = Phase.CONNECTED
                message = "SSH conectado · host verificado · PTY real"
                // Apply actual view dimensions even if they were measured before channel.connect().
                val emulator = screen.emulator
                queue.trySend(Action.Resize(emulator.mColumns, emulator.mRows, 0, 0))
                coroutineScope {
                    val writer = launch(Dispatchers.IO) {
                        try {
                            for (action in queue) when (action) {
                                is Action.Write -> { current.output.write(action.bytes); current.output.flush() }
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
                                    withContext(Dispatchers.Main) { if (id == generation) screen.append(chunk) }
                                }
                            }
                        }
                    } finally { queue.close(); writer.cancel() }
                }
                if (id == generation) { phase = Phase.ENDED; message = "Sessão encerrada pelo servidor. Reconecte quando desejar." }
            } catch (_: CancellationException) {
                // Explicit disconnect already supplies the UI state; stale attempts cannot overwrite it.
            } catch (_: Exception) {
                if (id == generation) {
                    phase = Phase.ERROR
                    message = if (pins.rejected) "Chave do host divergente. Conexão recusada antes da autenticação."
                    else if (!pins.verified) "Falha de conexão ou negociação SSH. Confira endereço, rede e fingerprint."
                    else "Falha de autenticação ou conexão interrompida. Confira a senha e reconecte."
                }
            } finally {
                secret.fill(0)
                withContext(NonCancellable + Dispatchers.IO) { current.close() }
                if (id == generation) { screen.finish(); connection = null; pending = null; ctrl = false }
            }
        }
    }

    fun disconnect(reason: String = "Sessão encerrada. Você pode reconectar.") {
        generation++
        val old = connection
        connection = null
        pending?.close(); pending = null
        job?.cancel(); job = null
        terminal?.finish()
        ctrl = false
        phase = Phase.ENDED
        message = reason
        if (old != null) viewModelScope.launch(Dispatchers.IO) { old.close() }
    }
    override fun onCleared() {
        // The close task must survive cancellation of viewModelScope.
        val old = connection
        if (old != null) Thread({ old.close() }, "myct-ssh-close").start()
        super.onCleared()
    }
}
