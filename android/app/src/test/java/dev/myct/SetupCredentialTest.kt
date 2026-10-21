package dev.myct

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.*
import org.junit.Test

class SetupCredentialTest {
    @Test fun realLifecycleStopDropsUnsentInputAndConfirmation() {
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this)
        }
        val credential = SetupCredential()
        owner.lifecycle.addObserver(credential)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        credential.text = "SYNTHETIC-UNSENT-SECRET"; credential.confirmation = true
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        assertEquals("", credential.text); assertFalse(credential.confirmation)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertEquals("", credential.text); assertFalse(credential.confirmation)
    }
    @Test fun disconnectReconnectCancelSendAndDisposeDoNotReuseInput() {
        val credential = SetupCredential()
        repeat(4) {
            credential.text = "SYNTHETIC-$it"; credential.confirmation = true
            if (it < 2) credential.connectionChanged() else credential.clear()
            assertEquals("", credential.text); assertFalse(credential.confirmation)
        }
        credential.connectionChanged()
        assertEquals("", credential.text)
    }
}
