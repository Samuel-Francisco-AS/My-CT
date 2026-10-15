package dev.myct

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
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
fun DownloadsScreen(model: DownloadsModel, filesBusy: Boolean, openFiles: (String) -> Unit, modifier: Modifier = Modifier) {
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
    LazyColumn(modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Downloads · Fedora", style = MaterialTheme.typography.titleMedium)
            Text("HTTP/HTTPS público. Uma transferência ativa por vez, sem fila. Continua no Fedora ao fechar o app.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(url, { if (it.length <= 8192) url = it }, label = { Text("URL HTTP/HTTPS") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = model.formEnabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
            OutlinedTextField(name, { if (it.length <= 256) name = it }, label = { Text("Nome opcional (padrão: download)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = model.formEnabled,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { model.start(url, name) }, enabled = model.connected && !model.busy && url.isNotBlank()) { Text("Baixar") }
                TextButton(onClick = { model.refresh() }, enabled = model.connected && !model.busy) { Text("Atualizar") }
            }
            // Keep the form geometry stable; passive polling must not steal focus or move it.
            Box(Modifier.fillMaxWidth().height(4.dp)) {
                if (model.showOperationProgress) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Text(model.message, style = MaterialTheme.typography.bodySmall)
            if (model.stale) Text("Dados anteriores; estado atual não confirmado.", color = MaterialTheme.colorScheme.error)
        }
        items(model.items, key = { it.id }) { item ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.name, style = MaterialTheme.typography.titleSmall)
                    Text(item.state.label)
                    Text("${formatFileSize(item.bytes)} recebidos (${item.bytes} bytes)" +
                        (item.total?.let { " / ${formatFileSize(it)} ($it bytes)" } ?: " · total desconhecido"))
                    item.progress?.let { Text("${(it * 100).toInt()} %") }
                    if (item.active && model.connected && !model.stale) {
                        val progress = item.progress
                        if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                        else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    }
                    if (item.message.isNotBlank()) Text(item.message, style = MaterialTheme.typography.bodySmall)
                    if (item.active) TextButton(onClick = { cancel = item }, enabled = model.connected && !model.busy && !model.stale) { Text("Cancelar…") }
                    if (item.state == DownloadState.completed) {
                        TextButton(onClick = { openFiles(item.directory) }, enabled = model.connected && !filesBusy) { Text("Ver em Arquivos") }
                        if (filesBusy) Text("Aguarde a operação SFTP para abrir a pasta.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    cancel?.let { item ->
        AlertDialog(onDismissRequest = { cancel = null }, title = { Text("Cancelar transferência?") },
            text = { Text("${item.name}: o arquivo parcial será preservado no Fedora. Não há retomada nesta fase.") },
            confirmButton = { TextButton(onClick = { cancel = null; model.cancel(item.id) }, enabled = model.connected && !model.busy) { Text("Cancelar download") } },
            dismissButton = { TextButton(onClick = { cancel = null }) { Text("Continuar baixando") } })
    }
}
