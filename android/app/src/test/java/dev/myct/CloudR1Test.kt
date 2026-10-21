package dev.myct

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Real model, controlled clock and fake persistent task; never executes release on disk. */
class CloudR1Test {
    private val paths = (1..13).map { "Series/Fixture/S01/E%02d.mkv".format(it) }
    private fun plan(selected: List<String> = emptyList()) = cloudPlan(selected).put("library", JSONArray(paths.map {
        JSONObject().put("path", it).put("present", true).put("linked_to_package", true)
    }))
    private inner class Fixture : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var now = CLOUD_NOW
        @Volatile var row = cloudTaskJson(CloudWork("torrent", listOf("space", CLOUD_ARCHIVE)), result = plan())
        val starts = CopyOnWriteArrayList<CloudQuery.Start>()
        val calls = CopyOnWriteArrayList<CloudQuery>()
        val model = CloudModel(scope, { now }) { _, query ->
            calls += query
            when (query) {
                CloudQuery.Snapshot -> cloudSnapshot(row)
                is CloudQuery.Task -> JSONObject().put("ok", true).put("task", row).toString()
                is CloudQuery.Start -> {
                    starts += query
                    row = cloudTaskJson(query.work, "running").put("id", query.id)
                    JSONObject().put("ok", true).put("task", row).toString()
                }
                else -> error("space is non-cancellable")
            }
        }
        fun waitIdle() = runBlocking { withTimeout(3000) { while (model.busy) delay(5) } }
        init {
            model.attach(SshConnection()); model.select(CLOUD_ARCHIVE); model.refresh(); waitIdle()
            model.viewTask(model.tasks.single()); waitIdle()
            paths.forEach { model.toggleLink(CLOUD_ARCHIVE, it) }
        }
        fun start() { model.start(CloudWork("torrent", listOf("space", CLOUD_ARCHIVE) + paths.flatMap { listOf("--remove-library", it) })); waitIdle() }
        fun complete(result: JSONObject = plan(paths)) {
            row = JSONObject(row.toString()).put("state", "succeeded").put("result", result)
            model.refresh(); waitIdle()
        }
        override fun close() { scope.cancel() }
    }
    @Test fun manualRefreshDuringAcceptedSpaceKeepsThirteenLinksAndCanAuthorizeAtCompletion() = Fixture().use { f ->
        val m = f.model
        f.start()
        assertFalse(m.busy); assertTrue(m.tasks.single { it.active }.active)
        assertEquals(paths, f.starts.single().work.librarySelection)
        val snapshots = f.calls.count { it == CloudQuery.Snapshot }
        val taskReads = f.calls.count { it is CloudQuery.Task }
        repeat(2) {
            f.now += 100000
            m.refreshFromUser(); f.waitIdle() // Exactly the action wired to Atualizar.
            assertEquals(13, m.selectedLinks(CLOUD_ARCHIVE).size)
            assertTrue(m.tasks.single { it.active }.active); assertNull(m.space)
        }
        assertEquals(snapshots, f.calls.count { it == CloudQuery.Snapshot })
        assertEquals(taskReads + 2, f.calls.count { it is CloudQuery.Task })
        f.now += 31400
        f.complete() // succeeded, same 13 paths, can_release=true, blockers=[]
        val p = m.space!!
        assertEquals(paths, p.selected); assertTrue(p.canRelease); assertTrue(p.blockers.isEmpty())
        assertEquals(13, m.selectedLinks(CLOUD_ARCHIVE).size)
        assertTrue(m.canRelease(p, paths))
        assertEquals(1, f.starts.size) // No second space request and no release execution.
    }
    @Test fun manualRefreshCannotUndoLegitimateInvalidationOrExpiryDuringSpace() {
        for (event in listOf("navigation", "background", "reconnect", "expiry", "other-package")) Fixture().use { f ->
            val m = f.model; f.start()
            when (event) {
                "navigation" -> { m.select(null); m.select(CLOUD_ARCHIVE) }
                "background" -> m.invalidatePreview()
                "reconnect" -> { m.attach(null); m.attach(SshConnection()); m.refresh(); f.waitIdle() }
                "expiry" -> f.now += 600001
                "other-package" -> m.select("e".repeat(64))
            }
            m.refreshFromUser(); f.waitIdle()
            assertTrue(m.tasks.single { it.active }.active)
            f.complete()
            if (event == "other-package") assertNull(m.space)
            m.viewTask(m.tasks.single()); f.waitIdle()
            assertNotNull(m.space)
            assertFalse(event, m.canRelease(m.space!!, paths))
            assertEquals(1, f.starts.size)
        }
    }
    @Test fun selectionEditsAreBlockedDuringSpaceAndAnActualEditStillRevokesAfterCompletion() = Fixture().use { f ->
        val m = f.model; f.start()
        assertFalse(m.actionsEnabled)
        m.toggleLink(CLOUD_ARCHIVE, paths.first()) // Disabled by both model and UI while active.
        assertEquals(paths.toSet(), m.selectedLinks(CLOUD_ARCHIVE))
        m.refreshFromUser(); f.waitIdle(); f.complete()
        assertTrue(m.canRelease(m.space!!, paths))
        m.toggleLink(CLOUD_ARCHIVE, paths.first())
        assertEquals(12, m.selectedLinks(CLOUD_ARCHIVE).size); assertNull(m.space)
        m.refreshFromUser(); f.waitIdle()
        m.viewTask(m.tasks.single()); f.waitIdle()
        assertFalse(m.canRelease(m.space!!, m.selectedLinks(CLOUD_ARCHIVE).sorted()))
        assertEquals(1, f.starts.size)
    }
    @Test fun manualRefreshAfterCompletedPreviewRevokesItAndHistoryNeverRearmsConsent() = Fixture().use { f ->
        val m = f.model; f.start(); f.complete()
        val completed = m.space!!
        assertTrue(m.canRelease(completed, paths))
        m.refreshFromUser(); f.waitIdle()
        assertEquals(13, m.selectedLinks(CLOUD_ARCHIVE).size)
        assertNull(m.space); assertFalse(m.canRelease(completed, paths))
        m.viewTask(m.tasks.single()); f.waitIdle()
        assertNotNull(m.space); assertFalse(m.canRelease(m.space!!, paths))
        assertEquals(1, f.starts.size)
    }
    @Test fun thirteenLinksRemainAfterLongQueryAndRecreatedViewAndConfirmationIsExact() = Fixture().use { f ->
        val m = f.model
        assertEquals(paths.toSet(), m.selectedLinks(CLOUD_ARCHIVE))
        f.start()
        assertEquals(paths, f.starts.single().work.librarySelection)
        f.now += 231400 // Expensive Fedora work is simulated; token is issued at completion.
        val token = "${f.now / 1000}-" + "d".repeat(64)
        f.complete(plan(paths).put("preview", token).put("confirmation", token).put("library_confirmation", token))
        // A new Composable reads these values from the same activity model, with no remember state.
        assertEquals(13, m.selectedLinks(CLOUD_ARCHIVE).size)
        assertEquals(13, m.libraryChoices(CLOUD_ARCHIVE).size)
        val p = m.space!!
        assertEquals(paths, p.selected)
        assertTrue(m.canRelease(p, paths))
        m.release(p, paths, true); f.waitIdle()
        val release = f.starts.last().work
        assertEquals("release", release.action)
        assertEquals(paths, release.librarySelection)
        assertTrue(release.args.contains("--confirm-library"))
    }
    @Test fun navigationAndBackgroundPreserveVisualSelectionButRevokePendingAuthorization() {
        for (event in listOf("navigation", "background")) Fixture().use { f ->
            val m = f.model; f.start()
            when (event) {
                "navigation" -> { m.select(null); m.select("e".repeat(64)); assertTrue(m.selectedLinks("e".repeat(64)).isEmpty()); m.select(CLOUD_ARCHIVE) }
                else -> m.invalidatePreview() // ON_STOP / onDispose still revoke the request identity.
            }
            f.complete()
            assertEquals(13, m.selectedLinks(CLOUD_ARCHIVE).size)
            assertNotNull(m.space); assertFalse(m.canRelease(m.space!!, paths))
            // Reopening the old result must not re-arm consent.
            m.viewTask(m.tasks.single()); f.waitIdle()
            assertFalse(m.canRelease(m.space!!, paths))
            f.start(); f.complete(); assertTrue(m.canRelease(m.space!!, paths))
        }
    }
    @Test fun reconnectAndNewProcessReadProgressWithoutRestoringDeletionConsent() = Fixture().use { f ->
        f.start()
        f.row.put("progress", cloudProgressJson("local_read").put("metric", "read").put("scope", "phase"))
        val m = f.model
        m.attach(null); m.attach(SshConnection()); m.refresh(); f.waitIdle()
        assertEquals("local_read", m.tasks.single().progress!!.phase)
        assertFalse(m.tasks.single().canCancel)
        assertTrue(m.selectedLinks(CLOUD_ARCHIVE).isEmpty())
        f.complete(); m.viewTask(m.tasks.single()); f.waitIdle()
        assertFalse(m.canRelease(m.space!!, paths))
        val fresh = CloudModel(f.scope, { f.now }) { _, _ -> cloudSnapshot(f.row) }
        fresh.attach(SshConnection())
        assertTrue(fresh.selectedLinks(CLOUD_ARCHIVE).isEmpty()); assertNull(fresh.space)
    }
    @Test fun divergenceBlockedResultsExpiryChangedSelectionAndSeparateConsentNeverRelease() {
        for (case in listOf("request-result", "wrong-package", "blocked", "can-release", "expiry", "selection", "no-consent")) Fixture().use { f ->
            f.start(); f.model.refreshFromUser(); f.waitIdle()
            val result = when (case) {
                "request-result" -> plan(paths.dropLast(1))
                "wrong-package" -> plan(paths).put("id", "e".repeat(64))
                "blocked" -> plan(paths).put("blockers", JSONArray().put("UNKNOWN_LINKS"))
                "can-release" -> plan(paths).put("can_release", false)
                else -> plan(paths)
            }
            f.complete(result)
            if (case in listOf("request-result", "wrong-package")) {
                assertNull(f.model.space); assertTrue(f.model.message.contains("divergente"))
            } else {
                val p = f.model.space!!
                if (case == "expiry") f.now += 600001
                if (case == "selection") f.model.toggleLink(CLOUD_ARCHIVE, paths.first())
                f.model.release(p, paths, case != "no-consent"); f.waitIdle()
            }
            assertEquals(case, 1, f.starts.size)
        }
    }
    @Test fun switchingPackagesWhileTaskFinishesCannotReopenOtherPackagePreview() = Fixture().use { f ->
        f.start(); f.model.select("e".repeat(64)); f.complete()
        assertNull(f.model.space); assertEquals("e".repeat(64), f.model.selected)
        assertEquals(13, f.model.selectedLinks(CLOUD_ARCHIVE).size)
        f.model.select(CLOUD_ARCHIVE); f.model.viewTask(f.model.tasks.single()); f.waitIdle()
        assertFalse(f.model.canRelease(f.model.space!!, paths))
    }
}
