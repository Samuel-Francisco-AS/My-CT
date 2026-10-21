package dev.myct

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Ephemeral UI input only. Removing a String reference is not guaranteed memory
 * erasure on the JVM; transport ByteArrays are cleared separately after use. */
internal class SetupCredential : LifecycleEventObserver {
    var text by mutableStateOf("")
    var confirmation by mutableStateOf(false)
    fun clear() { text = ""; confirmation = false }
    fun connectionChanged() = clear()
    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) clear()
    }
}

@Composable
internal fun SetupCredentialLifecycle(credential: SetupCredential) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, credential) {
        owner.lifecycle.addObserver(credential)
        onDispose { owner.lifecycle.removeObserver(credential); credential.clear() }
    }
}
