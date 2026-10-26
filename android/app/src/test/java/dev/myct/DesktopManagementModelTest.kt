package dev.myct

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class DesktopManagementModelTest {
    private class Peer : DesktopPeer {
        val closed = CountDownLatch(1)
        @Volatile private var render: ((RfbFramebuffer) -> Unit)? = null
        fun anotherFrame() { render?.invoke(RfbFramebuffer(4, 3)) }
        override fun connect(port: Int, password: CharArray) { password.fill('\u0000') }
        override fun read(onFrame: (RfbFramebuffer) -> Unit) {
            render = onFrame; onFrame(RfbFramebuffer(4, 3)); closed.await(10, TimeUnit.SECONDS); throw IOException("closed")
        }
        override fun pointer(x: Int, y: Int, mask: Int) {}
        override fun key(sym: Int, down: Boolean) {}
        override fun close() { closed.countDown() }
        override fun dispose() {}
    }
    private fun await(done: () -> Boolean) = runBlocking { withTimeout(3000) { while (!done()) delay(5) } }
    @Test fun absentHelperMustNotDisconnectTheLiveDesktop() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val peer = Peer()
        val model = DesktopModel(scope, { DesktopTunnel(23000) {} },
            { _, op -> if (op == DesktopOperation.STATUS) SshExecResult("active", "", 0) else SshExecResult("", "missing", 127) }, { peer }, inspectAdministration = { DesktopAdministration(DesktopAdminAccess.MISSING) })
        try {
            model.attach(SshConnection()); model.show({}, {}); await { !model.busy }
            model.connect("abc".toCharArray()); await { model.link == DesktopLink.CONNECTED }
            model.stop(); await { !model.busy }
            assertEquals("Administrative failure must not close VNC", DesktopLink.CONNECTED, model.link)
            assertEquals(1L, peer.closed.count)
            assertEquals(DesktopService.AVAILABLE, model.service)
        } finally { model.hide(); await { !model.retiring }; scope.cancel() }
    }
    @Test fun denialAfterAuthorizationRecheckKeepsTheViewerAndTheRealActiveState() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val peer = Peer()
        var access = DesktopAdministration(DesktopAdminAccess.READY, true, true)
        val calls = CopyOnWriteArrayList<DesktopOperation>()
        val model = DesktopModel(scope, { DesktopTunnel(23000) {} }, { _, op ->
            calls.add(op)
            if (op == DesktopOperation.STOP) { access = DesktopAdministration(DesktopAdminAccess.DENIED); SshExecResult("", "denied", 1) }
            else SshExecResult("active", "", 0)
        }, { peer }, inspectAdministration = { access })
        try {
            model.attach(SshConnection()); model.show({}, {}); await { !model.busy }; model.connect("abc".toCharArray())
            await { model.frames > 0 }; model.stop(); await { !model.busy }
            assertEquals(DesktopAdminOutcome.DENIED, model.adminOutcome); assertEquals(DesktopService.AVAILABLE, model.service)
            assertEquals(DesktopLink.CONNECTED, model.link); assertEquals(1L, peer.closed.count)
            assertEquals(1, calls.count { it == DesktopOperation.STOP }); assertEquals(DesktopOperation.STATUS, calls.last())
            val error = model.adminMessage; peer.anotherFrame(); assertEquals(error, model.adminMessage)
            assertFalse(model.administration.canStop)
        } finally { model.hide(); await { !model.retiring }; scope.cancel() }
    }
    @Test fun helperRemovedDuringTheRequestIsSpecificAndDoesNotCloseVnc() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val peer = Peer()
        var access = DesktopAdministration(DesktopAdminAccess.READY, true, true)
        val model = DesktopModel(scope, { DesktopTunnel(23000) {} }, { _, op ->
            if (op == DesktopOperation.STOP) { access = DesktopAdministration(DesktopAdminAccess.MISSING); SshExecResult("", "", 127) }
            else SshExecResult("active", "", 0)
        }, { peer }, inspectAdministration = { access })
        try {
            model.attach(SshConnection()); model.show({}, {}); await { !model.busy }; model.connect("abc".toCharArray()); await { model.frames > 0 }
            model.stop(); await { !model.busy }
            assertEquals(DesktopAdminOutcome.UNAVAILABLE, model.adminOutcome)
            assertTrue(model.adminMessage.contains("helper não instalado")); assertEquals(DesktopLink.CONNECTED, model.link)
            assertEquals(DesktopService.AVAILABLE, model.service); assertEquals(1L, peer.closed.count)
        } finally { model.hide(); await { !model.retiring }; scope.cancel() }
    }
    @Test fun queuedStopOnlyClosesTheViewerAfterInactiveIsActuallyObserved() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val peer = Peer()
        val requested = AtomicBoolean(); val inactive = AtomicBoolean(); val polls = AtomicInteger(); val stops = AtomicInteger()
        val model = DesktopModel(scope, { DesktopTunnel(23000) {} }, { _, op ->
            when (op) {
                DesktopOperation.STOP -> { stops.incrementAndGet(); requested.set(true); SshExecResult("", "", 0) }
                else -> { if (requested.get()) polls.incrementAndGet(); SshExecResult(if (inactive.get()) "inactive" else "active", "", 0) }
            }
        }, { peer }, pollDelayMs = 10, inspectAdministration = { DesktopAdministration(DesktopAdminAccess.READY, true, true) })
        try {
            model.attach(SshConnection()); model.show({}, {}); await { !model.busy }; model.connect("abc".toCharArray()); await { model.frames > 0 }
            model.stop(); await { requested.get() && polls.get() > 0 }
            assertEquals(DesktopAdminOutcome.RUNNING, model.adminOutcome); assertEquals(DesktopService.AVAILABLE, model.service)
            assertEquals(DesktopLink.CONNECTED, model.link); assertEquals(1L, peer.closed.count)
            model.stop(); assertEquals(1, stops.get())
            inactive.set(true); await { !model.busy && !model.retiring }
            assertEquals(DesktopAdminOutcome.SUCCEEDED, model.adminOutcome); assertEquals(DesktopService.OFF, model.service)
            assertEquals(DesktopLink.DISCONNECTED, model.link); assertEquals(0L, peer.closed.count)
        } finally { model.hide(); await { !model.retiring }; scope.cancel() }
    }
    @Test fun failedCommandAndUnknownResultDoNotClaimStopOrDisconnect() {
        for (transportFailure in listOf(false, true)) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val peer = Peer()
            val stops = AtomicInteger()
            val model = DesktopModel(scope, { DesktopTunnel(23000) {} }, { _, op ->
                if (op == DesktopOperation.STOP) {
                    stops.incrementAndGet()
                    if (transportFailure) throw IOException("unknown result") else SshExecResult("", "failed", 1)
                } else SshExecResult("active", "", 0)
            }, { peer }, inspectAdministration = { DesktopAdministration(DesktopAdminAccess.READY, true, true) })
            try {
                model.attach(SshConnection()); model.show({}, {}); await { !model.busy }; model.connect("abc".toCharArray()); await { model.frames > 0 }
                model.stop(); await { !model.busy }
                assertEquals(if (transportFailure) DesktopAdminOutcome.UNCERTAIN else DesktopAdminOutcome.FAILED, model.adminOutcome)
                assertEquals(DesktopService.AVAILABLE, model.service); assertEquals(DesktopLink.CONNECTED, model.link)
                assertEquals(1L, peer.closed.count); assertEquals(1, stops.get())
            } finally { model.hide(); await { !model.retiring }; scope.cancel() }
        }
    }
    @Test fun manualDisconnectAndReentryWorkWhenAdministrationIsUnavailable() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val calls = CopyOnWriteArrayList<DesktopOperation>(); val peers = CopyOnWriteArrayList<Peer>()
        val model = DesktopModel(scope, { DesktopTunnel(23000) {} }, { _, op -> calls.add(op); SshExecResult("active", "", 0) },
            { Peer().also { peers.add(it) } }, inspectAdministration = { DesktopAdministration(DesktopAdminAccess.DENIED) })
        try {
            model.attach(SshConnection()); model.show({}, {}); await { !model.busy }; model.connect("abc".toCharArray()); await { model.frames > 0 }
            model.hide(); await { !model.retiring }; assertEquals(DesktopService.AVAILABLE, model.service)
            model.show({}, {}); await { !model.busy }; model.connect("abc".toCharArray()); await { model.frames > 0 }
            assertEquals(2, peers.size); assertEquals(DesktopLink.CONNECTED, model.link)
            assertTrue(calls.all { it == DesktopOperation.STATUS })
        } finally { model.hide(); await { !model.retiring }; scope.cancel() }
    }
    @Test fun partialPermissionBlocksOnlyTheDisallowedAdministrativeOperation() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val commands = CopyOnWriteArrayList<DesktopOperation>()
        val model = DesktopModel(scope, query = { _, op -> commands.add(op); SshExecResult("active", "", 0) },
            inspectAdministration = { DesktopAdministration(DesktopAdminAccess.READY, true, false) })
        try {
            model.attach(SshConnection()); model.show({}, {}); await { !model.busy }
            assertTrue(model.administration.canStart); assertFalse(model.administration.canStop)
            model.stop(); await { !model.busy }
            assertEquals(DesktopAdminOutcome.DENIED, model.adminOutcome); assertTrue(commands.all { it == DesktopOperation.STATUS })
            assertEquals(DesktopService.AVAILABLE, model.service)
        } finally { model.hide(); scope.cancel() }
    }

}
