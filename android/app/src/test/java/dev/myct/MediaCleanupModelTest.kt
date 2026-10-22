package dev.myct

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MediaCleanupModelTest {
    private val path = "Series/Fixture/Season 01/E01.mkv"
    private val selection = CleanupSelection("storage", listOf(path))
    private val fingerprint = "a".repeat(64)
    private fun plan(pathValue: String = path) = """{"version":2,"mode":"storage","paths":["$pathValue"],
        "targets":[{"path":"/home/fixture/Media/$pathValue"}],"selected":1,"bytes":1,
        "torrents":[],"blocked":[],"can_apply":true,"fingerprint":"$fingerprint"}"""
    private fun await(predicate: () -> Boolean) = runBlocking {
        withTimeout(3000) { while (!predicate()) delay(5) }
    }

    @Test fun onlyMatchingVisiblePlanCanBeAppliedAndCannotBeReplayed() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val actions = mutableListOf<MediaAction>()
        val model = MediaModel(scope) { _, action ->
            actions.add(action)
            when (action) {
                is MediaAction.CleanupPreview -> plan()
                is MediaAction.CleanupApply -> """{"removed_links":1,"removed_torrents":0}"""
                MediaAction.CleanupList -> """{"files":[]}"""
                MediaAction.LibraryList -> """{"groups":[]}"""
                MediaAction.List -> """{"links":{}}"""
                else -> error("Unexpected action")
            }
        }
        try {
            model.attach(SshConnection())
            model.previewCleanup(selection); await { !model.busy }
            assertNotNull(model.cleanupPlan)
            model.applyCleanup(selection.copy(mode = "links"), fingerprint)
            model.applyCleanup(selection, "b".repeat(64))
            assertEquals(0, actions.count { it is MediaAction.CleanupApply })
            model.applyCleanup(selection, fingerprint); await { !model.busy }
            model.applyCleanup(selection, fingerprint)
            assertEquals(1, actions.count { it is MediaAction.CleanupApply })
        } finally { scope.cancel() }
    }

    @Test fun navigationOrBackgroundRevokesLatePreviewAndReconnectDoesNotRestoreIt() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var applies = 0
        val model = MediaModel(scope) { _, action ->
            if (action is MediaAction.CleanupApply) applies++
            entered.countDown(); release.await(2, TimeUnit.SECONDS); plan()
        }
        try {
            model.attach(SshConnection()); model.previewCleanup(selection)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            model.invalidateCleanup(); release.countDown(); await { !model.busy }
            assertNull(model.cleanupPlan)
            model.applyCleanup(selection, fingerprint)
            model.attach(null); model.attach(SshConnection())
            model.applyCleanup(selection, fingerprint)
            assertEquals(0, applies)
        } finally { release.countDown(); scope.cancel() }
    }

    @Test fun differentServerSelectionCannotBecomeAnAuthorizedPlan() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val model = MediaModel(scope) { _, _ -> plan("Series/Fixture/Season 01/E02.mkv") }
        try {
            model.attach(SshConnection()); model.previewCleanup(selection); await { !model.busy }
            assertNull(model.cleanupPlan); assertTrue(model.stale)
        } finally { scope.cancel() }
    }
}
