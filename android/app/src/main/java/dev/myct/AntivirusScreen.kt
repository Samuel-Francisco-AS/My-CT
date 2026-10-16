package dev.myct

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.text.DateFormat
import java.util.Date

private fun avDate(seconds: Long?) = seconds?.let {
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it * 1000))
} ?: "Não registrada"

@Composable
private fun AntivirusInfo(info: AvInfo) {
    Text("ClamAV: ${info.engine ?: "Indisponível"}", style = MaterialTheme.typography.bodySmall)
    Text("Base daily: ${info.dailyVersion ?: "Indisponível"} · ${info.dailyDate ?: "Data indisponível"}",
        style = MaterialTheme.typography.bodySmall)
    Text("Assinaturas: ${if (!info.available) "Indisponíveis" else if (info.stale) "Desatualizadas" else "Atualizadas"}",
        color = if (info.stale || !info.available) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
}

@Composable
internal fun AntivirusScanSection(model: AntivirusScanModel, enabled: Boolean) {
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text("Antivírus", style = MaterialTheme.typography.titleMedium)
    Text("Análise local sob demanda no Fedora. SHA-256 verifica integridade; não executa o antivírus.", style = MaterialTheme.typography.bodySmall)
    val result = model.result
    if (result == null && !model.busy) Text("Não analisado")
    if (model.busy) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(model.message, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { model.cancel() }) { Text("Cancelar análise") }
    } else {
        result?.let {
            Text(it.state.label, style = MaterialTheme.typography.titleMedium,
                color = if (it.state == AvState.DETECTED || it.state == AvState.ERROR || it.state == AvState.INCOMPLETE)
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            if (it.state == AvState.CLEAN) Text("O ClamAV não encontrou ameaças com as assinaturas utilizadas. Isso não comprova segurança.",
                style = MaterialTheme.typography.bodySmall)
            if (it.state == AvState.DETECTED) Text("Resultado informativo. Nenhuma ação foi realizada sobre o arquivo.")
            it.detections.forEach { detection -> Text("Detecção: $detection", color = MaterialTheme.colorScheme.error) }
            it.info?.let { info -> AntivirusInfo(info) }
            it.durationMs?.let { ms -> Text("Duração: ${ms / 1000.0} s", style = MaterialTheme.typography.bodySmall) }
            var details by remember(result) { mutableStateOf(false) }
            if (it.rssKib != null) {
                TextButton(onClick = { details = !details }) { Text(if (details) "Ocultar detalhes" else "Detalhes de consumo") }
                if (details) Text("Pico RSS: ${formatFileSize(it.rssKib * 1024)}", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (model.message.isNotBlank()) Text(model.message, style = MaterialTheme.typography.bodySmall)
        if (model.tooLarge) Text(AV_LIMIT_NOTICE, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = { model.scan() }, enabled = model.canScan && enabled) { Text("Verificar antivírus") }
    }
    Text("Até 2 GB decimais; limites de tempo, memória e expansão podem tornar a análise incompleta. O arquivo permanece intocado.",
        style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun AntivirusSignaturesSection(model: AntivirusSignaturesModel) {
    val activity = checkNotNull(LocalActivity.current as? androidx.activity.ComponentActivity)
    var visible by remember { mutableStateOf(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(activity, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) visible = true
            if (event == Lifecycle.Event.ON_STOP) { visible = false; model.hide() }
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer); model.hide() }
    }
    LaunchedEffect(visible, model.connected) { if (visible && model.connected) model.show() else model.hide() }
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text("Antivírus e assinaturas", style = MaterialTheme.typography.titleMedium)
    model.info?.let { info ->
        Text(if (info.available) "ClamAV disponível" else "ClamAV indisponível")
        AntivirusInfo(info)
        Text("Última atualização bem-sucedida: ${avDate(info.lastSuccess)}", style = MaterialTheme.typography.bodySmall)
    }
    model.update?.let {
        Text(if (it.running && it.phase == "WAITING") "WAITING · aguardando análise" else
            if (it.running) "RUNNING · atualização em andamento" else "Resultado: ${it.result}", style = MaterialTheme.typography.bodySmall)
    }
    if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    Text(model.message, style = MaterialTheme.typography.bodySmall)
    if (model.unconfirmed) Text("Dados anteriores não confirmam o estado atual.", style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { model.requestUpdate() }, enabled = model.connected && !model.busy) { Text("Atualizar assinaturas") }
    TextButton(onClick = { model.refresh() }, enabled = model.connected && !model.busy) { Text("Consultar estado") }
    Text("A atualização continua no Fedora ao fechar Opções. A agenda existente é de 36 horas.", style = MaterialTheme.typography.bodySmall)
}
