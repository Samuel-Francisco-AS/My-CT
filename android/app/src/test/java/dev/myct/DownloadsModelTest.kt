package dev.myct

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DownloadsModelTest {
    private val id = "158ab86e-1fbe-4d6f-b337-65c96b3c353a"
    private fun record(state: String) = "id: $id\nstate: $state\nbytes: 10\ntotal: -1\npath: /home/sam/Downloads/My-CT/$id/test.part\nmessage: Parcial preservado\n"
    private fun await(predicate: () -> Boolean) = runBlocking { withTimeout(3000) { while (!predicate()) delay(5) } }
    @Test fun cancellationAndReconnectReadServerStateWithoutReplayingAction() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val actions = mutableListOf<DownloadAction>()
        var serverState = "running"
        val model = DownloadsModel(scope) { _, action ->
            actions.add(action)
            if (action is DownloadAction.Cancel) serverState = "canceled"
            record(serverState)
        }
        try {
            model.attach(SshConnection()); model.refresh(); await { !model.busy }
            assertTrue(model.items.single().active)
            model.cancel(id); await { !model.busy }
            assertEquals(DownloadState.canceled, model.items.single().state)
            model.attach(null)
            assertTrue(model.items.isEmpty()); assertFalse(model.connected)
            model.attach(SshConnection()); model.refresh(); await { !model.busy }
            assertEquals(DownloadState.canceled, model.items.single().state)
            assertEquals(1, actions.count { it is DownloadAction.Cancel })
        } finally { scope.cancel() }
    }
    @Test fun disconnectAndHideInterruptQueryAndKeepUiResponsive() {
        listOf(false, true).forEach { hide ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val entered = CountDownLatch(1); val interrupted = CountDownLatch(1)
            val model = DownloadsModel(scope) { _, _ ->
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
    @Test fun passivePollingKeepsFormEditableWhileActionsRemainLockedAcrossFourCycles() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val entered = java.util.concurrent.Semaphore(0)
        val release = java.util.concurrent.Semaphore(0)
        var queries = 0
        val model = DownloadsModel(scope) { _, action ->
            assertEquals(DownloadAction.List, action)
            queries++
            entered.release()
            release.acquire()
            record("running").replace("bytes: 10", "bytes: $queries")
        }
        try {
            model.attach(SshConnection())
            repeat(4) {
                model.refresh(passive = true)
                assertTrue(entered.tryAcquire(2, TimeUnit.SECONDS))
                assertTrue(model.busy) // Baixar/Atualizar/Cancelar retain their existing locks.
                assertTrue(model.formEnabled)
                assertFalse(model.showOperationProgress)
                model.refresh(); model.cancel(id); model.start("https://example.com/", "test")
                release.release()
                await { !model.busy }
                assertEquals((it + 1).toLong(), model.items.single().bytes)
                assertTrue(model.formEnabled)
            }
            assertEquals(4, queries)
            model.refresh() // An explicit action still owns the form and visible progress.
            assertTrue(entered.tryAcquire(2, TimeUnit.SECONDS))
            assertFalse(model.formEnabled)
            assertTrue(model.showOperationProgress)
            model.attach(null)
            assertFalse(model.formEnabled)
            assertFalse(model.showOperationProgress)
            assertFalse(model.busy)
        } finally { release.release(); scope.cancel() }
    }

    @Test fun malformedServerReplyEndsBusyStateAndMarksHistoryStale() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val model = DownloadsModel(scope) { _, _ -> "id: incomplete\n" }
        try {
            model.attach(SshConnection()); model.refresh(); await { !model.busy }
            assertTrue(model.stale); assertTrue(model.message.contains("incompleta"))
        } finally { scope.cancel() }
    }
}
