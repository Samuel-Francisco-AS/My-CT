package dev.myct

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CloudPollingCancelTest {
    @Test fun silentPollOnlyReadsTaskAndKeepsControlsSelectionAndPreviewStable() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val work = CloudWork("torrent", listOf("archive", CLOUD_HASH))
        val task = cloudTaskJson(work, "running").put("progress", cloudProgressJson()).put("cancel_supported", true)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val calls = java.util.concurrent.CopyOnWriteArrayList<CloudQuery>()
        val model = CloudModel(scope, { CLOUD_NOW }) { _, query ->
            calls += query
            when (query) {
                CloudQuery.Snapshot -> cloudSnapshot(task, catalogOk = false)
                is CloudQuery.Task -> {
                    entered.countDown(); release.await(2, TimeUnit.SECONDS)
                    JSONObject().put("ok", true).put("task", task).toString()
                }
                else -> error("No mutation from polling")
            }
        }
        try {
            model.attach(SshConnection()); model.select(CLOUD_HASH); model.refresh()
            withTimeout(3000) { while (model.busy) delay(5) }
            val selected = model.selected; val message = model.message
            model.poll(); assertTrue(entered.await(2, TimeUnit.SECONDS))
            repeat(10) {
                assertFalse(model.busy); assertEquals(selected, model.selected); assertEquals(message, model.message)
                assertTrue(model.tasks.single().canCancel)
                model.poll() // Coalesces while the previous query runs.
            }
            assertEquals(1, calls.count { it is CloudQuery.Snapshot })
            assertEquals(1, calls.count { it is CloudQuery.Task })
            release.countDown()
        } finally { release.countDown(); scope.cancel() }
    }
    @Test fun pollingRefreshesCatalogOnceOnTerminalStateAndCancelIsExplicitNotReplayed() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val work = CloudWork("file", listOf("archive", "/home/user/fixture.bin"))
        var task = cloudTaskJson(work, "running").put("cancel_supported", true)
        val calls = java.util.concurrent.CopyOnWriteArrayList<CloudQuery>()
        val model = CloudModel(scope, { CLOUD_NOW }) { _, query ->
            calls += query
            when (query) {
                CloudQuery.Snapshot -> cloudSnapshot(task)
                is CloudQuery.Task -> JSONObject().put("ok", true).put("task", task).toString()
                is CloudQuery.Cancel -> {
                    task = JSONObject(task.toString()).put("cancel_requested", true)
                    JSONObject().put("ok", true).put("task", task).toString()
                }
                else -> error("No replay")
            }
        }
        suspend fun idle() { withTimeout(3000) { while (model.busy) delay(5) } }
        try {
            model.attach(SshConnection()); model.refresh(); idle()
            model.refresh(); idle() // Manual refresh of active copy also avoids locked catalogs.
            assertEquals(1, calls.count { it is CloudQuery.Snapshot })
            val copy = model.tasks.single()
            model.cancel(copy); idle(); model.cancel(copy); idle()
            assertEquals(1, calls.count { it is CloudQuery.Cancel })
            assertTrue(model.tasks.single().cancelRequested); assertFalse(model.tasks.single().canCancel)
            task = JSONObject(task.toString()).put("state", "cancelled").put("result", JSONObject().put("ok", false).put("error", JSONObject().put("code", "INTERRUPTED")))
            model.poll()
            withTimeout(3000) { while (model.tasks.single().active || calls.count { it is CloudQuery.Snapshot } < 2) delay(5) }
            repeat(4) { model.poll() }
            assertEquals(2, calls.count { it is CloudQuery.Snapshot })
            assertEquals("Cancelada", model.tasks.single().label)
            model.attach(null); model.attach(SshConnection()); model.refresh(); idle()
            assertEquals(1, calls.count { it is CloudQuery.Cancel })
            assertTrue(calls.none { it is CloudQuery.Start })
        } finally { scope.cancel() }
    }
    @Test fun completionObservedByDetailsOrCancelRefreshesCatalogExactlyOnce() = runBlocking {
        for (viaCancel in listOf(false, true)) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val work = CloudWork("file", listOf("archive", "/fixture.bin"))
            var task = cloudTaskJson(work, "running").put("cancel_supported", true)
            var snapshots = 0
            val model = CloudModel(scope, { CLOUD_NOW }) { _, query -> when (query) {
                CloudQuery.Snapshot -> { snapshots++; cloudSnapshot(task) }
                is CloudQuery.Task, is CloudQuery.Cancel -> JSONObject().put("ok", true).put("task", task).toString()
                else -> error("No replay")
            } }
            suspend fun idle() { withTimeout(3000) { while (model.busy) delay(5) } }
            try {
                model.attach(SshConnection()); model.refresh(); idle()
                val active = model.tasks.single()
                task = cloudTaskJson(work, "succeeded", JSONObject().put("ok", true)).put("cancel_supported", true)
                if (viaCancel) model.cancel(active) else model.viewTask(active)
                idle(); repeat(3) { model.poll() }
                assertEquals(2, snapshots); assertEquals("succeeded", model.tasks.single().state)
            } finally { scope.cancel() }
        }
    }
    @Test fun unsafeOperationsAndOldWorkersNeverExposeCancel() {
        for (kind in listOf("file", "torrent")) for (action in listOf("archive", "restore")) {
            val target = if (kind == "file" && action == "archive") "/fixture" else if (action == "archive") CLOUD_HASH else CLOUD_ARCHIVE
            val work = CloudWork(kind, listOf(action, target))
            assertFalse(CloudTask(CLOUD_REQUEST, work, "running").canCancel)
            assertTrue(CloudTask(CLOUD_REQUEST, work, "running", cancelSupported = true).canCancel)
            assertFalse(CloudTask(CLOUD_REQUEST, work, "succeeded", cancelSupported = true).canCancel)
        }
        for (action in listOf("release", "recover", "space", "inspect", "relink")) {
            assertFalse(CloudTask(CLOUD_REQUEST, CloudWork("torrent", listOf(action, CLOUD_ARCHIVE)), "running", cancelSupported = true).canCancel)
        }
        assertTrue(cloudCommand(CloudQuery.Cancel(CLOUD_REQUEST)).contains("cancel $CLOUD_REQUEST"))
        assertThrows(IllegalArgumentException::class.java) { cloudCommand(CloudQuery.Cancel("myct-core.service")) }
    }
}
