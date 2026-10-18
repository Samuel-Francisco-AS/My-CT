package dev.myct

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SectionNavigationTest {
    private class Frames(scale: Float = 1f) {
        val clock = BroadcastFrameClock()
        val context = clock + object : MotionDurationScale { override val scaleFactor = scale }
        private var time = 0L
        var count = 0
            private set
        suspend fun tick() {
            yield()
            clock.sendFrame(time)
            time += 16_000_000L
            count++
            yield()
        }
        suspend fun finish(job: Job) {
            repeat(80) { if (!job.isCompleted) tick() }
            assertTrue("Animation must finish without a real delay", job.isCompleted)
            job.join()
        }
    }

    @Test fun homeGroupsAreOrderedMonotonicAndFinishTogetherWithoutDelay() {
        for (group in 0..4) {
            assertEquals(0f, homeGroupProgress(0f, group))
            assertEquals(1f, homeGroupProgress(1f, group))
            assertTrue(homeGroupProgress(0.5f, group) > 0f)
            for (step in 1..100) assertTrue(homeGroupProgress(step / 100f, group) >=
                homeGroupProgress((step - 1) / 100f, group))
        }
        for (group in 1..4) assertTrue(homeGroupProgress(0.2f, group - 1) >= homeGroupProgress(0.2f, group))
    }

    @Test fun directionsReverseAndSignedTravelKeepsBackgroundContinuous() {
        assertEquals(androidx.compose.ui.geometry.Offset(0f, 1f), navigationDirection(AppSection.Home, AppSection.Ide))
        assertEquals(androidx.compose.ui.geometry.Offset(0f, -1f), navigationDirection(AppSection.Ide, AppSection.Home))
        val forward = navigationDirection(AppSection.Terminal, AppSection.Files)
        val backward = navigationDirection(AppSection.Files, AppSection.Terminal)
        assertEquals(-forward.x, backward.x, 0f)
        assertEquals(-forward.y, backward.y, 0f)
        assertEquals(0f, navigationAlpha(-1f))
        assertEquals(0f, navigationAlpha(1f))
        assertEquals(1f, navigationAlpha(0f))
        assertEquals(navigationAlpha(-0.4f), navigationAlpha(0.4f))
    }

    @Test fun duplicateRequestsDoNotNavigateAndLatestIntentWins() {
        val nav = SectionNavigation(AppSection.Home)
        assertFalse(nav.request(AppSection.Home))
        assertTrue(nav.request(AppSection.Ide))
        assertFalse(nav.request(AppSection.Ide))
        nav.request(AppSection.Terminal)
        assertEquals(AppSection.Home, nav.displayed)
        nav.showRequested()
        assertEquals(AppSection.Terminal, nav.displayed)
    }

    @Test fun restoredDestinationStartsSettledAndVaultRemainsProtected() {
        AppSection.entries.forEach { section ->
            val restored = checkNotNull(SectionNavigation.Saver.restore(section.name))
            assertEquals(section, restored.displayed)
            assertEquals(section, restored.requested)
            assertEquals(section == AppSection.Vault, restored.protectCapture)
        }
    }

    @Test fun outgoingScreenRemainsUntilInvisibleAndOneDestinationEnters() {
        val frames = Frames()
        runBlocking(frames.context) {
            val nav = SectionNavigation(AppSection.Home)
            val travel = Animatable(0f)
            nav.request(AppSection.Ide)
            val transition = launch { animateSectionChange(nav, travel) { true } }
            frames.tick(); frames.tick(); frames.tick()
            assertEquals(AppSection.Home, nav.displayed)
            assertTrue(travel.value > -1f && travel.value < 0f)
            frames.finish(transition)
            assertEquals(AppSection.Ide, nav.displayed)
            assertEquals(0f, travel.value)
        }
    }

    @Test fun retargetDuringExitSkipsTheOldDestination() {
        val frames = Frames()
        runBlocking(frames.context) {
            val nav = SectionNavigation(AppSection.Home)
            val travel = Animatable(0f)
            nav.request(AppSection.Ide)
            val old = launch { animateSectionChange(nav, travel) { true } }
            frames.tick(); frames.tick()
            nav.request(AppSection.Terminal)
            old.cancelAndJoin() // LaunchedEffect's key changed.
            val latest = launch { animateSectionChange(nav, travel) { true } }
            while (nav.displayed == AppSection.Home && !latest.isCompleted) frames.tick()
            assertEquals(AppSection.Terminal, nav.displayed)
            frames.finish(latest)
            assertEquals(0f, travel.value)
        }
    }

    @Test fun requestJustBeforeCommitAlsoUsesTheLatestTarget() {
        val frames = Frames()
        runBlocking(frames.context) {
            val nav = SectionNavigation(AppSection.Home)
            val travel = Animatable(0f)
            nav.request(AppSection.Ide)
            val transition = launch { animateSectionChange(nav, travel) { true } }
            frames.tick(); frames.tick()
            // Models a click before recomposition cancels the old effect.
            nav.request(AppSection.Files)
            frames.finish(transition)
            assertEquals(AppSection.Files, nav.displayed)
        }
    }

    @Test fun returningToDisplayedScreenDuringExitRestoresPositionAndOpacity() {
        val frames = Frames()
        runBlocking(frames.context) {
            val nav = SectionNavigation(AppSection.Terminal)
            val travel = Animatable(0f)
            nav.request(AppSection.Files)
            val old = launch { animateSectionChange(nav, travel) { true } }
            frames.tick(); frames.tick()
            nav.request(AppSection.Terminal)
            old.cancelAndJoin()
            val restored = launch { animateSectionChange(nav, travel) { true } }
            frames.finish(restored)
            assertEquals(AppSection.Terminal, nav.displayed)
            assertEquals(0f, travel.value)
        }
    }

    @Test fun drawerBlocksSwapAndReopeningDuringExitAbortsCommit() {
        val frames = Frames()
        runBlocking(frames.context) {
            val nav = SectionNavigation(AppSection.Home)
            val travel = Animatable(0f)
            nav.request(AppSection.Vault)
            frames.finish(launch { animateSectionChange(nav, travel) { false } })
            assertEquals(AppSection.Home, nav.displayed)
            assertTrue(nav.protectCapture)
            var ready = true
            val closing = launch { animateSectionChange(nav, travel) { ready } }
            frames.tick(); frames.tick()
            ready = false
            frames.finish(closing)
            assertEquals(AppSection.Home, nav.displayed)
            frames.finish(launch { animateSectionChange(nav, travel) { true } })
            assertEquals(AppSection.Vault, nav.displayed)
            assertTrue(nav.protectCapture)
        }
    }

    @Test fun vaultProtectionSurvivesInterruptedEntryAndExitUntilSettled() {
        val frames = Frames()
        runBlocking(frames.context) {
            val nav = SectionNavigation(AppSection.Home)
            val travel = Animatable(0f)
            nav.request(AppSection.Vault)
            assertTrue(nav.protectCapture)
            val enter = launch { animateSectionChange(nav, travel) { true } }
            while (nav.displayed != AppSection.Vault && !enter.isCompleted) frames.tick()
            frames.tick(); frames.tick()
            assertEquals(AppSection.Vault, nav.displayed)
            assertTrue(travel.value > 0f && travel.value < 1f)
            nav.request(AppSection.Files)
            enter.cancelAndJoin()
            val exit = launch { animateSectionChange(nav, travel) { true } }
            while (nav.displayed == AppSection.Vault && !exit.isCompleted) frames.tick()
            assertEquals(AppSection.Files, nav.displayed)
            assertTrue(nav.protectCapture)
            nav.request(AppSection.Home)
            exit.cancelAndJoin()
            assertTrue(nav.protectCapture)
            frames.finish(launch { animateSectionChange(nav, travel) { true } })
            assertFalse(nav.protectCapture)
        }
    }

    @Test fun zeroMotionScaleCompletesWithoutDelayAndRapidRouteReturnsHome() {
        val frames = Frames(0f)
        runBlocking(frames.context) {
            val nav = SectionNavigation(AppSection.Home)
            val travel = Animatable(0f)
            listOf(AppSection.Ide, AppSection.Terminal, AppSection.Vault, AppSection.Home).forEach {
                nav.request(it)
                val before = frames.count
                frames.finish(launch { animateSectionChange(nav, travel) { true } })
                assertTrue("Scale zero must skip both tweens", frames.count - before <= 3)
                assertEquals(it, nav.displayed)
                assertEquals(0f, travel.value)
            }
            assertFalse(nav.protectCapture)
        }
    }
}
