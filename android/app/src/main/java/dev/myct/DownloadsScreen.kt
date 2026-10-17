package dev.myct

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun HttpDownloadsScreen(model: DownloadsModel, filesBusy: Boolean, openFiles: (String) -> Unit, modifier: Modifier = Modifier) {
    val activity = checkNotNull(LocalActivity.current as? androidx.activity.ComponentActivity)
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var cancel by remember(model.connected) { mutableStateOf<ServerDownload?>(null) }
    var visible by remember { mutableStateOf(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(activity, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) visible = true
            if (event == Lifecycle.Event.ON_STOP) { visible = false; model.hide(); cancel = null }
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer); model.hide() }
    }
    LaunchedEffect(visible, model.connected) {
        if (visible && model.connected) while (isActive) {
            model.refresh(passive = true)
            delay(4000)
        }
    }
    LazyColumn(modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
        item(key = "form") {
            ScreenPanel {
                ScreenHeading("Novo download", "HTTP/HTTPS público. Uma transferência ativa por vez, sem fila. Continua no Fedora ao fechar o app; não depende de manter SSH. O nome padrão é download. Cancelar preserva o arquivo parcial, sem retomada automática.")
                OutlinedTextField(url, { if (it.length <= 8192) url = it }, label = { Text("URL HTTP/HTTPS") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = model.formEnabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
                OutlinedTextField(name, { if (it.length <= 256) name = it }, label = { Text("Nome opcional") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = model.formEnabled,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MyCtButton(onClick = { model.start(url, name) }, enabled = model.actionsEnabled && url.isNotBlank()) { Text("Baixar") }
                    MyCtTextButton(onClick = { model.refresh() }, enabled = model.actionsEnabled) { Text("Atualizar") }
                }
                Box(Modifier.fillMaxWidth().height(4.dp)) {
                    if (model.showOperationProgress) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                StatusNotice(model.message)
                if (model.stale) StatusNotice("Dados anteriores · estado atual não confirmado", true)
            }
        }
        item(key = "history") { Text("Histórico", style = MaterialTheme.typography.titleMedium) }
        if (model.items.isEmpty()) item(key = "empty") { ScreenPanel { StatusNotice("Nenhuma operação para exibir.") } }
        items(model.items, key = { it.id }) { item ->
            ScreenPanel {
                DetailValue("Download", item.name)
                if (model.stale || !model.connected) StatusNotice("Dados anteriores · estado atual não confirmado", true)
                Text(item.state.label, style = MaterialTheme.typography.labelLarge,
                    color = when (item.state) {
                        DownloadState.completed -> MaterialTheme.colorScheme.tertiary
                        DownloadState.failed, DownloadState.interrupted -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    })
                DataLine("Recebido", formatFileSize(item.bytes))
                DataLine("Total", item.total?.let(::formatFileSize) ?: "Desconhecido")
                val progress = item.progress
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text("${(progress * 100).toInt()} %", style = MaterialTheme.typography.labelSmall)
                } else if (item.active && model.connected && !model.stale) LinearProgressIndicator(Modifier.fillMaxWidth())
                StatusNotice(item.message, item.state == DownloadState.failed)
                if (item.active) MyCtTextButton(onClick = { cancel = item }, enabled = model.actionsEnabled && !model.stale) { Text("Cancelar…") }
                if (item.state == DownloadState.completed) {
                    MyCtTextButton(onClick = { openFiles(item.directory) }, enabled = model.connected && !filesBusy) { Text("Ver em Arquivos") }
                    if (filesBusy) StatusNotice("Aguarde a operação SFTP para abrir a pasta.")
                }
                ExpandDetails {
                    DetailValue("Destino", item.path)
                    DataLine("Bytes recebidos", item.bytes.toString())
                }
            }
        }
    }
    cancel?.let { item ->
        AlertDialog(onDismissRequest = { cancel = null }, title = { Text("Cancelar transferência?") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { DetailValue("Download", item.name); Text("O arquivo parcial será preservado no Fedora. Não há retomada nesta fase.") } },
            confirmButton = { MyCtTextButton(onClick = { cancel = null; model.cancel(item.id) }, enabled = model.actionsEnabled) { Text("Cancelar download") } },
            dismissButton = { MyCtTextButton(onClick = { cancel = null }) { Text("Continuar baixando") } })
    }
}
