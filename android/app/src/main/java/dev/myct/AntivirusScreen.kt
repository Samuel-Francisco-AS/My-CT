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
    MyCtPanelRule()
    ScreenHeading("Antivírus", "Análise local sob demanda no Fedora, sem modificar o arquivo. SHA-256 verifica integridade e não executa antivírus. Limites de tempo, memória e expansão podem tornar a análise incompleta. Até 2 GB decimais.")
    val result = model.result
    if (result == null && !model.busy) StatusNotice("Não analisado · SHA-256 não é uma análise antivírus")
    if (model.busy) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        StatusNotice(model.message)
        MyCtTextButton(onClick = { model.cancel() }) { Text("Cancelar análise") }
    } else {
        result?.let {
            val failed = it.state != AvState.CLEAN
            Text("${it.state.name} · ${it.state.label}", style = MaterialTheme.typography.titleMedium,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary)
            when (it.state) {
                AvState.CLEAN -> StatusNotice("Nenhuma ameaça encontrada com as assinaturas utilizadas. Isso não comprova segurança.")
                AvState.DETECTED -> StatusNotice("Detecção informativa. Nenhuma ação foi realizada sobre o arquivo.", true)
                else -> StatusNotice("Não há confirmação de análise completa.", true)
            }
            it.detections.forEach { detection -> StatusNotice("Detecção: $detection", true) }
            if (it.info?.stale == true || it.info?.available == false) StatusNotice("Assinaturas desatualizadas ou indisponíveis", true)
            it.durationMs?.let { ms -> DataLine("Duração", "${ms / 1000.0} s") }
            ExpandDetails("Assinaturas e consumo") {
                it.info?.let { info -> AntivirusInfo(info) }
                it.rssKib?.let { rss -> DataLine("Pico RSS", formatFileSize(rss * 1024)) }
            }
        }
        StatusNotice(model.message, result?.state in setOf(AvState.ERROR, AvState.INCOMPLETE, AvState.UNAVAILABLE))
        if (model.tooLarge) StatusNotice(AV_LIMIT_NOTICE, true)
        StatusNotice("Análise não destrutiva; limites podem impedir a conclusão.")
        MyCtTextButton(onClick = { model.scan() }, enabled = model.canScan && enabled) { Text("Verificar antivírus") }
    }
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
    ScreenHeading("Antivírus e assinaturas", "A atualização continua no Fedora ao fechar Opções. Agenda existente: 36 horas. Uma base atualizada não comprova que um arquivo é seguro.")
    ScreenPanel {
        model.info?.let { info ->
            Text(if (info.available) "ClamAV disponível" else "ClamAV indisponível", style = MaterialTheme.typography.titleMedium)
            AntivirusInfo(info)
            StatusNotice("Última atualização: ${avDate(info.lastSuccess)}")
        }
        model.update?.let {
            StatusNotice(if (it.running && it.phase == "WAITING") "WAITING · aguardando análise" else
                if (it.running) "RUNNING · atualização em andamento" else "Resultado: ${it.result}")
        }
        if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        StatusNotice(model.message)
        if (model.unconfirmed) StatusNotice("Dados anteriores · estado atual não confirmado", true)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MyCtTextButton(onClick = { model.requestUpdate() }, enabled = model.connected && !model.busy) { Text("Atualizar assinaturas") }
            MyCtTextButton(onClick = { model.refresh() }, enabled = model.connected && !model.busy) { Text("Consultar estado") }
        }
    }
}
