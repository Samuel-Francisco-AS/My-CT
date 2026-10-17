package dev.myct

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
internal fun DownloadsScreen(http: DownloadsModel, torrents: TorrentsModel, filesBusy: Boolean,
                             openFiles: (String) -> Unit, modifier: Modifier = Modifier) {
    var section by rememberSaveable { mutableIntStateOf(0) }
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            FilterChip(section == 0, { section = 0 }, label = { Text("HTTP/HTTPS") })
            FilterChip(section == 1, { section = 1 }, label = { Text("Torrents") })
        }
        if (section == 0) HttpDownloadsScreen(http, filesBusy, openFiles, Modifier.weight(1f))
        else TorrentsScreen(torrents, filesBusy, openFiles, Modifier.weight(1f))
    }
}

@Composable
internal fun TorrentsScreen(model: TorrentsModel, filesBusy: Boolean, openFiles: (String) -> Unit, modifier: Modifier) {
    val activity = checkNotNull(LocalActivity.current as? androidx.activity.ComponentActivity)
    var magnet by remember { mutableStateOf("") }
    var remove by remember(model.connected) { mutableStateOf<ServerTorrent?>(null) }
    var visible by remember { mutableStateOf(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(activity, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) visible = true
            if (event == Lifecycle.Event.ON_STOP) { visible = false; model.hide(); remove = null }
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
            Text("Torrents · Fedora", style = MaterialTheme.typography.titleMedium)
            Text("Continua no Fedora ao fechar o app. Concluído pode continuar compartilhando (seeding); use Pausar para parar. Limites do motor são preservados.", style = MaterialTheme.typography.bodySmall)
            Text("Novos torrents: pasta por hash durante o download; título real ao concluir. Pastas anteriores são mantidas.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(magnet, { if (it.length <= 8192) magnet = it }, label = { Text("Magnet legal e confiável") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = model.formEnabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MyCtButton(onClick = { model.add(magnet) }, enabled = model.actionsEnabled && magnet.isNotBlank()) { Text("Adicionar torrent") }
                MyCtTextButton(onClick = { model.refresh() }, enabled = model.actionsEnabled) { Text("Atualizar") }
            }
            Box(Modifier.fillMaxWidth().height(4.dp)) {
                if (model.showOperationProgress) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Text(model.message, style = MaterialTheme.typography.bodySmall)
            if (model.stale) Text("Dados anteriores; estado atual não confirmado.", color = MaterialTheme.colorScheme.error)
        }
        items(model.items, key = { it.hash }) { item ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.name, style = MaterialTheme.typography.titleSmall)
                    Text("ID ${item.id} · ${item.state.label}")
                    if (item.progress == null) {
                        Text("Aguardando metadados; tamanho/progresso indisponíveis.")
                        if (!item.paused) LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        Text("${(item.progress * 100).toInt()} % · ${item.bytes?.let { formatFileSize(it) } ?: "?"} / ${item.total?.let { formatFileSize(it) } ?: "?"}")
                        LinearProgressIndicator(progress = { item.progress }, modifier = Modifier.fillMaxWidth())
                    }
                    Text("↓ ${item.down?.let { formatFileSize(it) + "/s" } ?: "?"} · ↑ ${item.up?.let { formatFileSize(it) + "/s" } ?: "?"} · peers ${item.peers ?: "?"}")
                    Text(item.directory, style = MaterialTheme.typography.bodySmall)
                    if (item.error.isNotBlank()) Text(item.error, color = MaterialTheme.colorScheme.error)
                    Row {
                        MyCtTextButton(onClick = { model.control(if (item.paused) "resume" else "pause", item.hash) },
                            enabled = model.actionsEnabled && !model.stale) { Text(if (item.paused) "Retomar" else "Pausar") }
                        MyCtTextButton(onClick = { remove = item }, enabled = model.actionsEnabled && !model.stale) { Text("Remover…") }
                    }
                    MyCtTextButton(onClick = { openFiles(item.directory) }, enabled = model.connected && !filesBusy) { Text("Ver em Arquivos") }
                }
            }
        }
    }
    remove?.let { item ->
        AlertDialog(onDismissRequest = { remove = null }, title = { Text("Remover torrent da lista?") },
            text = { Text("${item.name}: para a transferência e o compartilhamento. Arquivos completos e parciais serão preservados no Fedora.") },
            confirmButton = { MyCtTextButton(onClick = { remove = null; model.control("remove", item.hash) }, enabled = model.actionsEnabled) { Text("Remover e preservar dados") } },
            dismissButton = { MyCtTextButton(onClick = { remove = null }) { Text("Voltar") } })
    }
}
