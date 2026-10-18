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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
internal fun DownloadsScreen(http: DownloadsModel, torrents: TorrentsModel, media: MediaModel, filesBusy: Boolean,
                             openFiles: (String) -> Unit, modifier: Modifier = Modifier) {
    var section by rememberSaveable { mutableIntStateOf(0) }
    val feedback = rememberNavigationFeedback()
    val panels = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    fun select(destination: Int) { if (section != destination) { feedback(); section = destination } }
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            FilterChip(section == 0, { select(0) }, label = { Text("HTTP/HTTPS") })
            FilterChip(section == 1, { select(1) }, label = { Text("Torrents") })
            FilterChip(section == 2, { select(2) }, label = { Text("Mídia") })
        }
        Box(Modifier.weight(1f).panelEntrance(section, if (section == 1) 1f else -1f)) {
            panels.SaveableStateProvider(section) {
                when (section) {
                    0 -> HttpDownloadsScreen(http, filesBusy, openFiles, Modifier.fillMaxSize())
                    1 -> TorrentsScreen(torrents, filesBusy, openFiles, Modifier.fillMaxSize())
                    else -> MediaScreen(media, Modifier.fillMaxSize())
                }
            }
        }
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
    LazyColumn(modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
        item(key = "form") {
            ScreenPanel {
                ScreenHeading("Novo torrent", "Magnet confiável. O motor continua no Fedora ao fechar o app. Concluídos podem continuar compartilhando (seeding); use Pausar para parar. Pasta por hash durante download, título real ao concluir; legados preservados. Acumulados são por torrent e podem exceder o tamanho atual. Ratio é o upload_ratio informado pelo Transmission (4.1: enviado / tamanho final), não uma divisão das taxas. Dados extras ausentes exigem o adaptador R2B, cuja instalação é manual e separada do Git.")
                OutlinedTextField(magnet, { if (it.length <= 8192) magnet = it }, label = { Text("Magnet legal e confiável") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = model.formEnabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
                StatusNotice("Concluídos podem continuar compartilhando. Use Pausar para parar.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MyCtButton(onClick = { model.add(magnet) }, enabled = model.actionsEnabled && magnet.isNotBlank()) { Text("Adicionar torrent") }
                    MyCtTextButton(onClick = { model.refresh() }, enabled = model.actionsEnabled) { Text("Atualizar") }
                }
                Box(Modifier.fillMaxWidth().height(4.dp)) {
                    if (model.showOperationProgress) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                StatusNotice(model.message)
                if (model.stale) StatusNotice("Dados anteriores · estado atual não confirmado", true)
            }
        }
        if (model.items.isEmpty()) item(key = "empty") { ScreenPanel { StatusNotice("Nenhum torrent para exibir.") } }
        items(model.items, key = { it.hash }) { item ->
            ScreenPanel {
                DetailValue("Torrent", item.name)
                if (model.stale || !model.connected) StatusNotice("Dados anteriores · estado atual não confirmado", true)
                Text(item.state.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                if (item.progress == null) {
                    StatusNotice("Aguardando metadados · tamanho e progresso indisponíveis")
                    if (!item.paused && model.connected && !model.stale) LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { item.progress }, modifier = Modifier.fillMaxWidth())
                    Text("${(item.progress * 100).toInt()} % · ${item.bytes?.let(::formatFileSize) ?: "?"} / ${item.total?.let(::formatFileSize) ?: "?"}")
                }
                DataLine(if (model.stale || !model.connected) "↓ Última taxa" else "↓ Agora", item.down?.let { formatFileSize(it) + "/s" } ?: "Indisponível")
                DataLine(if (model.stale || !model.connected) "↑ Última taxa" else "↑ Agora", item.up?.let { formatFileSize(it) + "/s" } ?: "Indisponível")
                DataLine("Peers", item.peers?.toString() ?: "Indisponível")
                HorizontalDivider()
                DataLine("Baixado acumulado", item.downloadedEver?.let(::formatFileSize) ?: "Indisponível")
                DataLine("Enviado acumulado", item.uploadedEver?.let(::formatFileSize) ?: "Indisponível")
                DataLine("Ratio (Transmission)", torrentRatioLabel(item.ratio))
                StatusNotice(item.error, true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MyCtTextButton(onClick = { model.control(if (item.paused) "resume" else "pause", item.hash) },
                        enabled = model.actionsEnabled && !model.stale) { Text(if (item.paused) "Retomar" else "Pausar") }
                    MyCtTextButton(onClick = { remove = item }, enabled = model.actionsEnabled && !model.stale) { Text("Remover…") }
                    MyCtTextButton(onClick = { openFiles(item.directory) }, enabled = model.connected && !filesBusy) { Text("Ver em Arquivos") }
                }
                if (filesBusy) StatusNotice("Aguarde a operação SFTP para abrir a pasta.")
                ExpandDetails {
                    DetailValue("Diretório", item.directory)
                    DetailValue("Hash", item.hash, maxLines = 1)
                    DataLine("ID", item.id.toString())
                }
            }
        }
    }
    remove?.let { item ->
        AlertDialog(onDismissRequest = { remove = null }, title = { Text("Remover torrent da lista?") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { DetailValue("Torrent", item.name); Text("Para a transferência e o compartilhamento. Arquivos completos e parciais serão preservados no Fedora.") } },
            confirmButton = { MyCtTextButton(onClick = { remove = null; model.control("remove", item.hash) }, enabled = model.actionsEnabled) { Text("Remover e preservar dados") } },
            dismissButton = { MyCtTextButton(onClick = { remove = null }) { Text("Voltar") } })
    }
}
