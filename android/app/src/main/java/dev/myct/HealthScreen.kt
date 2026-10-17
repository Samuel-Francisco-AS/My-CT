package dev.myct

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HealthScreen(model: HealthModel, modifier: Modifier = Modifier) {
    val state = model.state
    LaunchedEffect(model, state.phase == HealthPhase.NO_SSH) {
        if (state.phase != HealthPhase.NO_SSH) model.refresh()
    }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Saúde do Fedora", style = MaterialTheme.typography.titleLarge)
        Text(state.message)
        if (state.phase == HealthPhase.LOADING) LinearProgressIndicator(Modifier.fillMaxWidth())
        MyCtButton(onClick = model::refresh, enabled = state.phase != HealthPhase.NO_SSH && state.phase != HealthPhase.LOADING) { Text("Atualizar") }
        state.sample?.let { sample ->
            val date = DateFormat.getDateTimeInstance().format(Date(checkNotNull(state.fetchedAt)))
            Text("Última consulta: $date", style = MaterialTheme.typography.bodySmall)
            if (state.phase != HealthPhase.READY) Text("Dados anteriores — não representam o estado atual.", color = MaterialTheme.colorScheme.error)
            Text("CPU: ${String.format(Locale.getDefault(), "%.1f", sample.cpuPercent)} %")
            Text("RAM\nUtilizada: ${healthBytes(sample.ramUsed)}\nTotal: ${healthBytes(sample.ramTotal)}\nDisponível: ${healthBytes(sample.ramAvailable)}")
            Text("Disco /\nUtilizado: ${healthBytes(sample.diskUsed)}\nTotal: ${healthBytes(sample.diskTotal)}\nDisponível: ${healthBytes(sample.diskAvailable)}")
            Text("Uptime do Core: ${healthUptime(sample.uptimeSeconds)}")
            Text("Memória e disco utilizados = total − disponível. Disco considera espaço disponível ao usuário.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
