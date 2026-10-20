package dev.myct

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TorrentsModelTest {
    private val id = "a".repeat(40)
    private fun record(state: String) = """{"torrents":[{"id":1,"hash_string":"$id","name":"fixture","status":${if(state == "canceled") 0 else 4},"total_size":100,"size_when_done":100,"left_until_done":90,"percent_done":0.1,"metadata_percent_complete":1.0,"download_dir":"/home/sam/Downloads/My-CT/Torrents/$id","rate_download":0,"rate_upload":0,"peers_connected":0}]}"""
    private fun reply() = """{"ok":true,"hash":"$id"}"""
    private fun await(predicate: () -> Boolean) = runBlocking { withTimeout(3000) { while (!predicate()) delay(5) } }
    @Test fun cancellationAndReconnectReadServerStateWithoutReplayingAction() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val actions = mutableListOf<TorrentAction>()
        var serverState = "running"
        val model = TorrentsModel(scope) { _, action ->
            actions.add(action)
            if (action is TorrentAction.Control) serverState = "canceled"
            if (action is TorrentAction.Control) reply() else record(serverState)
        }
        try {
            model.attach(SshConnection()); model.refresh(); await { !model.busy }
            assertTrue(!model.items.single().paused)
            model.control("pause", id); await { !model.busy }
            assertEquals(TorrentState.STOPPED, model.items.single().state)
            model.attach(null)
            assertTrue(model.items.isEmpty()); assertFalse(model.connected)
            model.attach(SshConnection()); model.refresh(); await { !model.busy }
            assertEquals(TorrentState.STOPPED, model.items.single().state)
            assertEquals(1, actions.count { it is TorrentAction.Control })
        } finally { scope.cancel() }
    }
    @Test fun disconnectAndHideInterruptQueryAndKeepUiResponsive() {
        listOf(false, true).forEach { hide ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val entered = CountDownLatch(1); val interrupted = CountDownLatch(1)
            val model = TorrentsModel(scope) { _, _ ->
                entered.countDown()
                try { Thread.sleep(10000); "" } finally { interrupted.countDown() }
            }
            try {
                model.attach(SshConnection()); model.refresh()
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                if (hide) model.hide() else model.attach(null)
                assertFalse(model.busy)
                assertTrue(interrupted.await(2, TimeUnit.SECONDS))
                assertTrue(model.items.isEmpty())
            } finally { scope.cancel() }
        }
    }
    @Test fun passivePollingKeepsFormAndButtonsEnabledAcrossFourCycles() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val entered = java.util.concurrent.Semaphore(0)
        val release = java.util.concurrent.Semaphore(0)
        var queries = 0
        val model = TorrentsModel(scope) { _, action ->
            assertEquals(TorrentAction.List, action)
            queries++
            entered.release()
            release.acquire()
            record("running").replace("\"left_until_done\":90", "\"left_until_done\":${100 - queries}")
        }
        try {
            model.attach(SshConnection())
            repeat(4) {
                model.refresh(passive = true)
                assertTrue(entered.tryAcquire(2, TimeUnit.SECONDS))
                assertTrue(model.busy) // A query is in flight, but it must not disable the buttons.
                assertTrue(model.actionsEnabled)
                assertTrue(model.formEnabled)
                assertFalse(model.showOperationProgress)
                release.release()
                await { !model.busy }
                assertEquals((it + 1).toLong(), model.items.single().bytes)
                assertTrue(model.formEnabled)
            }
            assertEquals(4, queries)
            model.refresh() // An explicit action still owns the form and visible progress.
            assertTrue(entered.tryAcquire(2, TimeUnit.SECONDS))
            assertFalse(model.formEnabled)
            assertFalse(model.actionsEnabled)
            assertTrue(model.showOperationProgress)
            model.attach(null)
            assertFalse(model.formEnabled)
            assertFalse(model.showOperationProgress)
            assertFalse(model.busy)
        } finally { release.release(); scope.cancel() }
    }

    @Test fun userClickPreemptsPollingClosesItsChannelAndRunsExactlyOnce() {
        val start = TorrentAction.Add("magnet:?xt=urn:btih:$id")
        for (action in listOf(TorrentAction.List, start, TorrentAction.AddFile("Ubuntu", 12, "a".repeat(64)), TorrentAction.Control("pause", id))) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val pollingEntered = CountDownLatch(1)
            val pollingClosed = CountDownLatch(1)
            val actionEntered = CountDownLatch(1)
            val releaseAction = CountDownLatch(1)
            val calls = java.util.concurrent.ConcurrentLinkedQueue<TorrentAction>()
            val model = TorrentsModel(scope) { _, command ->
                calls.add(command)
                if (calls.size == 1) {
                    pollingEntered.countDown()
                    try { Thread.sleep(10000) } finally { pollingClosed.countDown() }
                    record("failed") // Canceled polling must never publish this stale response.
                } else {
                    check(pollingClosed.count == 0L)
                    if (calls.size == 2) {
                        actionEntered.countDown()
                        check(releaseAction.await(2, TimeUnit.SECONDS))
                    }
                    if (command is TorrentAction.Add || command is TorrentAction.AddFile) """{"hash":"$id","id":1,"duplicate":false}""" else if (command is TorrentAction.Control) reply() else record("canceled")
                }
            }
            fun click() = when (action) {
                TorrentAction.List -> model.refresh()
                is TorrentAction.Add -> model.add(action.magnet)
                is TorrentAction.AddFile -> model.addFile(action.query, action.indexerId, action.resultRef)
                is TorrentAction.Control -> model.control(action.verb, action.hash)
            }
            try {
                model.attach(SshConnection()); model.refresh(passive = true)
                assertTrue(pollingEntered.await(2, TimeUnit.SECONDS))
                assertTrue(model.actionsEnabled)
                click()
                assertTrue(actionEntered.await(2, TimeUnit.SECONDS))
                assertFalse(model.actionsEnabled)
                assertTrue(model.showOperationProgress)
                click(); model.refresh(passive = true) // No duplicate action or polling overlap.
                assertEquals(listOf(TorrentAction.List, action), calls.toList())
                releaseAction.countDown()
                await { !model.busy }
                val expected = if (action == TorrentAction.List) listOf(TorrentAction.List, action)
                    else listOf(TorrentAction.List, action, TorrentAction.List)
                assertEquals(expected, calls.toList())
                assertEquals(TorrentState.STOPPED, model.items.single().state)
                assertFalse(model.stale)
                assertTrue(model.actionsEnabled)
            } finally { releaseAction.countDown(); scope.cancel() }
        }
    }

    @Test fun malformedServerReplyEndsBusyStateAndMarksHistoryStale() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val model = TorrentsModel(scope) { _, _ -> "id: incomplete\n" }
        try {
            model.attach(SshConnection()); model.refresh(); await { !model.busy }
            assertTrue(model.stale); assertTrue(model.message.isNotBlank())
        } finally { scope.cancel() }
    }
}
