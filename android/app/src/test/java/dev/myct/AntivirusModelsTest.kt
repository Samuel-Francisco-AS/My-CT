package dev.myct

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class AntivirusModelsTest {
    private fun await(predicate: () -> Boolean) = runBlocking { withTimeout(3000) { while (!predicate()) delay(5) } }
    @Test fun sizeLimitIsInclusiveAndNeverOpensSshAboveIt() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val calls = AtomicInteger()
        val model = AntivirusScanModel(scope) { _, _ -> calls.incrementAndGet(); parseAvScan(reply(scanFixture())) }
        try {
            model.attach(SshConnection()); model.select("/home/sam/file", AV_MAX_BYTES + 1)
            assertFalse(model.canScan); model.scan()
            assertEquals(AvState.INCOMPLETE, model.result!!.state); assertEquals(0, calls.get())
            model.select("/home/sam/file", AV_MAX_BYTES)
            assertNull(model.result); assertTrue(model.canScan); assertEquals(0, calls.get()) // Selection never scans.
            model.scan(); await { !model.busy }
            assertEquals(1, calls.get()); assertEquals(AvState.CLEAN, model.result!!.state)
        } finally { scope.cancel() }
    }
    @Test fun cancelClosesOldOperationBeforeNextAndNeverPublishesItsResult() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val entered = CountDownLatch(1); val closed = CountDownLatch(1); val calls = AtomicInteger()
        val model = AntivirusScanModel(scope) { _, _ ->
            if (calls.incrementAndGet() == 1) {
                entered.countDown()
                try { Thread.sleep(10000) } finally { closed.countDown() }
            } else check(closed.count == 0L)
            parseAvScan(reply(scanFixture()))
        }
        try {
            model.attach(SshConnection()); model.select("/home/sam/file", 3); model.scan()
            assertTrue(entered.await(2, TimeUnit.SECONDS)); model.cancel("Cancelado ao sair do app.")
            assertEquals(AvState.INCOMPLETE, model.result!!.state)
            assertTrue(closed.await(2, TimeUnit.SECONDS)); assertFalse(model.busy)
            model.scan(); await { !model.busy }
            assertEquals(2, calls.get()); assertEquals(AvState.CLEAN, model.result!!.state)
            model.attach(null); model.scan(); assertNull(model.result); assertEquals(2, calls.get())
        } finally { scope.cancel() }
    }
    @Test fun scanTransportFailureIsNotAnAntivirusVerdict() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val model = AntivirusScanModel(scope) { _, _ -> error("SSH unavailable") }
        try {
            model.attach(SshConnection()); model.select("/home/sam/file", 3); model.scan(); await { !model.busy }
            assertNull(model.result); assertTrue(model.message.contains("SSH"))
        } finally { scope.cancel() }
    }
    @Test fun manualRequestIgnoresOldSuccessPollsWaitingThenConfirmsRealSuccess() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val commands = ConcurrentLinkedQueue<AvCommand>()
        val stage = AtomicReference("SUCCESS")
        val sent = CountDownLatch(1); val release = CountDownLatch(1)
        val model = AntivirusSignaturesModel(scope, 10) { _, cmd ->
            commands.add(cmd)
            when (cmd) {
                AvCommand.STATUS -> reply(avFixture())
                AvCommand.UPDATE -> {
                    sent.countDown(); check(release.await(2, TimeUnit.SECONDS)); stage.set("RUNNING")
                    reply(updateFixture("SUCCESS").put("requested", true)) // Old result must not be used.
                }
                AvCommand.UPDATE_STATUS -> reply(updateFixture(stage.get(), if (stage.get() == "RUNNING") "WAITING" else "FINISHED"))
            }
        }
        try {
            model.attach(SshConnection()); model.show(); await { !model.busy }
            assertEquals(AvUpdateResult.SUCCESS, model.update!!.result)
            assertFalse(commands.contains(AvCommand.UPDATE))
            model.requestUpdate(); assertTrue(sent.await(2, TimeUnit.SECONDS))
            assertNull(model.update); assertTrue(model.unconfirmed)
            model.requestUpdate(); release.countDown()
            await { model.update?.running == true }
            assertEquals("WAITING", model.update!!.phase); assertTrue(model.busy)
            assertEquals(1, commands.count { it == AvCommand.UPDATE })
            stage.set("SUCCESS"); await { !model.busy }
            assertEquals(AvUpdateResult.SUCCESS, model.update!!.result); assertFalse(model.unconfirmed)
            val count = commands.size; Thread.sleep(40); assertEquals(count, commands.size)
        } finally { release.countDown(); model.hide(); scope.cancel() }
    }
    @Test fun closingOptionsStopsPollingButReopeningQueriesInsteadOfReplayingUpdate() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val commands = ConcurrentLinkedQueue<AvCommand>()
        val started = CountDownLatch(1); val closed = CountDownLatch(1)
        val state = AtomicReference("SUCCESS")
        val model = AntivirusSignaturesModel(scope, 10) { _, cmd ->
            commands.add(cmd)
            when (cmd) {
                AvCommand.STATUS -> reply(avFixture())
                AvCommand.UPDATE -> { state.set("RUNNING"); reply(updateFixture("SUCCESS").put("requested", true)) }
                AvCommand.UPDATE_STATUS -> {
                    if (state.get() == "RUNNING") {
                        started.countDown(); try { Thread.sleep(10000) } finally { closed.countDown() }
                    }
                    reply(updateFixture(state.get(), if (state.get() == "RUNNING") "UPDATING" else "FINISHED"))
                }
            }
        }
        try {
            model.attach(SshConnection()); model.show(); await { !model.busy }
            model.requestUpdate(); assertTrue(started.await(2, TimeUnit.SECONDS))
            model.hide(); assertTrue(closed.await(2, TimeUnit.SECONDS)); assertFalse(model.busy)
            state.set("SUCCESS"); model.show(); await { !model.busy }
            assertEquals(AvUpdateResult.SUCCESS, model.update!!.result)
            assertEquals(1, commands.count { it == AvCommand.UPDATE })
        } finally { model.hide(); scope.cancel() }
    }
    @Test fun failuresAndInterruptionKeepLastSuccessfulUpdateAndStaleness() {
        for (outcome in listOf("ERROR", "INTERRUPTED")) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val model = AntivirusSignaturesModel(scope) { _, cmd ->
                if (cmd == AvCommand.STATUS) reply(avFixture().put("stale", true))
                else reply(updateFixture(outcome).put("stale", true), 1)
            }
            try {
                model.attach(SshConnection()); model.show(); await { !model.busy }
                assertEquals(AvUpdateResult.valueOf(outcome), model.update!!.result)
                assertEquals(1792177627L, model.info!!.lastSuccess); assertTrue(model.info!!.stale)
            } finally { model.hide(); scope.cancel() }
        }
    }
    @Test fun connectionFailureRequiresExplicitRefreshAndNeverStartsUpdate() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val calls = ConcurrentLinkedQueue<AvCommand>()
        val model = AntivirusSignaturesModel(scope) { _, cmd -> calls.add(cmd); error("disconnected") }
        try {
            model.attach(SshConnection()); model.show(); await { !model.busy }
            assertTrue(model.unconfirmed); assertTrue(model.message.contains("SSH")); assertNull(model.update)
            assertEquals(listOf(AvCommand.STATUS), calls.toList())
            model.attach(null); model.show(); model.requestUpdate()
            assertFalse(model.connected); assertEquals(1, calls.size)
        } finally { model.hide(); scope.cancel() }
    }
}
