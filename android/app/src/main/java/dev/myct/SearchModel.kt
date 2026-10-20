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
    val busy get() = state == SearchState.Loading || loadingMore
    var loadingMore by mutableStateOf(false)
        private set
    var pageError by mutableStateOf<String?>(null)
        private set
    var indexers by mutableStateOf(emptyList<SearchIndexer>())
        private set
    var selectedIndexer by mutableStateOf<Int?>(null)
        private set
    var hasMore by mutableStateOf(false)
        private set
    var total by mutableStateOf(0)
        private set
    var received by mutableStateOf(0)
        private set
    var limited by mutableStateOf(false)
        private set
    var paginated by mutableStateOf(false)
        private set
    private var page = 0
    var items by mutableStateOf(emptyList<SearchResult>())
        private set
    var truncated by mutableStateOf(false)
        private set
    var message by mutableStateOf("Conecte ao Fedora pela aba Terminal.")
        private set
    var additionMagnet by mutableStateOf<String?>(null)
        private set
    var additionRef by mutableStateOf<String?>(null)
        private set
    val additionRequested get() = additionMagnet != null || additionRef != null

    fun attach(value: SshConnection?) {
        cancel()
        connection = value
        indexers = emptyList(); selectedIndexer = null
        clearResults()
        state = if (connected) SearchState.Idle else SearchState.Disconnected
        message = if (connected) "Digite uma consulta e toque em Pesquisar." else "SSH desconectado. Conecte pela aba Terminal."
    }
    private fun cancel() {
        generation++; job?.cancel(); job = null; loadingMore = false
        if (additionRequested) torrents.cancelSearchAddition(additionMagnet, additionRef)
    }
    private fun clearResults() {
        items = emptyList(); truncated = false; additionMagnet = null; additionRef = null
        page = 0; hasMore = false; total = 0; received = 0; limited = false; paginated = false; pageError = null
    }
    fun selectIndexer(id: Int?) {
        if (id == selectedIndexer || (id != null && indexers.none { it.id == id && it.available })) return
        cancel(); selectedIndexer = id; clearResults()
        state = if (connected) SearchState.Idle else SearchState.Disconnected
        message = "Fonte alterada. Toque em Pesquisar."
    }
    fun edit(value: String) {
        if (value == query) return
        cancel(); query = value; clearResults()
        state = if (connected) SearchState.Idle else SearchState.Disconnected
        message = if (connected) "Toque em Pesquisar para consultar." else "SSH desconectado. Conecte pela aba Terminal."
    }
    fun hide() {
        if (additionRequested) torrents.cancelSearchAddition(additionMagnet, additionRef)
        if (!busy) return
        val keepResults = loadingMore
        cancel()
        if (keepResults) {
            state = SearchState.Results; pageError = "Carregamento cancelado. Você pode tentar novamente."
        } else {
            clearResults()
            state = if (connected) SearchState.Idle else SearchState.Disconnected
            message = "Consulta encerrada. Pesquise novamente quando desejar."
        }
    }
    fun checkStatus() = request(SearchAction.Status)
    fun search() {
        if (!validSearchQuery(query)) {
            cancel(); clearResults(); state = SearchState.Error
            message = "Use uma consulta de 1 a 200 caracteres, sem quebras de linha."
            return
        }
        request(SearchAction.Query(query, indexerId = selectedIndexer))
    }
    fun loadMore() {
        if (busy || !hasMore || page !in 1..24 || state != SearchState.Results) return
        request(SearchAction.Query(query, page + 1, selectedIndexer), append = true)
    }
    private fun request(action: SearchAction, append: Boolean = false) {
        val ssh = connection ?: return
        if (busy || (additionRequested && torrents.busy)) return
        cancel()
        if (!append) clearResults()
        val epoch = generation
        pageError = null
        if (append) loadingMore = true else state = SearchState.Loading
        if (!append) message = if (action == SearchAction.Status) "Verificando serviço…" else "Pesquisando…"
        job = scope.launch {
            try {
                val reply = runInterruptible(Dispatchers.IO) { execute(ssh, action) }
                if (epoch != generation) return@launch
                when (reply) {
                    is SearchReply.Ready -> {
                        indexers = reply.indexers
                        if (selectedIndexer != null && indexers.none { it.id == selectedIndexer && it.available }) selectedIndexer = null
                        state = SearchState.Ready
                        message = "Prowlarr ${reply.version} disponível. Selecione uma fonte e toque em Pesquisar."
                    }
                    is SearchReply.Results -> {
                        items = (if (append) items + reply.items else reply.items).distinctBy { it.identity() }
                        page = reply.page; hasMore = reply.hasMore; total = reply.total; received = reply.received
                        limited = reply.limited; paginated = reply.paginated; truncated = reply.truncated
                        state = if (items.isEmpty()) SearchState.Empty else SearchState.Results
                        message = if (items.isEmpty()) "Nenhum resultado. Tente outros termos." else "${items.size} carregados · ${total} disponíveis nesta consulta · ${received} recebidos das fontes."
                    }
                }
            } catch (_: CancellationException) {
            } catch (failure: SearchFailure) {
                if (epoch == generation) fail(failure.safeMessage, append)
            } catch (_: DownloadFailure) {
                if (epoch == generation) fail("Canal SSH indisponível, excedeu o limite ou o tempo de espera. Confira a conexão e tente manualmente.", append)
            } catch (_: Exception) {
                if (epoch == generation) fail("Falha no SSH ou resposta de pesquisa inválida. Confira a conexão e o helper no Fedora.", append)
            } finally {
                if (epoch == generation) loadingMore = false
            }
        }
    }
    private fun fail(safeMessage: String, append: Boolean) {
        if (append) { state = SearchState.Results; pageError = safeMessage }
        else { state = SearchState.Error; message = safeMessage }
    }
    fun canAdd(item: SearchResult): Boolean = connected && state == SearchState.Results &&
        item in items && item.magnet != null && !busy && torrents.connected && torrents.actionsEnabled && !torrents.busy

    fun cancelAddition() = torrents.cancelSearchAddition(additionMagnet, additionRef)

    fun canAddFile(item: SearchResult): Boolean = connected && state == SearchState.Results && !busy &&
        item in items && item.torrentAvailable && item.resultRef != null && item.indexerId != null &&
        torrents.connected && torrents.actionsEnabled && !torrents.busy

    fun addFile(item: SearchResult) {
        if (!canAddFile(item)) return
        additionMagnet = null; additionRef = item.resultRef
        torrents.addFile(query, checkNotNull(item.indexerId), checkNotNull(item.resultRef))
    }

    fun add(item: SearchResult) {
        if (!canAdd(item)) return
        val magnet = usableSearchMagnet(item.magnet) ?: return
        additionRef = null
        additionMagnet = magnet
        torrents.add(magnet) // Existing TorrentAction.Add, validation, RPC and notices.
    }
}
