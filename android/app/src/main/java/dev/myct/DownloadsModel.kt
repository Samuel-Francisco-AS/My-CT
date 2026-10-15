package dev.myct

import androidx.compose.runtime.*
import kotlinx.coroutines.*

class DownloadsModel internal constructor(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val execute: (SshConnection, DownloadAction) -> String = { ssh, action -> ssh.downloads(action) }
) {
    private var connection: SshConnection? = null
    private var generation = 0
    private var job: Job? = null
    var connected by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    private var passiveRefresh by mutableStateOf(false)
    val formEnabled get() = connected && (!busy || passiveRefresh)
    val showOperationProgress get() = busy && !passiveRefresh
    var items by mutableStateOf(emptyList<ServerDownload>())
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
    fun refresh(passive: Boolean = false) = operate(DownloadAction.List, passive)
    fun start(url: String, name: String) {
        if (items.any { it.active } && !stale) {
            message = "Já existe uma transferência ativa; não há fila. Aguarde ou cancele."; return
        }
        operate(DownloadAction.Start(url.trim(), name.trim()))
    }
    fun cancel(id: String) = operate(DownloadAction.Cancel(id))
    // Leaving the tab stops queries. A mutation already sent may have succeeded: never retry it.
    fun hide() {
        if (!busy) return
        generation++; job?.cancel(); job = null; busy = false; passiveRefresh = false; stale = true
        message = "Consulta encerrada. Atualize para conferir o estado real antes de repetir uma ação."
    }
    private fun operate(action: DownloadAction, passive: Boolean = false) {
        val ssh = connection ?: return
        if (busy) return
        val epoch = generation
        passiveRefresh = passive
        busy = true
        job = scope.launch {
            try {
                val result = runInterruptible(Dispatchers.IO) { execute(ssh, action) }
                val notice = when (action) {
                    DownloadAction.List -> null
                    is DownloadAction.Start -> {
                        require(downloadId.matches(result.trim()))
                        "Pedido aceito: ${result.trim()}. Consulte o estado; a transferência pode falhar no servidor."
                    }
                    is DownloadAction.Cancel -> {
                        parseDownloads(result).also { require(it.single().id == action.id) }
                        "Cancelamento consultado. Arquivos parciais são preservados."
                    }
                }
                val list = if (action == DownloadAction.List) result else
                    runInterruptible(Dispatchers.IO) { execute(ssh, DownloadAction.List) }
                val parsed = parseDownloads(list)
                if (epoch == generation) {
                    items = parsed; stale = false
                    message = notice ?: if (parsed.isEmpty()) "Nenhum download no Fedora." else "Histórico atualizado do Fedora."
                }
            } catch (_: CancellationException) {
            } catch (failure: DownloadFailure) {
                if (epoch == generation) { stale = true; message = failure.safeMessage }
            } catch (_: IllegalArgumentException) {
                if (epoch == generation) {
                    stale = true
                    message = if (action is DownloadAction.Start) "Confira URL HTTP/HTTPS e nome. Se o pedido foi enviado, atualize antes de repetir."
                        else "Resposta inesperada ou incompleta da CLI. Atualize; dados anteriores não confirmam o estado atual."
                }
            } catch (_: Exception) {
                if (epoch == generation) { stale = true; message = "Falha no canal SSH. Confira a conexão e atualize antes de repetir a ação." }
            } finally { if (epoch == generation) { busy = false; passiveRefresh = false } }
        }
    }
}
