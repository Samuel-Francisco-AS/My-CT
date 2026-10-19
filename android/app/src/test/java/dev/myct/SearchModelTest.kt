package dev.myct

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentLinkedQueue

class SearchModelTest {
    private val magnet = "magnet:?xt=urn:btih:" + "a".repeat(40)
    private fun item(magnet: String? = this.magnet) = SearchResult("Ubuntu", 123L, "Knaben", 1, null, magnet, if (magnet == null) null else "infohash")
    private fun await(predicate: () -> Boolean) = runBlocking { withTimeout(3000) { while (!predicate()) delay(5) } }

    @Test fun queriesAreExplicitAndReconnectClearsResultsWithoutReplaying() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val calls = ConcurrentLinkedQueue<SearchAction>()
        val torrents = TorrentsModel(scope) { _, _ -> error("No torrent action allowed") }
        val model = SearchModel(torrents, scope) { _, action ->
            calls.add(action)
            if (action == SearchAction.Status) SearchReply.Ready("2.6.5") else SearchReply.Results(listOf(item()), true, 84)
        }
        try {
            model.attach(SshConnection()); model.edit("Ubuntu")
            assertTrue(calls.isEmpty())
            model.checkStatus(); await { !model.busy }
            assertEquals(SearchState.Ready, model.state)
            model.search(); await { !model.busy }
            assertEquals(SearchState.Results, model.state); assertTrue(model.truncated)
            model.attach(null)
            assertEquals(SearchState.Disconnected, model.state); assertTrue(model.items.isEmpty())
            model.attach(SshConnection())
            assertEquals(SearchState.Idle, model.state)
            assertEquals(listOf(SearchAction.Status, SearchAction.Query("Ubuntu")), calls.toList())
        } finally { scope.cancel() }
    }
    @Test fun malformedInputEmptyAndFailureHavePredictableStates() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var fail = false
        var calls = 0
        val model = SearchModel(TorrentsModel(scope), scope) { _, _ ->
            calls++
            if (fail) throw SearchFailure("Tempo limite da pesquisa.")
            SearchReply.Results(emptyList(), false, 0)
        }
        try {
            model.attach(SshConnection()); model.edit("Ubuntu\n"); model.search()
            assertEquals(SearchState.Error, model.state); assertEquals(0, calls)
            model.edit("Ubuntu"); model.search(); await { !model.busy }
            assertEquals(SearchState.Empty, model.state)
            fail = true; model.search(); await { !model.busy }
            assertEquals(SearchState.Error, model.state); assertTrue(model.message.contains("Tempo limite"))
        } finally { scope.cancel() }
    }
    @Test fun hideDisconnectAndEditingInterruptSearchAndDiscardLateReply() {
        for (change in listOf("hide", "disconnect", "edit")) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val finished = CountDownLatch(1)
            val model = SearchModel(TorrentsModel(scope), scope) { _, _ ->
                entered.countDown()
                // A server response may race with cancellation; even a late reply must not publish.
                while (true) { try { release.await(); break } catch (_: InterruptedException) { } }
                finished.countDown()
                SearchReply.Results(listOf(item()), false, 1)
            }
            try {
                model.attach(SshConnection()); model.edit("Ubuntu"); model.search()
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                when (change) { "hide" -> model.hide(); "disconnect" -> model.attach(null); else -> model.edit("Fedora") }
                assertFalse(model.busy)
                release.countDown(); assertTrue(finished.await(2, TimeUnit.SECONDS))
                runBlocking { delay(40) }
                assertTrue(model.items.isEmpty())
                assertEquals(if (change == "disconnect") SearchState.Disconnected else SearchState.Idle, model.state)
            } finally { release.countDown(); scope.cancel() }
        }
    }
    private fun numbered(number: Int) = item().copy(title = "Ubuntu $number", id = number.toString(16).padStart(64, '0'))
    private fun page(number: Int, items: List<SearchResult>, more: Boolean = true, indexer: Int? = null) =
        SearchReply.Results(items, true, 60, number, indexer, more, 60, paginated = true)

    @Test fun appendsDeduplicatesRetriesSamePageAndStopsWithoutAutomaticTorrentActions() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val calls = ConcurrentLinkedQueue<SearchAction>()
        var failure: Exception? = null
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val model = SearchModel(TorrentsModel(scope) { _, _ -> error("No torrent action allowed") }, scope) { _, action ->
            calls.add(action)
            val query = action as SearchAction.Query
            when (query.page) {
                1 -> page(1, (1..20).map(::numbered))
                2 -> {
                    entered.countDown(); release.await()
                    failure?.let { throw it }
                    page(2, (20..39).map(::numbered)) // Live response shifted one result.
                }
                else -> page(3, listOf(numbered(40)), more = false)
            }
        }
        try {
            model.attach(SshConnection()); model.edit("Ubuntu"); model.search(); await { !model.busy }
            val original = model.items
            failure = DownloadFailure("SSH timeout")
            model.loadMore(); assertTrue(entered.await(2, TimeUnit.SECONDS))
            model.loadMore(); model.search(); assertEquals(2, calls.size)
            assertEquals(original, model.items); assertTrue(model.loadingMore)
            release.countDown(); await { !model.busy }
            assertEquals(original, model.items); assertNotNull(model.pageError); assertTrue(model.hasMore)
            failure = null; model.loadMore(); await { !model.busy }
            assertEquals(39, model.items.size); assertEquals(original, model.items.take(20)); assertNull(model.pageError)
            model.loadMore(); await { !model.busy }
            assertEquals(40, model.items.size); assertFalse(model.hasMore)
            model.loadMore(); assertEquals(listOf(1, 2, 2, 3), calls.map { (it as SearchAction.Query).page })
        } finally { release.countDown(); scope.cancel() }
    }
    @Test fun filterChangesInvalidateResultsAndRequireExplicitSearchWithDynamicId() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val calls = ConcurrentLinkedQueue<SearchAction>()
        val model = SearchModel(TorrentsModel(scope), scope) { _, action ->
            calls.add(action)
            if (action == SearchAction.Status) SearchReply.Ready("2.6.5", listOf(SearchIndexer(17, "Dynamic"), SearchIndexer(6, "Blocked", false)))
            else page(1, listOf(numbered(1)), indexer = (action as SearchAction.Query).indexerId)
        }
        try {
            model.attach(SshConnection()); model.checkStatus(); await { !model.busy }
            model.edit("Ubuntu"); model.search(); await { !model.busy }
            assertEquals(1, model.items.size)
            model.selectIndexer(6); assertNull(model.selectedIndexer)
            model.selectIndexer(999); assertNull(model.selectedIndexer)
            model.selectIndexer(17); assertEquals(17, model.selectedIndexer)
            assertTrue(model.items.isEmpty()); assertFalse(model.hasMore); assertEquals(2, calls.size)
            model.search(); await { !model.busy }
            assertEquals(SearchAction.Query("Ubuntu", indexerId = 17), calls.last())
            model.selectIndexer(null); assertTrue(model.items.isEmpty()); assertEquals(3, calls.size)
            model.search(); await { !model.busy }
            assertEquals(SearchAction.Query("Ubuntu"), calls.last())
            model.attach(null); assertTrue(model.indexers.isEmpty()); assertNull(model.selectedIndexer)
        } finally { scope.cancel() }
    }
    @Test fun latePageAfterQueryFilterCancelOrDisconnectCannotMixResults() {
        for (change in listOf("query", "filter", "cancel", "disconnect")) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val finished = CountDownLatch(1)
            val calls = ConcurrentLinkedQueue<SearchAction>()
            val model = SearchModel(TorrentsModel(scope), scope) { _, action ->
                calls.add(action)
                if (action == SearchAction.Status) SearchReply.Ready("2.6.5", listOf(SearchIndexer(17, "Dynamic")))
                else if ((action as SearchAction.Query).page == 1) page(1, listOf(numbered(1)), indexer = action.indexerId)
                else {
                    entered.countDown()
                    while (true) { try { release.await(); break } catch (_: InterruptedException) { } }
                    finished.countDown(); page(2, listOf(numbered(2)))
                }
            }
            try {
                model.attach(SshConnection()); model.checkStatus(); await { !model.busy }
                model.edit("Ubuntu"); model.search(); await { !model.busy }
                model.loadMore(); assertTrue(entered.await(2, TimeUnit.SECONDS))
                when (change) {
                    "query" -> model.edit("Fedora")
                    "filter" -> model.selectIndexer(17)
                    "cancel" -> model.hide()
                    else -> model.attach(null)
                }
                release.countDown(); assertTrue(finished.await(2, TimeUnit.SECONDS))
                runBlocking { delay(40) }
                assertFalse(model.busy)
                if (change == "cancel") { assertEquals(listOf(numbered(1)), model.items); assertTrue(model.hasMore) }
                else { assertTrue(model.items.isEmpty()); assertFalse(model.hasMore) }
                assertEquals(3, calls.size)
            } finally { release.countDown(); scope.cancel() }
        }
    }
    @Test fun explicitAdditionUsesExistingTorrentActionOnceAndReportsAcceptOrDuplicate() {
        for (outcome in listOf("accepted", "duplicate", "failure")) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val calls = ConcurrentLinkedQueue<TorrentAction>()
            val torrents = TorrentsModel(scope) { _, action ->
                calls.add(action)
                if (action is TorrentAction.Add) {
                    entered.countDown(); release.await()
                    if (outcome == "failure") throw DownloadFailure("Falha sintética segura.")
                    """{"hash":"${"a".repeat(40)}","id":1,"duplicate":${outcome == "duplicate"}}"""
                } else """{"torrents":[]}"""
            }
            val model = SearchModel(torrents, scope) { _, _ -> SearchReply.Results(listOf(item(), item(null)), false, 2) }
            try {
                val ssh = SshConnection(); torrents.attach(ssh); model.attach(ssh)
                model.edit("Ubuntu"); model.search(); await { !model.busy }
                assertTrue(calls.isEmpty()) // Searching or rendering does not add anything.
                model.add(model.items[1]); assertTrue(calls.isEmpty())
                model.add(model.items[0]); assertTrue(entered.await(2, TimeUnit.SECONDS))
                model.add(model.items[0]); assertEquals(1, calls.size)
                assertFalse(model.canAdd(model.items[0])); assertTrue(model.additionRequested)
                release.countDown(); await { !torrents.busy }
                val expected = if (outcome == "failure") listOf(TorrentAction.Add(magnet))
                    else listOf(TorrentAction.Add(magnet), TorrentAction.List)
                assertEquals(expected, calls.toList())
                assertTrue(torrents.message.contains(when (outcome) {
                    "failure" -> "Falha sintética"; "duplicate" -> "já existente"; else -> "aceito"
                }))
                assertEquals(outcome == "failure", torrents.stale)
                assertFalse(torrents.message.contains("Baixando"))
                model.attach(null); model.add(item())
                assertEquals(expected.size, calls.size)
            } finally { release.countDown(); scope.cancel() }
        }
    }
}
