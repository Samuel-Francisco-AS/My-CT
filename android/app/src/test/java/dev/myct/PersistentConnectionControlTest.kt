package dev.myct

import com.termux.terminal.TerminalSession
import org.junit.Assert.*
import org.junit.Test

class PersistentConnectionControlTest {
    private fun session() = TerminalSession(object : TerminalSession.Transport {
        override fun send(data: ByteArray) = Unit
        override fun resize(columns: Int, rows: Int, widthPixels: Int, heightPixels: Int) = Unit
    }, SessionCallbacks())

    @Test fun disconnectedOrConnectingNeverRequestsAServiceOrConfirmation() {
        var calls = 0
        for (persistent in listOf(false, true)) for (requested in listOf(false, true)) {
            requestPersistenceChange(false, persistent, requested, { calls++ }, { calls++ })
        }
        assertEquals(0, calls)
    }

    @Test fun activationRequestsExistingEnableCallbackWithoutOptimisticState() {
        var enables = 0; var confirmations = 0
        val serviceState = false // Only the real service may acknowledge activation.
        requestPersistenceChange(true, serviceState, true, { enables++ }, { confirmations++ })
        assertEquals(1, enables)
        assertEquals(0, confirmations)
        assertFalse(serviceState)
    }

    @Test fun disableOnlyRequestsConfirmationAndCancellationLeavesSessionAlone() {
        var enables = 0; var confirmations = 0; var stops = 0
        val current = session()
        requestPersistenceChange(true, true, false, { enables++ }, { confirmations++ })
        assertEquals(0, enables)
        assertEquals(1, confirmations)
        // Cancel clears the pending session; an already queued confirmation can no longer stop it.
        confirmPersistenceStop(true, true, null, current) { stops++ }
        assertEquals(0, stops)
    }

    @Test fun unchangedRequestsDoNothing() {
        var calls = 0
        listOf(false, true).forEach { checked ->
            requestPersistenceChange(true, checked, checked, { calls++ }, { calls++ })
        }
        assertEquals(0, calls)
    }

    @Test fun confirmationStopsOnlyTheSameStillPersistentConnectedSession() {
        val original = session(); val reconnected = session()
        var stops = 0
        assertTrue(canConfirmPersistenceStop(true, true, original, original))
        confirmPersistenceStop(false, true, original, original) { stops++ }
        confirmPersistenceStop(true, false, original, original) { stops++ }
        confirmPersistenceStop(true, true, original, reconnected) { stops++ }
        confirmPersistenceStop(true, true, original, null) { stops++ }
        confirmPersistenceStop(true, true, null, original) { stops++ }
        assertEquals(0, stops)
        confirmPersistenceStop(true, true, original, original) { stops++ }
        assertEquals(1, stops)
    }
}
