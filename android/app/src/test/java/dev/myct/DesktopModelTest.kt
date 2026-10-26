package dev.myct

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

class DesktopModelTest {
    private class Peer : DesktopPeer {
        val closed = CountDownLatch(1); val connected = AtomicInteger(); val disposed = AtomicInteger()
        val frame = RfbFramebuffer(4, 3); val input = CopyOnWriteArrayList<String>()
        var fail = false
        var failWrite = false
        override fun connect(port: Int, password: CharArray) { connected.incrementAndGet(); password.fill('\u0000'); if (fail) throw RfbException("Senha recusada", DesktopFailure.AUTH) }
        override fun read(onFrame: (RfbFramebuffer) -> Unit) { onFrame(frame); closed.await(3, TimeUnit.SECONDS); throw IOException("closed") }
        override fun pointer(x: Int, y: Int, mask: Int) { if (failWrite) throw IOException("write failed"); input.add("p:$x:$y:$mask") }
        override fun key(sym: Int, down: Boolean) { input.add("k:$sym:$down") }
        override fun close() { closed.countDown() }
        override fun dispose() { disposed.incrementAndGet() }
    }
    private fun await(done: () -> Boolean) = runBlocking { withTimeout(3500) { while (!done()) delay(5) } }
    @Test fun duplicateConnectHideAndReconnectCloseOnlyDesktopResources() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val peers = CopyOnWriteArrayList<Peer>(); val released = AtomicInteger(); val rendered = AtomicInteger()
        val model = DesktopModel(scope, { DesktopTunnel(23000) { released.incrementAndGet() } },
            { _, _ -> SshExecResult("active", "", 0) }, { Peer().also { peers.add(it) } })
        try {
            model.attach(SshConnection()); model.show({ rendered.incrementAndGet() }, {}); await { !model.busy }
            val secret = "abc".toCharArray(); model.connect(secret); await { model.frames > 0 }
            val duplicate = "other".toCharArray(); model.connect(duplicate)
            assertEquals(1, peers.size); assertTrue(duplicate.all { it == '\u0000' }); assertTrue(secret.all { it == '\u0000' })
            model.hide(); await { !model.retiring && released.get() == 1 }
            assertEquals(DesktopLink.DISCONNECTED, model.link); assertTrue(model.ssh); assertEquals(1, peers[0].disposed.get())
            val stoppedAt = rendered.get(); Thread.sleep(20); assertEquals(stoppedAt, rendered.get())
            model.show({}, {}); await { !model.busy }; model.connect("abc".toCharArray()); await { model.link == DesktopLink.CONNECTED }
            model.attach(null); await { released.get() == 2 && !model.retiring }
            assertFalse(model.ssh); assertEquals(DesktopLink.DISCONNECTED, model.link)
        } finally { model.hide(); scope.cancel() }
    }
    @Test fun cancellationDuringTunnelAcquisitionClosesLateLeaseAndErasesPassword() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val released = AtomicInteger(); val peer = Peer()
        val model = DesktopModel(scope, { entered.countDown(); release.await(2, TimeUnit.SECONDS); DesktopTunnel(23001) { released.incrementAndGet() } },
            { _, _ -> SshExecResult("active", "", 0) }, { peer })
        try {
            model.attach(SshConnection()); model.show({}, {}); await { !model.busy }
            val secret = "abc".toCharArray(); model.connect(secret); assertTrue(entered.await(1, TimeUnit.SECONDS))
            model.disconnect(); release.countDown(); await { released.get() == 1 && !model.retiring }
            assertTrue(secret.all { it == '\u0000' }); assertEquals(DesktopLink.DISCONNECTED, model.link); assertEquals(1, peer.disposed.get())
        } finally { release.countDown(); model.hide(); scope.cancel() }
    }
    @Test fun authFailureDoesNotPublishConnectedImage() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val peer = Peer().also { it.fail = true }
        val model = DesktopModel(scope, { DesktopTunnel(23000) {} }, { _, _ -> SshExecResult("active", "", 0) }, { peer })
        try {
            model.attach(SshConnection()); model.show({ fail("Must not render") }, {}); await { !model.busy }
            model.connect("bad".toCharArray()); await { model.link == DesktopLink.ERROR && peer.disposed.get() == 1 }
            assertEquals(0L, model.frames); assertEquals(DesktopFailure.AUTH, model.diagnostics.state.failure)
        } finally { model.hide(); scope.cancel() }
    }
    @Test fun keyboardChordAndPointerReleaseArePairedAndFocusClearsModifiers() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val peer = Peer()
        val model = DesktopModel(scope, { DesktopTunnel(23000) {} }, { _, _ -> SshExecResult("active", "", 0) }, { peer })
        try {
            model.attach(SshConnection()); model.show({}, {}); await { !model.busy }; model.connect("abc".toCharArray()); await { model.link == DesktopLink.CONNECTED }
            model.toggleModifier(DesktopKeys.CTRL); model.press('c'.code); model.pointer(3, 2, 1); model.releaseInput(); await { peer.input.size >= 9 }
            assertEquals(listOf("k:65507:true", "k:99:true", "k:99:false", "k:65507:false", "p:3:2:1", "p:3:2:0"), peer.input.take(6))
            assertFalse(model.ctrl); assertFalse(model.alt); assertFalse(model.shift)
        } finally { model.hide(); await { !model.retiring }; scope.cancel() }
    }
    @Test fun missingHelperDoesNotClaimStartStopSuccess() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val commands = CopyOnWriteArrayList<DesktopOperation>()
        val model = DesktopModel(scope, query = { _, op -> commands.add(op); SshExecResult("inactive", "", 0) },
            inspectAdministration = { DesktopAdministration(DesktopAdminAccess.MISSING) })
        try {
            model.attach(SshConnection()); model.show({}, {}); await { !model.busy }; assertEquals(DesktopService.OFF, model.service)
            model.start(); await { !model.busy }; assertEquals(DesktopService.OFF, model.service)
            assertEquals(DesktopAdminOutcome.UNAVAILABLE, model.adminOutcome); assertTrue(model.adminMessage.contains("helper"))
            model.stop(); await { !model.busy }; assertEquals(DesktopService.OFF, model.service)
            assertTrue(commands.all { it == DesktopOperation.STATUS })
        } finally { model.hide(); scope.cancel() }
    }
    @Test fun staleServiceQueryAfterSshChangeCannotOverwriteNewHost() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val calls = AtomicInteger()
        val model = DesktopModel(scope, query = { _, _ ->
            if (calls.incrementAndGet() == 1) { entered.countDown(); release.await(2, TimeUnit.SECONDS); SshExecResult("active", "", 0) }
            else SshExecResult("inactive", "", 0)
        })
        try {
            model.attach(SshConnection()); model.show({}, {}); assertTrue(entered.await(1, TimeUnit.SECONDS))
            model.attach(null); model.attach(SshConnection()); release.countDown(); await { !model.busy }; assertEquals(DesktopService.OFF, model.service)
        } finally { release.countDown(); model.hide(); scope.cancel() }
    }
    @Test fun writerFailureUnblocksIdleDecoderAndReleasesTunnel() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val peer = Peer()
        val released = AtomicInteger()
        val model = DesktopModel(scope, { DesktopTunnel(23000) { released.incrementAndGet() } },
            { _, _ -> SshExecResult("active", "", 0) }, { peer })
        try {
            model.attach(SshConnection()); model.show({}, {}); await { !model.busy }; model.connect("abc".toCharArray())
            await { model.link == DesktopLink.CONNECTED }; peer.failWrite = true; model.pointer(1, 1)
            await { released.get() == 1 && !model.retiring }
            assertEquals(DesktopLink.ERROR, model.link); assertEquals(1, peer.disposed.get())
        } finally { model.hide(); scope.cancel() }
    }
    @Test fun schedulingStartAndStopIsNotSuccessUntilTheQueriedStateConfirmsIt() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val calls = AtomicInteger(); var target = "inactive"; var transition = false
        val model = DesktopModel(scope, query = { _, op ->
            when (op) {
                DesktopOperation.START -> { target = "active"; transition = true; calls.set(0); SshExecResult("", "", 0) }
                DesktopOperation.STOP -> { target = "inactive"; transition = true; calls.set(0); SshExecResult("", "", 0) }
                DesktopOperation.STATUS -> SshExecResult(if (transition && calls.incrementAndGet() == 1)
                    (if (target == "active") "activating" else "deactivating") else target, "", 0)
            }
        }, pollDelayMs = 20, inspectAdministration = { DesktopAdministration(DesktopAdminAccess.READY, true, true) })
        try {
            model.attach(SshConnection()); model.show({}, {}); await { !model.busy }
            model.start(); assertTrue(model.busy); assertEquals(DesktopAdminOutcome.RUNNING, model.adminOutcome)
            await { !model.busy }; assertEquals(DesktopService.AVAILABLE, model.service); assertTrue(calls.get() >= 2)
            assertEquals(DesktopAdminOutcome.SUCCEEDED, model.adminOutcome)
            model.stop(); assertTrue(model.busy); assertEquals(DesktopAdminOutcome.RUNNING, model.adminOutcome)
            await { !model.busy }; assertEquals(DesktopService.OFF, model.service)
            assertEquals(DesktopAdminOutcome.SUCCEEDED, model.adminOutcome)
        } finally { model.hide(); scope.cancel() }
    }

    @Test fun hidingWaitsForAnInFlightPixelCopyThenClearsTheImage() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val peer = Peer()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val pixels = java.util.concurrent.atomic.AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor()
        val model = DesktopModel(scope, { DesktopTunnel(23000) {} }, { _, _ -> SshExecResult("active", "", 0) }, { peer })
        try {
            model.attach(SshConnection())
            model.show({ entered.countDown(); release.await(2, TimeUnit.SECONDS); pixels.set(true) }, { pixels.set(false) })
            await { !model.busy }; model.connect("abc".toCharArray()); assertTrue(entered.await(1, TimeUnit.SECONDS))
            val hiding = executor.submit { model.hide() }
            Thread.sleep(20); assertFalse(hiding.isDone)
            release.countDown(); hiding.get(2, TimeUnit.SECONDS); await { !model.retiring }
            assertFalse(pixels.get()); assertEquals(DesktopLink.DISCONNECTED, model.link)
        } finally { release.countDown(); model.hide(); scope.cancel(); executor.shutdownNow() }
    }

    @Test fun tunnelFailureIsRecoverableAndRenderCompletionRequiresAnActualDrawCallback() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val peers = CopyOnWriteArrayList<Peer>(); var failTunnel = true
        val model = DesktopModel(scope, { if (failTunnel) throw IOException("do not display this detail"); DesktopTunnel(23000) {} },
            { _, _ -> SshExecResult("active", "", 0) }, { Peer().also { peers.add(it) } })
        try {
            model.attach(SshConnection()); model.show({}, {}); await { !model.busy }
            model.connect("abc".toCharArray()); await { model.link == DesktopLink.ERROR && !model.retiring }
            assertEquals(DesktopFailure.TUNNEL, model.diagnostics.state.failure)
            assertEquals(DesktopStep.SSH, model.diagnostics.state.lastCompleted)
            assertFalse(model.message.contains("do not display")); assertTrue(model.ssh)
            failTunnel = false; model.connect("abc".toCharArray()); await { model.frames > 0 }
            assertFalse(model.presented)
            model.framePresented(model.attempt - 1); assertFalse(model.presented)
            model.framePresented(model.attempt); assertTrue(model.presented)
            assertTrue(DesktopStep.RENDER in model.diagnostics.state.completed)
        } finally { model.hide(); await { !model.retiring }; scope.cancel() }
    }
    @Test fun bitmapPreparationFailureStaysInsideDesktopAndAllowsASecondAttempt() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val model = DesktopModel(scope, { DesktopTunnel(23000) {} }, { _, _ -> SshExecResult("active", "", 0) }, { Peer() })
        try {
            model.attach(SshConnection()); model.show({ throw IllegalStateException("native renderer detail") }, {})
            await { !model.busy }; model.connect("abc".toCharArray())
            await { model.link == DesktopLink.ERROR && !model.retiring }
            assertEquals(DesktopFailure.RENDER, model.diagnostics.state.failure)
            assertTrue(model.ssh); assertFalse(model.presented)
            assertFalse(model.message.contains("native renderer detail"))
            model.show({}, {}); await { !model.busy }; model.connect("abc".toCharArray()); await { model.frames > 0 }
            assertEquals(DesktopLink.CONNECTED, model.link)
        } finally { model.hide(); await { !model.retiring }; scope.cancel() }
    }
    @Test fun tunnelConnectReadAndQueryAreDispatchedOffTheCallingUiThread() {
        val calling = Thread.currentThread()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val peer = Peer(); val checks = CopyOnWriteArrayList<Boolean>()
        val model = DesktopModel(scope, { checks.add(Thread.currentThread() !== calling); DesktopTunnel(23000) {} },
            { _, _ -> checks.add(Thread.currentThread() !== calling); SshExecResult("active", "", 0) }, {
                object : DesktopPeer by peer {
                    override fun connect(port: Int, password: CharArray) { checks.add(Thread.currentThread() !== calling); peer.connect(port, password) }
                    override fun read(onFrame: (RfbFramebuffer) -> Unit) { checks.add(Thread.currentThread() !== calling); peer.read(onFrame) }
                }
            })
        try {
            model.attach(SshConnection()); model.show({}, {}); await { !model.busy }; model.connect("abc".toCharArray()); await { model.frames > 0 }
            assertTrue(checks.size >= 4); assertTrue(checks.all { it })
        } finally { model.hide(); await { !model.retiring }; scope.cancel() }
    }

}
