package dev.myct

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
    val upload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { model.pickedUpload(it) }
    val download = rememberLauncherForActivityResult(CreateRemoteDocument()) { model.pickedDownload(it) }
    var link by remember { mutableStateOf<RemoteEntry?>(null) }
    val listing = model.listing
    val status = model.status
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    LaunchedEffect(model) { if (model.listing == null && !model.busy) model.load(null) }
    Column(modifier.padding(horizontal = 8.dp)) {
        Text("Arquivos · SFTP", style = MaterialTheme.typography.titleMedium)
        if (model.recoveryNotice.isNotEmpty()) {
            Text(model.recoveryNotice, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { model.dismissRecoveryNotice() }) { Text("Entendi") }
        }
        Text(listing?.path ?: "Diretório inicial", maxLines = 3, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp))
        Row {
            TextButton(enabled = !model.busy && listing != null && listing.path != "/",
                onClick = { listing?.let { model.load(RemotePath.parent(it.path)) } }) { Text("↑ Pai") }
            TextButton(enabled = !model.busy, onClick = { model.load() }) { Text("Atualizar") }
            TextButton(enabled = !model.busy && listing != null, onClick = {
                if (model.beginUploadSelection()) upload.launch(arrayOf("*/*"))
            }) { Text("Enviar…") }
        }
        Text("Transferências acompanhadas nesta tela. Sair do app cancela; girar ou alternar abas mantém a operação.",
            style = MaterialTheme.typography.labelSmall)
        if (model.busy) {
            if (status.phase == FilePhase.TRANSFERRING && status.total != null && status.total > 0)
                LinearProgressIndicator(progress = { (status.bytes.toFloat() / status.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(enabled = status.phase != FilePhase.CANCELLING, onClick = { model.cancel() }) { Text("Cancelar operação") }
        }
        Text(status.message, style = MaterialTheme.typography.bodySmall,
            color = if (status.phase == FilePhase.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.heightIn(max = 110.dp).verticalScroll(rememberScrollState()).padding(vertical = 4.dp))
        if (status.name.isNotEmpty()) Text("${status.name} · ${status.bytes} / ${status.total?.toString() ?: "?"} bytes", maxLines = 2,
            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
        model.completedDownload?.let { uri ->
            TextButton(onClick = {
                try {
                    val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (_: Exception) { Toast.makeText(context, "Nenhum aplicativo/provedor pôde abrir este documento. Use o gerenciador de arquivos Android.", Toast.LENGTH_LONG).show() }
            }) { Text("Abrir arquivo baixado") }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(listing?.entries ?: emptyList(), key = { it.name }) { entry ->
                ListItem(headlineContent = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text("${when (entry.kind) { RemoteKind.DIRECTORY -> "Pasta"; RemoteKind.LINK -> "Link simbólico"; RemoteKind.FILE -> "Arquivo · ${entry.size} bytes"; RemoteKind.OTHER -> "Tipo especial (sem transferência)" }} · ${dateFormat.format(Date(entry.modified * 1000))}", style = MaterialTheme.typography.labelSmall)
                    }, modifier = Modifier.clickable(enabled = !model.busy) {
                        when (entry.kind) {
                            RemoteKind.DIRECTORY -> listing?.let { model.load(RemotePath.child(it.path, entry.name)) }
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
                Text("${choice.size?.toString() ?: "Tamanho desconhecido"}${if (choice.size != null) " bytes" else ""}. Existentes não serão substituídos.")
                OutlinedTextField(name, { if (it.length <= 512) name = it }, label = { Text("Nome no Fedora") }, singleLine = true)
            } }, confirmButton = { TextButton(onClick = { model.upload(name) }) { Text("Enviar") } },
            dismissButton = { TextButton(onClick = { model.dismissUpload() }) { Text("Cancelar") } })
    }
    model.downloadChoice?.let { choice ->
        AlertDialog(onDismissRequest = { model.dismissDownload() }, title = { Text("Baixar arquivo") },
            text = { Text("${choice.entry.name} · ${choice.entry.size} bytes\nEscolha um novo documento no Android. Nomes existentes são tratados pelo seletor SAF. Se interrompido, o provedor pode manter um arquivo parcial.") },
            confirmButton = { TextButton(onClick = { download.launch(choice.entry.name) }) { Text("Escolher destino…") } },
            dismissButton = { TextButton(onClick = { model.dismissDownload() }) { Text("Cancelar") } })
    }
    link?.let { entry ->
        AlertDialog(onDismissRequest = { link = null }, title = { Text("Link simbólico") },
            text = { Text("${entry.name}\nAbrir seguirá o link somente para navegar, se o destino for um diretório acessível. Links não são baixados nem modificados por esta tela.") },
            confirmButton = { TextButton(onClick = { listing?.let { model.load(RemotePath.child(it.path, entry.name)) }; link = null }) { Text("Abrir diretório") } },
            dismissButton = { TextButton(onClick = { link = null }) { Text("Cancelar") } })
    }
}
