package dev.myct

import androidx.compose.runtime.*
import kotlinx.coroutines.*

internal class TorrentsModel internal constructor(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val execute: (SshConnection, TorrentAction) -> String = { ssh, action -> ssh.torrents(action) }
) {
    private var connection: SshConnection? = null
    private var generation = 0
    private var job: Job? = null
    var connected by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    private var passiveRefresh by mutableStateOf(false)
    val actionsEnabled get() = connected && (!busy || passiveRefresh)
    val formEnabled get() = actionsEnabled
    val showOperationProgress get() = busy && !passiveRefresh
    var items by mutableStateOf(emptyList<ServerTorrent>())
        private set
    var message by mutableStateOf("Conecte ao Fedora pela aba Terminal.")
        private set
    var stale by mutableStateOf(false)
        private set

    fun attach(value: SshConnection?) {
        generation++
        job?.cancel(); job = null
        connection = value; connected = value != null
        busy = false; passiveRefresh = false; items = emptyList(); stale = false
        message = if (connected) "Consulte o histórico do Fedora." else "SSH desconectado. Conecte pela aba Terminal."
    }
    fun refresh(passive: Boolean = false) = operate(TorrentAction.List, passive)
    fun add(magnet: String) = operate(TorrentAction.Add(magnet.trim()))
    fun control(verb: String, hash: String) = operate(TorrentAction.Control(verb, hash))
    // Leaving the tab stops queries. A mutation already sent may have succeeded: never retry it.
    fun hide() {
        if (!busy) return
        generation++; job?.cancel(); job = null; busy = false; passiveRefresh = false; stale = true
        message = "Consulta encerrada. Atualize para conferir o estado real antes de repetir uma ação."
    }
    private fun operate(action: TorrentAction, passive: Boolean = false) {
        val ssh = connection ?: return
        if (busy && (passive || !passiveRefresh)) return
        // A click takes priority over polling. Await its channel cleanup before opening
        // another one; never interrupt or replay an explicit start/cancel operation.
        val previous = job.takeIf { busy }
        val epoch = ++generation
        previous?.cancel()
        passiveRefresh = passive
        busy = true
        job = scope.launch {
            try {
                previous?.join()
                val result = runInterruptible(Dispatchers.IO) { execute(ssh, action) }
                val notice = when (action) {
                    TorrentAction.List -> null
                    is TorrentAction.Add -> {
                        val reply = org.json.JSONObject(result)
                        require(torrentHash.matches(reply.getString("hash")))
                        if (reply.getBoolean("duplicate")) "Torrent já existente no motor; não foi adicionado novamente (ID ${reply.getLong("id")})."
                        else "Torrent aceito pelo Fedora (ID ${reply.getLong("id")}). Consulte o estado real."
                    }
                    is TorrentAction.Control -> {
                        val reply = org.json.JSONObject(result)
                        require(reply.getBoolean("ok") && reply.getString("hash") == action.hash)
                        if (action.verb == "remove") "Removido da lista; dados preservados no Fedora." else "Comando aceito pelo motor."
                    }
                }
                val list = if (action == TorrentAction.List) result else
                    runInterruptible(Dispatchers.IO) { execute(ssh, TorrentAction.List) }
                val parsed = parseTorrents(list)
                if (epoch == generation) {
                    items = parsed; stale = false
                    message = notice ?: if (parsed.isEmpty()) "Nenhum torrent My-CT no Fedora." else "Torrents atualizados do Fedora."
                }
            } catch (_: CancellationException) {
            } catch (failure: DownloadFailure) {
                if (epoch == generation) { stale = true; message = failure.safeMessage }
            } catch (_: IllegalArgumentException) {
                if (epoch == generation) {
                    stale = true
                    message = if (action is TorrentAction.Add) "Confira o magnet. Se o pedido foi enviado, atualize antes de repetir."
                        else "Resposta inesperada ou incompleta da CLI. Atualize; dados anteriores não confirmam o estado atual."
                }
            } catch (_: Exception) {
                if (epoch == generation) { stale = true; message = "Falha no canal SSH. Confira a conexão e atualize antes de repetir a ação." }
            } finally { if (epoch == generation) { busy = false; passiveRefresh = false } }
        }
    }
}
