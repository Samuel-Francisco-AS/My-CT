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
            val opacity = Animatable(1f)
            nav.request(AppSection.Ide)
            val transition = launch { animateSectionChange(nav, opacity) { true } }
            frames.tick(); frames.tick(); frames.tick()
            assertEquals(AppSection.Home, nav.displayed)
            assertTrue(opacity.value > 0f && opacity.value < 1f)
            frames.finish(transition)
            assertEquals(AppSection.Ide, nav.displayed)
            assertEquals(1f, opacity.value)
        }
    }

    @Test fun retargetDuringExitSkipsTheOldDestination() {
        val frames = Frames()
        runBlocking(frames.context) {
            val nav = SectionNavigation(AppSection.Home)
            val opacity = Animatable(1f)
            nav.request(AppSection.Ide)
            val old = launch { animateSectionChange(nav, opacity) { true } }
            frames.tick(); frames.tick()
            nav.request(AppSection.Terminal)
            old.cancelAndJoin() // LaunchedEffect's key changed.
            val latest = launch { animateSectionChange(nav, opacity) { true } }
            while (nav.displayed == AppSection.Home && !latest.isCompleted) frames.tick()
            assertEquals(AppSection.Terminal, nav.displayed)
            frames.finish(latest)
            assertEquals(1f, opacity.value)
        }
    }

    @Test fun requestJustBeforeCommitAlsoUsesTheLatestTarget() {
        val frames = Frames()
        runBlocking(frames.context) {
            val nav = SectionNavigation(AppSection.Home)
            val opacity = Animatable(1f)
            nav.request(AppSection.Ide)
            val transition = launch { animateSectionChange(nav, opacity) { true } }
            frames.tick(); frames.tick()
            // Models a click before recomposition cancels the old effect.
            nav.request(AppSection.Files)
            frames.finish(transition)
            assertEquals(AppSection.Files, nav.displayed)
        }
    }

    @Test fun returningToDisplayedScreenDuringExitRestoresOpacity() {
        val frames = Frames()
        runBlocking(frames.context) {
            val nav = SectionNavigation(AppSection.Terminal)
            val opacity = Animatable(1f)
            nav.request(AppSection.Files)
            val old = launch { animateSectionChange(nav, opacity) { true } }
            frames.tick(); frames.tick()
            nav.request(AppSection.Terminal)
            old.cancelAndJoin()
            val restored = launch { animateSectionChange(nav, opacity) { true } }
            frames.finish(restored)
            assertEquals(AppSection.Terminal, nav.displayed)
            assertEquals(1f, opacity.value)
        }
    }

    @Test fun drawerBlocksSwapAndReopeningDuringExitAbortsCommit() {
        val frames = Frames()
        runBlocking(frames.context) {
            val nav = SectionNavigation(AppSection.Home)
            val opacity = Animatable(1f)
            nav.request(AppSection.Vault)
            frames.finish(launch { animateSectionChange(nav, opacity) { false } })
            assertEquals(AppSection.Home, nav.displayed)
            assertTrue(nav.protectCapture)
            var ready = true
            val closing = launch { animateSectionChange(nav, opacity) { ready } }
            frames.tick(); frames.tick()
            ready = false
            frames.finish(closing)
            assertEquals(AppSection.Home, nav.displayed)
            frames.finish(launch { animateSectionChange(nav, opacity) { true } })
            assertEquals(AppSection.Vault, nav.displayed)
            assertTrue(nav.protectCapture)
        }
    }

    @Test fun vaultProtectionSurvivesInterruptedEntryAndExitUntilSettled() {
        val frames = Frames()
        runBlocking(frames.context) {
            val nav = SectionNavigation(AppSection.Home)
            val opacity = Animatable(1f)
            nav.request(AppSection.Vault)
            assertTrue(nav.protectCapture)
            val enter = launch { animateSectionChange(nav, opacity) { true } }
            repeat(8) { frames.tick() }
            assertEquals(AppSection.Vault, nav.displayed)
            assertTrue(opacity.value < 1f)
            nav.request(AppSection.Files)
            enter.cancelAndJoin()
            val exit = launch { animateSectionChange(nav, opacity) { true } }
            while (nav.displayed == AppSection.Vault && !exit.isCompleted) frames.tick()
            assertEquals(AppSection.Files, nav.displayed)
            assertTrue(nav.protectCapture)
            nav.request(AppSection.Home)
            exit.cancelAndJoin()
            assertTrue(nav.protectCapture)
            frames.finish(launch { animateSectionChange(nav, opacity) { true } })
            assertFalse(nav.protectCapture)
        }
    }

    @Test fun zeroMotionScaleCompletesWithoutDelayAndRapidRouteReturnsHome() {
        val frames = Frames(0f)
        runBlocking(frames.context) {
            val nav = SectionNavigation(AppSection.Home)
            val opacity = Animatable(1f)
            listOf(AppSection.Ide, AppSection.Terminal, AppSection.Vault, AppSection.Home).forEach {
                nav.request(it)
                val before = frames.count
                frames.finish(launch { animateSectionChange(nav, opacity) { true } })
                assertTrue("Scale zero must skip both tweens", frames.count - before <= 3)
                assertEquals(it, nav.displayed)
                assertEquals(1f, opacity.value)
            }
            assertFalse(nav.protectCapture)
        }
    }
}
