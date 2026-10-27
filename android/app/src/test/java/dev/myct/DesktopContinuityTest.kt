package dev.myct

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/** Stable-session tests. Peers and SSH are fixtures, never the Fedora server. */
class DesktopContinuityTest {
    private class Peer : DesktopPeer {
        val closed = CountDownLatch(1)
        val authenticated = AtomicInteger()
        val frame = RfbFramebuffer(8, 4)
        var sink: ((RfbFramebuffer) -> Unit)? = null
        override fun connect(port: Int, password: CharArray) { authenticated.incrementAndGet(); password.fill('\u0000') }
        override fun read(onFrame: (RfbFramebuffer) -> Unit) {
            sink = onFrame; onFrame(frame); closed.await(); throw IOException("closed fixture")
        }
        override fun pointer(x: Int, y: Int, mask: Int) {}
        override fun key(sym: Int, down: Boolean) {}
        override fun close() { closed.countDown() }
        override fun dispose() {}
    }
    private fun await(test: () -> Boolean) = runBlocking { withTimeout(3500) { while (!test()) delay(5) } }
    @Test fun repeatedSurfaceReplacementReusesAuthenticationTunnelAndIdleFramebuffer() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val peer = Peer(); val opens = AtomicInteger(); val releases = AtomicInteger()
        val model = DesktopModel(scope, { opens.incrementAndGet(); DesktopTunnel(23000) { releases.incrementAndGet() } },
            { _, _ -> SshExecResult("active", "", 0) }, { peer }, configurationGraceMs = 1000)
        var owner = Any(); val stale = mutableListOf<Any>(); var copies = AtomicInteger()
        try {
            model.attach(SshConnection()); model.show({ copies.incrementAndGet() }, {}, owner)
            model.connect("fixture".toCharArray()); await { model.frames > 0 }
            repeat(20) {
                val oldOwner = owner; val oldCopies = copies; stale.add(oldOwner)
                model.detachSurface(oldOwner, true)
                assertFalse(model.ownsSurface(oldOwner)); assertEquals(DesktopLink.CONNECTED, model.link)
                val n = oldCopies.get(); owner = Any(); copies = AtomicInteger()
                model.show({ copies.incrementAndGet() }, {}, owner)
                await { copies.get() > 0 }
                model.framePresented(model.attempt, oldOwner); assertFalse(model.presented)
                model.framePresented(model.attempt, owner); assertTrue(model.presented)
                model.detachSurface(oldOwner, false) // Late disposal cannot disconnect a newer View.
                assertEquals(n, oldCopies.get()); assertEquals(DesktopLink.CONNECTED, model.link)
            }
            assertEquals(1, opens.get()); assertEquals(1, peer.authenticated.get()); assertEquals(0, releases.get())
            model.disconnect(); await { !model.retiring }
            assertEquals(1, releases.get()); assertEquals(DesktopLink.DISCONNECTED, model.link)
        } finally { model.hide(); scope.cancel() }
    }
    @Test fun genuineBackgroundExitAndUnclaimedConfigurationHandoffCloseResources() {
        for (configuration in listOf(false, true)) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val peer = Peer()
            val closes = AtomicInteger(); val owner = Any()
            val model = DesktopModel(scope, { DesktopTunnel(23000) { closes.incrementAndGet() } },
                { _, _ -> SshExecResult("active", "", 0) }, { peer }, configurationGraceMs = 30)
            try {
                model.attach(SshConnection()); model.show({}, {}, owner); model.connect("fixture".toCharArray())
                await { model.frames > 0 }; model.detachSurface(owner, configuration)
                await { model.link == DesktopLink.DISCONNECTED && !model.retiring }
                assertEquals(1, closes.get()); assertFalse(model.ownsSurface(owner))
            } finally { model.hide(); scope.cancel() }
        }
    }
    @Test fun sshLossDuringHandoffCannotReattachOldPixelsOrReopenTunnel() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val peer = Peer()
        val closes = AtomicInteger(); val owner = Any(); val copies = AtomicInteger()
        val model = DesktopModel(scope, { DesktopTunnel(23000) { closes.incrementAndGet() } },
            { _, _ -> SshExecResult("active", "", 0) }, { peer })
        try {
            model.attach(SshConnection()); model.show({}, {}, owner); model.connect("fixture".toCharArray()); await { model.frames > 0 }
            model.detachSurface(owner, true); model.attach(null); await { !model.retiring }
            model.show({ copies.incrementAndGet() }, {}, Any()); Thread.sleep(30)
            assertEquals(0, copies.get()); assertFalse(model.ssh); assertEquals(1, closes.get())
            assertEquals(DesktopLink.DISCONNECTED, model.link)
        } finally { model.hide(); scope.cancel() }
    }
    @Test fun detachWaitsForInFlightCopyAndThenReleasesAllRendererReferences() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val peer = Peer(); val owner = Any()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val model = DesktopModel(scope, { DesktopTunnel(23000) {} }, { _, _ -> SshExecResult("active", "", 0) }, { peer })
        val executor = Executors.newSingleThreadExecutor()
        try {
            model.attach(SshConnection()); model.show({ entered.countDown(); release.await(2, TimeUnit.SECONDS) }, {}, owner)
            model.connect("fixture".toCharArray()); assertTrue(entered.await(2, TimeUnit.SECONDS))
            val detaching = executor.submit { model.detachSurface(owner, true) }
            Thread.sleep(20); assertFalse(detaching.isDone); release.countDown(); detaching.get(2, TimeUnit.SECONDS)
            assertFalse(model.ownsSurface(owner))
            for (name in listOf("render", "clearImage", "surfaceOwner")) {
                val f = DesktopModel::class.java.getDeclaredField(name); f.isAccessible = true; assertNull(name, f.get(model))
            }
        } finally { release.countDown(); model.hide(); scope.cancel(); executor.shutdownNow() }
    }
}
