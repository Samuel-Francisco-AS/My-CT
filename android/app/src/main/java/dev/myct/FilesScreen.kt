package dev.myct

import android.content.ClipData
import android.content.ClipboardManager
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
fun FilesScreen(model: FilesModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var notificationsGranted by remember { mutableStateOf(Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsGranted = it
        Toast.makeText(context, if (it) "Notificações permitidas para as próximas transferências."
            else "Mantenha o app visível durante a transferência.", Toast.LENGTH_LONG).show()
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
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Column(modifier.padding(horizontal = 8.dp)) {
            Text("Arquivos · SFTP", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            if (model.recoveryNotice.isNotEmpty()) {
                Text(model.recoveryNotice, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { model.dismissRecoveryNotice() }) { Text("Entendi") }
            }
            Text(listing?.path ?: "Diretório inicial", maxLines = 3, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp))
            Row {
                TextButton(enabled = !model.busy && listing != null && listing.path != "/",
                    onClick = { listing?.let { openDirectory(RemotePath.parent(it.path)) } }) { Text("↑ Pai") }
                TextButton(enabled = !model.busy, onClick = { model.load() }) { Text("Atualizar") }
                TextButton(enabled = !model.busy && listing != null, onClick = {
                    if (model.beginUploadSelection()) upload.launch(arrayOf("*/*"))
                }) { Text("Enviar…") }
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                TextButton(onClick = { saveScroll(); model.toggleHiddenFiles() }) {
                    Text(if (showHidden) "Ocultar ocultos" else "Mostrar ocultos")
                }
                listing?.let {
                    Text("${visibleEntries.size} de ${it.entries.size} itens",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("Com notificações permitidas, a transferência continua em segundo plano enquanto o serviço e a rede permanecerem ativos. Sem serviço, mantenha o app visível. Sem retomada automática.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!notificationsGranted && Build.VERSION.SDK_INT >= 33) TextButton(onClick = {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }) { Text("Permitir notificações de transferência") }
            if (model.busy) {
                if (status.phase == FilePhase.TRANSFERRING && status.total != null && status.total > 0)
                    LinearProgressIndicator(progress = { (status.bytes.toFloat() / status.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(enabled = status.phase != FilePhase.CANCELLING, onClick = { model.cancel() }) { Text("Cancelar operação") }
            }
            Text(status.message, style = MaterialTheme.typography.bodySmall,
                color = if (status.phase == FilePhase.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.heightIn(max = 110.dp).verticalScroll(rememberScrollState()).padding(vertical = 4.dp))
            if (status.name.isNotEmpty()) Text("${status.name} · ${formatFileSize(status.bytes)} / ${status.total?.let(::formatFileSize) ?: "?"}", maxLines = 2,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            model.completedDownload?.let { uri ->
                TextButton(onClick = {
                    try {
                        val reported = context.contentResolver.getType(uri)
                        val inferred = MimeTypeMap.getSingleton().getMimeTypeFromExtension(status.name.substringAfterLast('.', "").lowercase())
                        val mime = reported?.takeUnless { it == "application/octet-stream" } ?: inferred ?: "application/octet-stream"
                        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    } catch (_: Exception) { Toast.makeText(context, "Download concluído, mas o visualizador/provedor não abriu o documento. Isso não comprova corrupção. Tente pelo gerenciador Android.", Toast.LENGTH_LONG).show() }
                }) { Text("Abrir arquivo baixado") }
            }
            LazyColumn(Modifier.weight(1f), state = listState) {
                items(visibleEntries, key = { it.name }) { entry ->
                    ListItem(headlineContent = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text("${when (entry.kind) { RemoteKind.DIRECTORY -> "Pasta"; RemoteKind.LINK -> "Link simbólico"; RemoteKind.FILE -> "Arquivo · ${formatFileSize(entry.size)}"; RemoteKind.OTHER -> "Tipo especial (sem transferência)" }} · ${dateFormat.format(Date(entry.modified * 1000))}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }, modifier = Modifier.clickable(enabled = !model.busy) {
                            when (entry.kind) {
                                RemoteKind.DIRECTORY -> listing?.let { openDirectory(RemotePath.child(it.path, entry.name)) }
                                RemoteKind.FILE -> model.selectDownload(entry)
                                RemoteKind.LINK -> link = entry
                                RemoteKind.OTHER -> Unit
                            }
                        })
                    HorizontalDivider()
                }
            }
        }
        model.uploadChoice?.let { choice ->
            var name by remember(choice) { mutableStateOf(choice.name) }
            AlertDialog(onDismissRequest = { model.dismissUpload() }, title = { Text("Enviar arquivo") },
                text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Destino: ${choice.directory}")
                    Text("${choice.size?.let(::formatFileSize) ?: "Tamanho desconhecido"}${choice.size?.let { " ($it bytes)" } ?: ""}. Existentes não serão substituídos.")
                    OutlinedTextField(name, { if (it.length <= 512) name = it }, label = { Text("Nome no Fedora") }, singleLine = true)
                } }, confirmButton = { TextButton(onClick = { model.upload(name) }) { Text("Enviar") } },
                dismissButton = { TextButton(onClick = { model.dismissUpload() }) { Text("Cancelar") } })
        }
        model.downloadChoice?.let { choice ->
            AlertDialog(onDismissRequest = { model.dismissDownload() }, title = { Text("Arquivo no Fedora") },
                text = { Text("${choice.entry.name} · ${formatFileSize(choice.entry.size)} (${choice.entry.size} bytes)\nEscolha um novo documento no Android. Nomes existentes são tratados pelo seletor SAF. Se interrompido, o provedor pode manter um arquivo parcial.") },
                confirmButton = { Column {
                    TextButton(onClick = { model.inspectSelected() }) { Text("Verificar arquivo") }
                    TextButton(onClick = { download.launch(choice.entry.name) }) { Text("Escolher destino…") }
                } },
                dismissButton = { TextButton(onClick = { model.dismissDownload() }) { Text("Cancelar") } })
        }
        if (model.inspectionVisible) {
            var checksum by remember { mutableStateOf("") }
            AlertDialog(onDismissRequest = { model.closeInspection() }, title = { Text("Verificar arquivo") },
                text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (model.inspecting) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(model.inspectionMessage)
                    model.inspection?.let { result ->
                        Text("Nome: ${result.name}")
                        Text("Localização: ${result.path}")
                        Text("Tamanho: ${formatFileSize(result.bytes)} (${result.bytes} bytes)")
                        Text("Extensão: ${result.extension.ifEmpty { "Sem extensão" }}")
                        Text("Formato por conteúdo (MIME): ${result.type.ifEmpty { "Não identificado" }}")
                        val declaredType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(result.extension.lowercase())
                        if (declaredType != null && result.type.isNotEmpty() && declaredType != result.type)
                            Text("A extensão sugere $declaredType; o conteúdo foi identificado como ${result.type}.")
                        Text("SHA-256: ${result.sha256}")
                        TextButton(onClick = {
                            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("SHA-256", result.sha256))
                            Toast.makeText(context, "SHA-256 copiado", Toast.LENGTH_SHORT).show()
                        }) { Text("Copiar SHA-256") }
                        OutlinedTextField(checksum, { if (it.length <= 256) checksum = it },
                            label = { Text("SHA-256 conhecido (opcional)") }, modifier = Modifier.fillMaxWidth())
                        val comparison = compareChecksum(result.sha256, checksum)
                        Text(comparison, style = MaterialTheme.typography.titleMedium,
                            color = if (comparison == "DIVERGENTE") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        Text(result.warning, style = MaterialTheme.typography.bodySmall)
                    }
                    AntivirusScanSection(model.antivirus, enabled = !model.inspecting)
                } }, confirmButton = { TextButton(onClick = { model.closeInspection() }) {
                    Text(if (model.inspecting) "Cancelar inspeção" else "Fechar")
                } })
        }
        link?.let { entry ->
            AlertDialog(onDismissRequest = { link = null }, title = { Text("Link simbólico") },
                text = { Text("${entry.name}\nAbrir seguirá o link somente para navegar, se o destino for um diretório acessível. Links não são baixados nem modificados por esta tela.") },
                confirmButton = { TextButton(onClick = { listing?.let { openDirectory(RemotePath.child(it.path, entry.name)) }; link = null }) { Text("Abrir diretório") } },
                dismissButton = { TextButton(onClick = { link = null }) { Text("Cancelar") } })
        }
    }
}
