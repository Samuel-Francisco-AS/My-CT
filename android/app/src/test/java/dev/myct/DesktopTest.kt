package dev.myct

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

class DesktopTest {
    @Test fun tunnelIsExclusiveReusableAndDoesNotRemoveHealth() {
        var next = 24000; val removed = mutableListOf<Int>()
        val health = HealthForwarding({ 8765 }, { removed.add(it) })
        val owner = DesktopForwarding({ next++ }, { removed.add(it) })
        assertEquals(8765, health.port())
        val a = owner.acquire(); assertThrows(IllegalStateException::class.java) { owner.acquire() }
        a.close(); a.close(); val b = owner.acquire(); a.close()
        assertEquals(24001, b.port); assertEquals(listOf(24000), removed)
        owner.close(); b.close(); owner.close()
        assertEquals(listOf(24000, 24001), removed); assertEquals(8765, health.port())
        assertThrows(IllegalStateException::class.java) { owner.acquire() }
        health.close(); assertEquals(listOf(24000, 24001, 8765), removed)
    }
    @Test fun closeSerializesWithInFlightTunnelCreation() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val removed = AtomicInteger()
        val owner = DesktopForwarding({ entered.countDown(); check(release.await(2, TimeUnit.SECONDS)); 22000 }, { removed.incrementAndGet() })
        val executor = Executors.newFixedThreadPool(2)
        try {
            val opening = executor.submit<DesktopTunnel> { owner.acquire() }
            assertTrue(entered.await(1, TimeUnit.SECONDS)); val closing = executor.submit { owner.close() }
            release.countDown(); opening.get(2, TimeUnit.SECONDS).close(); closing.get(2, TimeUnit.SECONDS)
            assertEquals(1, removed.get())
        } finally { release.countDown(); owner.close(); executor.shutdownNow() }
    }
    @Test fun failedForwardingCanBeRetried() {
        var calls = 0; val owner = DesktopForwarding({ if (++calls == 1) error("SSH lost"); 22001 }, {})
        assertThrows(IllegalStateException::class.java) { owner.acquire() }
        assertEquals(22001, owner.acquire().port); owner.close()
    }
    @Test fun commandsAreFixedAndStatusDoesNotClaimSuccessOnFailure() {
        assertEquals("/usr/bin/systemctl show --value --property=ActiveState vncserver@:1.service", desktopCommand(DesktopOperation.STATUS))
        listOf(DesktopOperation.START, DesktopOperation.STOP).forEach {
            assertEquals("/usr/bin/sudo -n -N -u root -- /usr/local/libexec/myct-desktop-control ${it.name.lowercase()}", desktopCommand(it))
        }
        fun parse(value: String, status: Int = 0) = parseDesktopService(SshExecResult(value, "", status))
        assertEquals(DesktopService.AVAILABLE, parse("active\n")); assertEquals(DesktopService.OFF, parse("inactive"))
        assertEquals(DesktopService.STARTING, parse("activating")); assertEquals(DesktopService.STOPPING, parse("deactivating"))
        assertEquals(DesktopService.ERROR, parse("active", 1)); assertEquals(DesktopService.ERROR, parse("unknown"))
    }
    @Test fun portraitLandscapeZoomAndPanCoordinatesExcludeLetterbox() {
        val portrait = DesktopViewport(720f, 1280f, 1280, 720)
        assertEquals(Pair(640, 360), portrait.remote(360f, 640f)); assertNull(portrait.remote(360f, 1f))
        assertEquals(Pair(0, 0), portrait.remote(-50f, -50f, true))
        val landscape = DesktopViewport(1280f, 720f, 1280, 720, 2f, -120f, 50f)
        assertEquals(Pair(700, 335), landscape.remote(640f, 360f)); assertNull(landscape.remote(Float.NaN, 2f))
        assertNull(DesktopViewport(0f, 0f, 1280, 720).remote(0f, 0f))
    }
    @Test fun portugueseCompositionCommitsOnceAndBackspacePreservesRemoteWhileComposing() {
        val sent = mutableListOf<String>(); var deleted = 0
        val ime = DesktopComposition({ sent.add(it) }, { deleted++ })
        ime.compose("a"); ime.compose("ã"); assertTrue(sent.isEmpty())
        ime.commit("ã"); ime.finish(); assertEquals(listOf("ã"), sent)
        ime.compose("caf"); ime.delete(1); assertEquals("ca", ime.composing); assertEquals(0, deleted)
        ime.clear(); ime.delete(1); assertEquals(1, deleted)
        ime.compose("ação"); ime.finish(); ime.finish(); assertEquals(listOf("ã", "ação"), sent)
    }
    @Test fun textMapsLatin1UnicodeAndSpecialKeys() {
        assertEquals(listOf(0x61, 0xe7, 0xe3, 0x6f), DesktopKeys.text("ação"))
        assertEquals(listOf(DesktopKeys.ENTER, DesktopKeys.TAB, DesktopKeys.BACKSPACE), DesktopKeys.text("\n\t\b"))
        assertEquals(listOf(0x010020ac, 0x0101f600), DesktopKeys.text("€😀"))
    }
    @Test fun desktopNavigationAndIconsAreComplete() {
        assertEquals(AppSection.Desktop, checkNotNull(SectionNavigation.Saver.restore("Desktop")).displayed)
        assertTrue(sectionIcons.keys.containsAll(AppSection.entries))
        val navigation = SectionNavigation(AppSection.Desktop)
        navigation.request(AppSection.Terminal); navigation.showRequested(); assertEquals(AppSection.Terminal, navigation.displayed)
    }
    @Test fun slowPinchAccumulatesBeforeDetectionAndNeverScrollsAfterRecognition() {
        val pinch = DesktopTwoFinger(100f, 200f, 5f)
        assertEquals(DesktopTwoFinger.Mode.WAITING, pinch.update(104f, 200f))
        assertEquals(DesktopTwoFinger.Mode.WAITING, pinch.update(108f, 200f))
        assertEquals(DesktopTwoFinger.Mode.ZOOM, pinch.update(112f, 200f))
        assertEquals(DesktopTwoFinger.Mode.ZOOM, pinch.update(100f, 250f))
        val scroll = DesktopTwoFinger(100f, 200f, 5f)
        assertEquals(DesktopTwoFinger.Mode.SCROLL, scroll.update(101f, 215f))
    }

}
