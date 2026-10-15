package dev.myct

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HealthModelTest {
    private val sample = HealthSample(20.0, 100, 40, 200, 80, 10)
    private fun await(done: () -> Boolean) = runBlocking {
        withTimeout(3000) { while (!done()) delay(5) }
    }
    @Test fun refreshSuccessFailureAndDisconnectRetainTimestampedSample() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var fail = false
        val model = HealthModel(scope) { if (fail) error("Core off"); sample }
        try {
            model.refresh()
            assertEquals(HealthPhase.NO_SSH, model.state.phase)
            model.attach(SshConnection())
            model.refresh(); await { model.state.phase == HealthPhase.READY }
            assertEquals(sample, model.state.sample)
            assertNotNull(model.state.fetchedAt)
            fail = true
            model.refresh(); await { model.state.phase == HealthPhase.ERROR }
            assertEquals(sample, model.state.sample)
            model.attach(null)
            assertEquals(HealthPhase.NO_SSH, model.state.phase)
            assertEquals(sample, model.state.sample)
            model.attach(SshConnection())
            assertNull(model.state.sample)
        } finally { scope.cancel() }
    }
    @Test fun disconnectDuringLoadCannotPublishOldSessionResult() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val model = HealthModel(scope) {
            entered.countDown()
            try { check(release.await(3, TimeUnit.SECONDS)); sample } finally { returned.countDown() }
        }
        try {
            model.attach(SshConnection()); model.refresh()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertEquals(HealthPhase.LOADING, model.state.phase)
            model.refresh() // Duplicate must not issue another request.
            model.attach(null); model.attach(SshConnection())
            release.countDown()
            assertTrue(returned.await(2, TimeUnit.SECONDS))
            runBlocking { withTimeout(2000) { scope.coroutineContext[Job]!!.children.toList().joinAll() } }
            assertEquals(HealthPhase.IDLE, model.state.phase)
            assertNull(model.state.sample)
        } finally { release.countDown(); scope.cancel() }
    }
}
