package dev.myct

import androidx.compose.runtime.*
import kotlinx.coroutines.*

class HealthModel internal constructor(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val fetch: (SshConnection) -> HealthSample = { it.health() }
) {
    private var connection: SshConnection? = null
    private var generation = 0
    private var job: Job? = null
    var state by mutableStateOf(HealthState())
        private set
    fun attach(value: SshConnection?) {
        generation++
        job?.cancel(); job = null
        connection = value
        // Samples belong to the authenticated host: never carry them into another session.
        state = if (value == null) state.attached(false) else HealthState(HealthPhase.IDLE)
    }
    fun refresh() {
        val current = connection ?: return
        if (state.phase == HealthPhase.LOADING) return
        val id = generation
        state = state.copy(phase = HealthPhase.LOADING)
        job = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { fetch(current) }
                if (id == generation) state = HealthState(HealthPhase.READY, result, System.currentTimeMillis())
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (id == generation) state = state.copy(phase = HealthPhase.ERROR)
            }
        }
    }
}
