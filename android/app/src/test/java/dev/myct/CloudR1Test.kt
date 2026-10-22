package dev.myct

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Real model and inode projection; fake transport never touches a personal server. */
class CloudR1Test {
    private val paths = (1..13).map { "Series/Fixture/S01/E%02d.mkv".format(it) }
    private inner class Fixture : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var now = CLOUD_NOW
        @Volatile var row = cloudTaskJson(CloudWork("torrent", listOf("space", CLOUD_ARCHIVE)),
            result = cloudPlan(choices = paths))
        val starts = CopyOnWriteArrayList<CloudQuery.Start>()
        val calls = CopyOnWriteArrayList<CloudQuery>()
        var entryState = "verified"
        val model = CloudModel(scope, { now }) { _, query ->
            calls += query
            when (query) {
                CloudQuery.Snapshot -> cloudSnapshot(row, entryState = entryState)
                is CloudQuery.Task -> JSONObject().put("ok", true).put("task", row).toString()
                is CloudQuery.Start -> {
                    starts += query
                    row = cloudTaskJson(query.work, "running").put("id", query.id)
                    JSONObject().put("ok", true).put("task", row).toString()
                }
                else -> error("No cancellation in this fixture")
            }
        }
        fun waitIdle() = runBlocking { withTimeout(3000) { while (model.busy) delay(5) } }
        init {
            model.attach(SshConnection()); model.select(CLOUD_ARCHIVE); model.refresh(); waitIdle()
            model.viewTask(model.tasks.single()); waitIdle()
        }
        fun start() { model.consultSpace(force = true); waitIdle() }
        fun complete(result: JSONObject = cloudPlan(choices = paths)) {
            row = JSONObject(row.toString()).put("state", "succeeded").put("result", result)
            model.refresh(); waitIdle()
        }
        fun load() { start(); complete() }
        override fun close() { scope.cancel() }
    }
    @Test fun selectionProjectsExactPathsAndBlocksWithoutAnyTransportOrSecondInventory() = Fixture().use { f ->
        val m = f.model; f.load()
        val initial = m.space!!; val calls = f.calls.size
        paths.forEach { m.toggleLink(CLOUD_ARCHIVE, it) }
        val chosen = m.space!!
        assertEquals(initial.token, chosen.token)
        assertEquals(paths, chosen.selected)
        assertEquals(80L + 13 * 20L, chosen.estimate)
        assertEquals(initial.paths.size + 13, chosen.paths.size)
        assertTrue(m.canRelease(chosen, paths))
        m.toggleLink(CLOUD_ARCHIVE, paths.first())
        assertFalse(m.space!!.paths.contains("/home/user/Media/${paths.first()}"))
        assertEquals(chosen.estimate - 20, m.space!!.estimate)
        repeat(3) { m.consultSpace() }
        assertEquals(calls, f.calls.size); assertEquals(1, f.starts.size)
    }
    @Test fun dialogFreezesSelectionAndAnEditRevokesOnlyTheDialog() = Fixture().use { f ->
        val m = f.model; f.load(); m.toggleLink(CLOUD_ARCHIVE, paths.first())
        val frozen = m.space!!; m.beginRelease(frozen)
        assertSame(frozen, m.confirmation)
        m.toggleLink(CLOUD_ARCHIVE, paths.last())
        assertNull(m.confirmation); assertNotNull(m.space)
        m.release(frozen, frozen.selected, true); f.waitIdle()
        assertEquals(1, f.starts.size)
        val current = m.space!!
        m.start(current.release(true, f.now)); f.waitIdle(); assertEquals(1, f.starts.size)
        m.beginRelease(current); m.release(current, current.selected, true); f.waitIdle()
        val work = f.starts.last().work
        assertEquals("release", work.action)
        assertEquals(listOf(paths.first(), paths.last()), work.librarySelection)
        assertTrue(work.args.contains("--confirm-library"))
    }
    @Test fun backgroundNavigationAndRefreshKeepInventoryButCannotReplayAnOpenDialog() = Fixture().use { f ->
        val m = f.model; f.load(); paths.forEach { m.toggleLink(CLOUD_ARCHIVE, it) }
        val old = m.space!!; m.beginRelease(old); m.invalidatePreview() // ON_STOP/onDispose.
        assertNull(m.confirmation); assertSame(old, m.space)
        m.release(old, paths, true); f.waitIdle(); assertEquals(1, f.starts.size)
        m.select(null); m.select(CLOUD_ARCHIVE)
        val calls = f.calls.size; m.consultSpace()
        assertEquals(calls, f.calls.size); assertEquals(paths, m.space!!.selected)
        assertTrue(m.canRelease(m.space!!, paths)) // A fresh user dialog may be opened locally.
        m.refreshFromUser(); f.waitIdle()
        assertNotNull(m.space); assertNull(m.confirmation); assertEquals(1, f.starts.size)
    }
    @Test fun aLateOrHistoricalResponseCannotGrantConsentOrReplaceLiveInventory() = Fixture().use { f ->
        val m = f.model; f.start(); m.invalidatePreview(); f.complete()
        assertNotNull(m.space); assertFalse(m.canRelease(m.space!!, emptyList()))
        m.beginRelease(m.space!!); assertNull(m.confirmation)
        f.load(); val live = m.space!!
        m.viewTask(m.tasks.single()); f.waitIdle()
        assertSame(live, m.space); assertTrue(m.canRelease(live, emptyList()))
        assertNull(m.confirmation) // Reading history never opens a dialog.
        f.row = JSONObject(f.row.toString()).put("state", "failed").put("result",
            JSONObject().put("ok", false).put("error", JSONObject().put("code", "NETWORK")))
        m.viewTask(parseCloudTask(f.row)); f.waitIdle()
        assertSame(live, m.space) // Even a failed historical response cannot erase the live cache.
    }
    @Test fun incompleteInventoryAndUnprovenSelectedLinkCannotAuthorizeSubset() {
        for (failure in listOf("incomplete", "unproven", "blocker")) Fixture().use { f ->
            f.start()
            val reply = cloudPlan(choices = paths)
            val inventory = reply.getJSONObject("inventory")
            when (failure) {
                "incomplete" -> inventory.put("library_complete", false)
                "unproven" -> inventory.getJSONArray("library").getJSONObject(0).put("managed", false)
                else -> inventory.getJSONArray("blockers").put("UNKNOWN_LINKS")
            }
            f.complete(reply)
            f.model.toggleLink(CLOUD_ARCHIVE, paths.first())
            val plan = f.model.space!!
            assertEquals(13, plan.library.size); assertEquals(listOf(paths.first()), plan.selected)
            assertFalse(f.model.canRelease(plan, plan.selected))
            f.model.beginRelease(plan); assertNull(f.model.confirmation)
            assertEquals(1, f.starts.size)
        }
    }
    @Test fun expiryDoesNotExtendOnSelectionAndExplicitRefreshLoadsOnceAgain() = Fixture().use { f ->
        val m = f.model; f.load(); val deadline = m.space!!.expiresAt
        f.now += 599000; m.toggleLink(CLOUD_ARCHIVE, paths.first())
        assertEquals(deadline, m.space!!.expiresAt)
        f.now += 1001
        assertFalse(m.canRelease(m.space!!, m.space!!.selected))
        m.consultSpace(); f.waitIdle(); assertEquals(2, f.starts.size)
    }
    @Test fun reconnectAndRelevantCatalogChangeInvalidateCachedInventory() {
        for (event in listOf("connection", "catalog")) Fixture().use { f ->
            val m = f.model; f.load()
            if (event == "connection") { m.attach(null); m.attach(SshConnection()) }
            else f.entryState = "failed"
            m.refresh(); f.waitIdle()
            assertNull(m.space); assertNull(m.confirmation)
            assertEquals(1, f.starts.size)
        }
    }
    @Test fun completionUsesAlreadyReceivedResultWithoutAnotherTaskQuery() = Fixture().use { f ->
        f.start()
        val before = f.calls.count { it is CloudQuery.Task }
        f.complete()
        assertEquals(before + 1, f.calls.count { it is CloudQuery.Task })
        assertTrue(f.model.canRelease(f.model.space!!, emptyList()))
    }
    @Test fun manualRefreshDuringSpaceFollowsTheSameJob() = Fixture().use { f ->
        f.start(); f.model.refreshFromUser(); f.waitIdle()
        assertTrue(f.model.tasks.single { it.active }.active)
        f.complete(); assertEquals(1, f.starts.size)
        assertTrue(f.model.canRelease(f.model.space!!, emptyList()))
    }
    @Test fun oldContractIsReadOnlyAndIndependentRestoreDoesNotRequireSpace() = Fixture().use { f ->
        val legacy = cloudPlan().apply { remove("version"); remove("inventory") }
        f.start(); f.complete(legacy)
        assertNotNull(f.model.space); assertFalse(f.model.space!!.valid(f.now))
        f.model.start(CloudWork("torrent", listOf("restore", CLOUD_ARCHIVE))); f.waitIdle()
        assertEquals("restore", f.starts.last().work.action)
    }
    @Test fun unknownAliasesRetainBlocksAndCannotBePresentedAsReclaimable() {
        val raw = cloudPlan().apply {
            getJSONObject("inventory").getJSONArray("inodes").getJSONObject(1)
                .put("nlink", 3).put("unknown_links", 1)
            getJSONObject("inventory").getJSONArray("blockers").put("UNKNOWN_LINKS")
        }
        val plan = parseCloudSpace(raw, CLOUD_NOW).project(listOf(CLOUD_LIB))
        assertEquals(80L, plan.estimate); assertEquals(20L, plan.retained)
        assertFalse(plan.valid(CLOUD_NOW))
    }
}
