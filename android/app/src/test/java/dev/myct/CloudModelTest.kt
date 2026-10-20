package dev.myct

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CloudModelTest {
    private fun await(predicate: () -> Boolean) = runBlocking { withTimeout(3000) { while (!predicate()) delay(5) } }
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val work = CloudWork("torrent", listOf("space", CLOUD_ARCHIVE))

    @Test fun reconnectReadsPersistentJobAndNeverRepeatsStart() {
        val scope = scope(); val calls = java.util.concurrent.CopyOnWriteArrayList<CloudQuery>()
        var serverTask: JSONObject? = null
        val model = CloudModel(scope, { CLOUD_NOW }) { _, query ->
            calls += query
            when (query) {
                CloudQuery.Snapshot -> cloudSnapshot(serverTask)
                is CloudQuery.Start -> {
                    serverTask = cloudTaskJson(query.work, "running").put("id", query.id)
                    JSONObject().put("ok", true).put("task", serverTask).toString()
                }
                is CloudQuery.Cancel -> error("Unexpected cancellation")
                is CloudQuery.Task -> JSONObject().put("ok", true).put("task", serverTask).toString()
            }
        }
        try {
            model.attach(SshConnection()); model.refresh(); await { !model.busy }; assertTrue(model.actionsEnabled)
            model.start(work); await { !model.busy }; assertTrue(model.active); assertFalse(model.actionsEnabled)
            model.start(work); assertEquals(1, calls.count { it is CloudQuery.Start })
            model.attach(null); model.attach(SshConnection()); model.refresh(); await { !model.busy }
            assertTrue(model.active)
            serverTask!!.put("state", "succeeded").put("result", cloudPlan())
            model.refresh(); await { !model.busy }
            model.select(CLOUD_ARCHIVE); model.viewTask(model.tasks.single()); await { !model.busy }
            assertNotNull(model.space); assertTrue(model.canRelease(model.space!!, emptyList()))
            assertEquals(1, calls.count { it is CloudQuery.Start })
        } finally { scope.cancel() }
    }
    @Test fun previewIdentitySelectionExpiryAndLibraryConsentAreEnforcedInModel() {
        for (case in listOf("valid", "selection", "expired", "replaced", "library", "library-valid", "disconnect")) {
            val scope = scope(); var now = CLOUD_NOW
            val selected = if (case.startsWith("library")) listOf(CLOUD_LIB) else emptyList()
            val previewWork = CloudWork("torrent", listOf("space", CLOUD_ARCHIVE) + selected.flatMap { listOf("--remove-library", it) })
            val task = cloudTaskJson(previewWork, result = cloudPlan(selected))
            val starts = java.util.concurrent.CopyOnWriteArrayList<CloudQuery.Start>()
            val model = CloudModel(scope, { now }) { _, query -> when (query) {
                CloudQuery.Snapshot -> cloudSnapshot(task)
                is CloudQuery.Cancel -> error("Unexpected cancellation")
                is CloudQuery.Task -> JSONObject().put("ok", true).put("task", task).toString()
                is CloudQuery.Start -> { starts += query; JSONObject().put("ok", true).put("task", cloudTaskJson(query.work, "running").put("id", query.id)).toString() }
            } }
            try {
                model.attach(SshConnection()); model.select(CLOUD_ARCHIVE); model.refresh(); await { !model.busy }
                model.viewTask(model.tasks.single()); await { !model.busy }
                val plan = model.space!!
                when (case) {
                    "expired" -> now += 600001
                    "replaced" -> model.invalidatePreview()
                    "disconnect" -> model.attach(null)
                }
                model.release(plan, if (case == "selection") listOf(CLOUD_LIB) else selected, case == "library-valid")
                await { !model.busy }
                assertEquals(case, if (case in listOf("valid", "library-valid")) 1 else 0, starts.size)
                if (starts.isNotEmpty()) assertEquals("release", starts.single().work.action)
            } finally { scope.cancel() }
        }
    }
    @Test fun ambiguousSshStartCannotBeAutomaticallyDuplicated() {
        val scope = scope(); var starts = 0
        val model = CloudModel(scope, { CLOUD_NOW }) { _, query -> when (query) {
            CloudQuery.Snapshot -> cloudSnapshot()
            is CloudQuery.Start -> { starts++; throw java.io.IOException("network dropped") }
            else -> error("No automatic task mutation")
        } }
        try {
            model.attach(SshConnection()); model.refresh(); await { !model.busy }
            model.start(work); await { !model.busy }
            assertTrue(model.stale); assertFalse(model.actionsEnabled)
            model.start(work); assertEquals(1, starts)
            model.refresh(); await { !model.busy }; assertEquals(1, starts)
        } finally { scope.cancel() }
    }
    @Test fun disconnectInterruptsSshQueryWithoutPublishingOldState() {
        val scope = scope(); val entered = CountDownLatch(1); val closed = CountDownLatch(1)
        val model = CloudModel(scope, { CLOUD_NOW }) { _, _ ->
            entered.countDown()
            try { Thread.sleep(10000); cloudSnapshot() } finally { closed.countDown() }
        }
        try {
            model.attach(SshConnection()); model.refresh()
            assertTrue(entered.await(2, TimeUnit.SECONDS)); model.attach(null)
            assertTrue(closed.await(2, TimeUnit.SECONDS)); assertFalse(model.connected); assertFalse(model.busy)
            assertTrue(model.entries.isEmpty()); assertNull(model.space)
        } finally { scope.cancel() }
    }
    @Test fun busyCatalogUncertainJobAndFailedTaskBlockUnsafeActions() {
        val scope = scope()
        var task = cloudTaskJson(work, "uncertain")
        var catalogOk = false
        val model = CloudModel(scope, { CLOUD_NOW }) { _, query -> when (query) {
            CloudQuery.Snapshot -> cloudSnapshot(task, catalogOk = catalogOk)
            is CloudQuery.Cancel -> error("Unexpected cancellation")
            is CloudQuery.Task -> JSONObject().put("ok", true).put("task", task).toString()
            else -> error("Mutation must stay disabled")
        } }
        try {
            model.attach(SshConnection()); model.refresh(); await { !model.busy }
            assertFalse(model.actionsEnabled); assertTrue(model.message.contains("ocupado"))
            catalogOk = true; model.refresh(); await { !model.busy }; assertFalse(model.actionsEnabled)
            task = cloudTaskJson(work, "failed", JSONObject().put("ok", false).put("error", JSONObject().put("code", "STALE_PREVIEW")))
            model.refresh(); await { !model.busy }; model.viewTask(model.tasks.single()); await { !model.busy }
            assertNull(model.space); assertTrue(model.message.contains("prévia"))
        } finally { scope.cancel() }
    }
    @Test fun helperMissingAndMalformedResponsesAreVisibleAndNeverEnableActions() {
        for (failure in listOf(DownloadFailure("Helper ausente"), IllegalArgumentException("json"))) {
            val scope = scope(); val model = CloudModel(scope, { CLOUD_NOW }) { _, _ -> throw failure }
            try {
                model.attach(SshConnection()); model.refresh(); await { !model.busy }
                assertTrue(model.stale); assertFalse(model.actionsEnabled); assertTrue(model.message.isNotBlank())
            } finally { scope.cancel() }
        }
    }
    @Test fun newlyArchivedHashNavigatesToItsAuthoritativeCatalogId() {
        val scope = scope()
        val model = CloudModel(scope, { CLOUD_NOW }) { _, _ -> cloudSnapshot() }
        try {
            model.attach(SshConnection()); model.select(CLOUD_HASH)
            model.refresh(); await { !model.busy }
            assertEquals(CLOUD_ARCHIVE, model.selected)
            assertNull(model.inspection)
        } finally { scope.cancel() }
    }
    @Test fun reopenedSpaceResultNavigatesToDetailsAndInvalidInputNeverOpensSsh() {
        val scope = scope(); var starts = 0
        val task = cloudTaskJson(work, result = cloudPlan())
        val model = CloudModel(scope, { CLOUD_NOW }) { _, query -> when (query) {
            CloudQuery.Snapshot -> cloudSnapshot(task)
            is CloudQuery.Cancel -> error("Unexpected cancellation")
            is CloudQuery.Task -> JSONObject().put("ok", true).put("task", task).toString()
            is CloudQuery.Start -> { starts++; error("Invalid request must not reach SSH") }
        } }
        try {
            model.attach(SshConnection()); model.refresh(); await { !model.busy }
            assertNull(model.selected)
            model.viewTask(model.tasks.single()); await { !model.busy }
            assertEquals(CLOUD_ARCHIVE, model.selected); assertNotNull(model.space)
            model.start(CloudWork("file", listOf("archive", "/a/../b")))
            assertEquals(0, starts); assertTrue(model.message.contains("inválido"))
        } finally { scope.cancel() }
    }
}
