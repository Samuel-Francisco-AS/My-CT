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
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeading("Saúde", "Métricas reais do Core pelo túnel SSH. Memória e disco utilizados = total − disponível. Disco considera espaço disponível ao usuário. Uptime é do processo Core, não do Fedora.")
        ScreenPanel {
            StatusNotice(state.message, state.phase == HealthPhase.ERROR)
            state.fetchedAt?.let { StatusNotice("Última consulta: ${DateFormat.getDateTimeInstance().format(Date(it))}") }
            if (state.sample != null && state.phase != HealthPhase.READY) StatusNotice("Dados anteriores · estado atual não confirmado", true)
            Box(Modifier.fillMaxWidth().height(4.dp)) {
                if (state.phase == HealthPhase.LOADING) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            MyCtButton(onClick = model::refresh, enabled = state.phase != HealthPhase.NO_SSH && state.phase != HealthPhase.LOADING) { Text("Atualizar") }
        }
        val sample = state.sample
        if (sample == null) ScreenPanel { StatusNotice("Sem medições. Conecte ao Fedora e consulte o Core.") }
        else {
            ScreenPanel {
                Text("CPU", style = MaterialTheme.typography.labelLarge)
                Text(String.format(Locale.getDefault(), "%.1f %%", sample.cpuPercent), style = MaterialTheme.typography.headlineMedium)
                LinearProgressIndicator(progress = { (sample.cpuPercent / 100).toFloat() }, modifier = Modifier.fillMaxWidth())
            }
            UsagePanel("RAM", sample.ramUsed, sample.ramTotal, sample.ramAvailable)
            UsagePanel("Disco /", sample.diskUsed, sample.diskTotal, sample.diskAvailable)
            ScreenPanel {
                Text("Uptime do Core", style = MaterialTheme.typography.labelLarge)
                Text(healthUptime(sample.uptimeSeconds), style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@Composable
private fun UsagePanel(title: String, used: Long, total: Long, available: Long) {
    ScreenPanel {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Text(healthBytes(used), style = MaterialTheme.typography.headlineMedium)
        LinearProgressIndicator(progress = { (used.toDouble() / total).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        DataLine("Total", healthBytes(total))
        DataLine("Disponível", healthBytes(available))
    }
}
