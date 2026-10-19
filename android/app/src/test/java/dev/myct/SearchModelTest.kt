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
