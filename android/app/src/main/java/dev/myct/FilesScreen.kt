package dev.myct

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.app.Activity
import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.result.contract.ActivityResultContract
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

private class CreateRemoteDocument : ActivityResultContract<String, Uri?>() {
    override fun createIntent(context: Context, input: String): Intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType(MimeTypeMap.getSingleton().getMimeTypeFromExtension(input.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream")
        .putExtra(Intent.EXTRA_TITLE, input)
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

@Composable
internal fun FilesScreen(model: FilesModel, modifier: Modifier = Modifier, cloud: CloudModel? = null) {
    val context = LocalContext.current
    val navigationFeedback = rememberNavigationFeedback()
    var notificationsGranted by remember { mutableStateOf(Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) }
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsGranted = it
        Toast.makeText(context, if (it) "Notificações permitidas para as próximas transferências."
            else "Mantenha o app visível durante a transferência.", Toast.LENGTH_LONG).show()
        val action = afterPermission; afterPermission = null; action?.invoke()
    }
    fun withTransferPermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            afterPermission = action
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else action()
    }
    val upload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { model.pickedUpload(it) }
    val download = rememberLauncherForActivityResult(CreateRemoteDocument()) { model.pickedDownload(it) }
    var link by remember { mutableStateOf<RemoteEntry?>(null) }
    val listing = model.listing
    val status = model.status
    val showHidden = model.showHiddenFiles
    val visibleEntries = remember(listing?.entries, showHidden) {
        visibleSftpEntries(listing?.entries.orEmpty(), showHidden)
    }
    // Key by directory and visibility: never draw an old scroll index in a filtered list.
    val listState = key(listing?.path, showHidden) {
        val saved = listing?.let { model.scrollPosition(it.path) }
        rememberLazyListState(
            initialFirstVisibleItemIndex = saved?.restoredIndex(visibleEntries) ?: 0,
            initialFirstVisibleItemScrollOffset = saved?.offset ?: 0
        )
    }
    fun saveScroll() {
        listing?.let { current ->
            model.rememberScroll(current.path, visibleEntries,
                listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }
    }
    fun openDirectory(path: String) {
        if (path != listing?.path) navigationFeedback()
        saveScroll()
        model.load(path)
    }
    DisposableEffect(model, listing?.path, showHidden) {
        val current = listing
        val currentEntries = visibleEntries
        onDispose {
            // Navigating already saved the old folder; avoid storing a clamped new-list position.
            if (current != null && model.listing?.path == current.path) {
                model.rememberScroll(current.path, currentEntries,
                    listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
            }
        }
    }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    LaunchedEffect(model) { if (model.listing == null && !model.busy) model.load(null) }
    var help by remember { mutableStateOf(false) }
    var statusDetails by remember { mutableStateOf(false) }
    fun openCompleted() {
        model.completedDownload?.let { uri ->
            try {
                val reported = context.contentResolver.getType(uri)
                val inferred = MimeTypeMap.getSingleton().getMimeTypeFromExtension(status.name.substringAfterLast('.', "").lowercase())
                val mime = reported?.takeUnless { it == "application/octet-stream" } ?: inferred ?: "application/octet-stream"
                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (_: Exception) { Toast.makeText(context, "Download concluído, mas o visualizador/provedor não abriu o documento. Isso não comprova corrupção. Tente pelo gerenciador Android.", Toast.LENGTH_LONG).show() }
        }
    }
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Column(modifier.padding(horizontal = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = !model.busy && listing != null && listing.path != "/",
                    onClick = { listing?.let { openDirectory(RemotePath.parent(it.path)) } }) {
                    Icon(FileGlyphs.Parent, contentDescription = "Subir um diretório", tint = if (!model.busy && listing != null && listing.path != "/") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                }
                DetailValue("Caminho", listing?.path ?: "Diretório inicial", maxLines = 1, modifier = Modifier.weight(1f), showLabel = false)
                IconButton(onClick = { help = true }, modifier = Modifier.semantics { contentDescription = "Ajuda de arquivos e transferências" }) { Text("ⓘ") }
            }
            if (model.recoveryNotice.isNotEmpty()) ScreenPanel {
                StatusNotice(model.recoveryNotice, true)
                MyCtTextButton(onClick = { model.dismissRecoveryNotice() }) { Text("Entendi") }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                MyCtTextButton(enabled = !model.busy, onClick = { model.load() }) { Text("Atualizar") }
                MyCtTextButton(enabled = !model.busy && listing != null, onClick = {
                    if (model.beginUploadSelection()) withTransferPermission { upload.launch(arrayOf("*/*")) }
                }) { Text("Enviar…") }
                MyCtTextButton(onClick = { saveScroll(); model.toggleHiddenFiles() },
                    modifier = Modifier.semantics { contentDescription = if (showHidden) "Ocultar arquivos ocultos" else "Mostrar arquivos ocultos" }) {
                    Text(".*", color = if (showHidden) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("${visibleEntries.size}", style = MaterialTheme.typography.labelSmall)
            }
            // This geometry never changes when a row starts listing/transfer: no viewport shrink/recenter.
            Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable { statusDetails = true }) {
                    Text(status.message, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                        color = if (status.phase == FilePhase.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (status.phase == FilePhase.TRANSFERRING && !model.transferKeepsConnection)
                        StatusNotice("Sem serviço · mantenha o app visível", true)
                    else if (status.name.isNotEmpty()) Text("${status.name} · ${formatFileSize(status.bytes)} / ${status.total?.let(::formatFileSize) ?: "?"}",
                        maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = { model.cancel() }, enabled = model.busy && status.phase != FilePhase.CANCELLING) { Text("×", modifier = Modifier.semantics { contentDescription = "Cancelar operação" }) }
                IconButton(onClick = { openCompleted() }, enabled = model.completedDownload != null) { Text("↗", modifier = Modifier.semantics { contentDescription = "Abrir arquivo baixado" }) }
            }
            Box(Modifier.fillMaxWidth().height(4.dp)) {
                if (model.busy) {
                    if (status.phase == FilePhase.TRANSFERRING && status.total != null && status.total > 0)
                        LinearProgressIndicator(progress = { (status.bytes.toFloat() / status.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            LazyColumn(Modifier.weight(1f).panelEntrance(listing?.path), state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(visibleEntries, key = { it.name }) { entry ->
                    Surface(shape = MyCtGeometry.Control, color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Row(Modifier.fillMaxWidth().clickable(enabled = !model.busy) {
                            when (entry.kind) {
                                RemoteKind.DIRECTORY -> listing?.let { openDirectory(RemotePath.child(it.path, entry.name)) }
                                RemoteKind.FILE -> model.selectDownload(entry)
                                RemoteKind.LINK -> link = entry
                                RemoteKind.OTHER -> Unit
                            }
                        }.heightIn(min = 64.dp).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(FileGlyphs.forKind(entry.kind), contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(24.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                Text("${when (entry.kind) { RemoteKind.DIRECTORY -> "Pasta"; RemoteKind.LINK -> "Link simbólico"; RemoteKind.FILE -> "Arquivo · ${formatFileSize(entry.size)}"; RemoteKind.OTHER -> "Tipo especial · sem transferência" }} · ${dateFormat.format(Date(entry.modified * 1000))}",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        if (help) ReportDialog("Arquivos e transferências", { help = false }, actions = {
            if (!notificationsGranted && Build.VERSION.SDK_INT >= 33) MyCtTextButton(onClick = {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }) { Text("Permitir notificações") }
        }) {
            Text("Toque no caminho para consultar/copiar o endereço completo. .* alterna os arquivos ocultos. Links exigem confirmação e não são baixados nem modificados.")
            Text("A permissão de notificação é solicitada ao iniciar a escolha de uma transferência. Com serviço e rede ativos, ela pode continuar em segundo plano; sem serviço, mantenha o app visível. Sem retomada automática.")
        }
        if (statusDetails) ReportDialog("Estado da operação", { statusDetails = false }) {
            Text(status.message)
            if (status.name.isNotEmpty()) DetailValue("Arquivo", status.name)
            DataLine("Recebido/enviado", formatFileSize(status.bytes))
            DataLine("Total", status.total?.let(::formatFileSize) ?: "Desconhecido")
        }
        model.uploadChoice?.let { choice ->
            var name by remember(choice) { mutableStateOf(choice.name) }
            AlertDialog(onDismissRequest = { model.dismissUpload() }, title = { Text("Enviar arquivo") },
                text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                    DetailValue("Destino", choice.directory)
                    Text("${choice.size?.let(::formatFileSize) ?: "Tamanho desconhecido"}${choice.size?.let { " ($it bytes)" } ?: ""}. Existentes não serão substituídos.")
                    OutlinedTextField(name, { if (it.length <= 512) name = it }, label = { Text("Nome no Fedora") }, singleLine = true)
                } }, confirmButton = { MyCtTextButton(onClick = { model.upload(name) }) { Text("Enviar") } },
                dismissButton = { MyCtTextButton(onClick = { model.dismissUpload() }) { Text("Cancelar") } })
        }
        model.downloadChoice?.let { choice ->
            AlertDialog(onDismissRequest = { model.dismissDownload() }, title = { Text("Arquivo no Fedora") },
                text = { Column(Modifier.verticalScroll(rememberScrollState())) { DetailValue("Arquivo", choice.entry.name); Text("${formatFileSize(choice.entry.size)} · Escolha um novo documento no Android. Nomes existentes são tratados pelo seletor SAF. Se interrompido, o provedor pode manter um arquivo parcial.") } },
                confirmButton = { Column {
                    MyCtTextButton(onClick = { model.inspectSelected() }) { Text("Verificar arquivo") }
                    if (cloud != null) MyCtTextButton(onClick = {
                        model.dismissDownload(); cloud.prepareFile(choice.path)
                    }) { Text("Arquivar no Drive…") }
                    MyCtTextButton(onClick = { withTransferPermission { download.launch(choice.entry.name) } }) { Text("Escolher destino…") }
                } },
                dismissButton = { MyCtTextButton(onClick = { model.dismissDownload() }) { Text("Cancelar") } })
        }
        if (cloud != null) {
            CloudFileArchiveDialog(cloud)
            if (cloud.tasks.isNotEmpty()) Text(cloud.message, style = MaterialTheme.typography.bodySmall)
        }
        if (model.inspectionVisible) InspectionReport(model)
        link?.let { entry ->
            AlertDialog(onDismissRequest = { link = null }, title = { Text("Link simbólico") },
                text = { Column(Modifier.verticalScroll(rememberScrollState())) { DetailValue("Link", entry.name); Text("Abrir seguirá o link somente para navegar, se o destino for um diretório acessível. Links não são baixados nem modificados por esta tela.") } },
                confirmButton = { MyCtTextButton(onClick = { listing?.let { openDirectory(RemotePath.child(it.path, entry.name)) }; link = null }) { Text("Abrir diretório") } },
                dismissButton = { MyCtTextButton(onClick = { link = null }) { Text("Cancelar") } })
        }
    }
}
