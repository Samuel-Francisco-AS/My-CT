package dev.myct

import androidx.compose.runtime.*
import kotlinx.coroutines.*

internal enum class SearchState { Disconnected, Idle, Loading, Ready, Results, Empty, Error }

internal class SearchModel(
    private val torrents: TorrentsModel,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val execute: (SshConnection, SearchAction) -> SearchReply = { ssh, action -> ssh.search(action) }
) {
    private var connection: SshConnection? = null
    private var generation = 0
    private var job: Job? = null
    val connected get() = connection != null
    var query by mutableStateOf("")
        private set
    var state by mutableStateOf(SearchState.Disconnected)
        private set
    val busy get() = state == SearchState.Loading
    var items by mutableStateOf(emptyList<SearchResult>())
        private set
    var truncated by mutableStateOf(false)
        private set
    var message by mutableStateOf("Conecte ao Fedora pela aba Terminal.")
        private set
    var additionMagnet by mutableStateOf<String?>(null)
        private set
    val additionRequested get() = additionMagnet != null

    fun attach(value: SshConnection?) {
        cancel()
        connection = value
        clearResults()
        state = if (connected) SearchState.Idle else SearchState.Disconnected
        message = if (connected) "Digite uma consulta e toque em Pesquisar." else "SSH desconectado. Conecte pela aba Terminal."
    }
    private fun cancel() { generation++; job?.cancel(); job = null }
    private fun clearResults() { items = emptyList(); truncated = false; additionMagnet = null }
    fun edit(value: String) {
        if (value == query) return
        cancel(); query = value; clearResults()
        state = if (connected) SearchState.Idle else SearchState.Disconnected
        message = if (connected) "Toque em Pesquisar para consultar." else "SSH desconectado. Conecte pela aba Terminal."
    }
    fun hide() {
        if (!busy) return
        cancel(); clearResults()
        state = if (connected) SearchState.Idle else SearchState.Disconnected
        message = "Consulta encerrada. Pesquise novamente quando desejar."
    }
    fun checkStatus() = request(SearchAction.Status)
    fun search() {
        if (!validSearchQuery(query)) {
            cancel(); clearResults(); state = SearchState.Error
            message = "Use uma consulta de 1 a 200 caracteres, sem quebras de linha."
            return
        }
        request(SearchAction.Query(query))
    }
    private fun request(action: SearchAction) {
        val ssh = connection ?: return
        if (busy) return
        cancel(); clearResults()
        val epoch = generation
        state = SearchState.Loading
        message = if (action == SearchAction.Status) "Verificando serviço…" else "Pesquisando…"
        job = scope.launch {
            try {
                val reply = runInterruptible(Dispatchers.IO) { execute(ssh, action) }
                if (epoch != generation) return@launch
                when (reply) {
                    is SearchReply.Ready -> { state = SearchState.Ready; message = "Prowlarr ${reply.version} disponível. Toque em Pesquisar." }
                    is SearchReply.Results -> {
                        items = reply.items; truncated = reply.truncated
                        state = if (items.isEmpty()) SearchState.Empty else SearchState.Results
                        message = if (items.isEmpty()) "Nenhum resultado. Tente outros termos." else "${items.size} resultados."
                    }
                }
            } catch (_: CancellationException) {
            } catch (failure: SearchFailure) {
                if (epoch == generation) { state = SearchState.Error; message = failure.safeMessage }
            } catch (_: DownloadFailure) {
                if (epoch == generation) { state = SearchState.Error; message = "Canal SSH indisponível, excedeu o limite ou o tempo de espera. Confira a conexão e tente manualmente." }
            } catch (_: Exception) {
                if (epoch == generation) { state = SearchState.Error; message = "Falha no SSH ou resposta de pesquisa inválida. Confira a conexão e o helper no Fedora." }
            }
        }
    }
    fun canAdd(item: SearchResult): Boolean = connected && state == SearchState.Results &&
        item in items && item.magnet != null && torrents.connected && torrents.actionsEnabled && !torrents.busy

    fun add(item: SearchResult) {
        if (!canAdd(item)) return
        val magnet = usableSearchMagnet(item.magnet) ?: return
        additionMagnet = magnet
        torrents.add(magnet) // Existing TorrentAction.Add, validation, RPC and notices.
    }
}
