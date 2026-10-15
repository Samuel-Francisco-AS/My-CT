package dev.myct

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VaultModelTest {
    private val locked = VaultStatus(VaultKind.LOCKED, true)
    private fun await(predicate: () -> Boolean) = runBlocking {
        withTimeout(3000) { while (!predicate()) delay(5) }
    }
    @Test fun disconnectInterruptsPromptReadWithoutMainTerminalOrSecret() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val entered = CountDownLatch(1)
        val input = PipedInputStream()
        val feeder = PipedOutputStream(input)
        val output = ByteArrayOutputStream()
        val transport = VaultUnlockChannel(input, output, { input.close(); feeder.close() }, "test")
        val model = VaultModel(scope, { locked }, { entered.countDown(); transport })
        try {
            model.attach(SshConnection()); model.refresh()
            await { model.state.phase == VaultPhase.LOCKED }
            model.beginUnlock()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertEquals(VaultPhase.PREPARING, model.state.phase)
            val secret = "synthetic-before-prompt".toCharArray()
            model.submitPassword(secret)
            assertTrue(secret.all { it == '\u0000' })
            model.attach(null)
            runBlocking { withTimeout(3000) { scope.coroutineContext[Job]!!.children.toList().joinAll() } }
            assertEquals(VaultPhase.NO_SSH, model.state.phase)
            assertFalse(output.toString("UTF-8").contains("synthetic-before-prompt"))
        } finally { scope.cancel(); transport.close() }
    }
    @Test fun promptThenHumanSubmissionRequeriesStatusAndWipesCallerBuffer() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val out = ByteArrayOutputStream()
        var queries = 0
        var closes = 0
        val transport = VaultUnlockChannel(ByteArrayInputStream((
            "\r\nMYCT-VAULT-BEGIN:test\r\nSenha do cofre login (SSH privado, sem eco): " +
                "\r\nMYCT-VAULT-DONE:test:0\r\n").toByteArray()), out, { closes++ }, "test")
        val model = VaultModel(scope, { if (++queries == 1) locked else VaultStatus(VaultKind.UNLOCKED) }, { transport })
        try {
            model.attach(SshConnection()); model.refresh()
            await { model.state.phase == VaultPhase.LOCKED }
            model.beginUnlock(); await { model.state.phase == VaultPhase.PASSWORD }
            assertFalse(out.toString("UTF-8").contains("synthetic-password"))
            val secret = "synthetic-password".toCharArray()
            model.submitPassword(secret)
            assertTrue(secret.all { it == '\u0000' })
            await { model.state.phase == VaultPhase.UNLOCKED }
            assertEquals(2, queries)
            assertEquals(1, closes)
            model.attach(null); model.attach(SshConnection())
            assertEquals(VaultPhase.IDLE, model.state.phase)
        } finally { scope.cancel(); transport.close() }
    }
    @Test fun lateOpenAfterCancelIsClosedAndCannotReplaceNewSession() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val late = VaultUnlockChannel(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), { closed.countDown() })
        val model = VaultModel(scope, { locked }, {
            entered.countDown()
            // Model a connector that returns after cancellation rather than aborting.
            var released = false
            while (!released) {
                try { released = release.await(2, TimeUnit.SECONDS); check(released) }
                catch (_: InterruptedException) { }
            }
            late
        })
        try {
            model.attach(SshConnection()); model.refresh()
            await { model.state.phase == VaultPhase.LOCKED }
            model.beginUnlock()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            model.cancelUnlock(); model.attach(SshConnection())
            release.countDown()
            assertTrue(closed.await(2, TimeUnit.SECONDS))
            runBlocking { withTimeout(3000) { scope.coroutineContext[Job]!!.children.toList().joinAll() } }
            assertEquals(VaultPhase.IDLE, model.state.phase)
        } finally { release.countDown(); scope.cancel(); late.close() }
    }
    @Test fun cancelledScopeBeforeSubmissionStillWipesAndDoesNotSendPassword() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val out = ByteArrayOutputStream()
        val transport = VaultUnlockChannel(ByteArrayInputStream((
            "\r\nMYCT-VAULT-BEGIN:test\r\nSenha do cofre login (SSH privado, sem eco): ").toByteArray()), out, {}, "test")
        val model = VaultModel(scope, { locked }, { transport })
        try {
            model.attach(SshConnection()); model.refresh()
            await { model.state.phase == VaultPhase.LOCKED }
            model.beginUnlock(); await { model.state.phase == VaultPhase.PASSWORD }
            scope.cancel()
            val secret = "synthetic-cancelled".toCharArray()
            model.submitPassword(secret)
            assertTrue(secret.all { it == '\u0000' })
            assertFalse(out.toString("UTF-8").contains("synthetic-cancelled"))
        } finally { scope.cancel(); transport.close() }
    }

}
