package dev.myct

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** Hybrid workflow: explicit bulk confirmation, ambiguous files require named approval. */
@Composable
internal fun MediaScreen(model: MediaModel, modifier: Modifier = Modifier) {
    var mode by remember { mutableIntStateOf(0) }
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            FilterChip(mode == 0, { mode = 0 }, label = { Text("Organizar manualmente") })
            FilterChip(mode == 1, { mode = 1 }, label = { Text("Reconhecimento automático") })
        }
        if (mode == 0) MediaBatchScreen(model, Modifier.weight(1f))
        else MediaAutomaticScreen(model, Modifier.weight(1f))
    }
}

@Composable
private fun MediaAutomaticScreen(model: MediaModel, modifier: Modifier = Modifier) {
    var bulkConfirmation by remember { mutableStateOf(false) }
    var pendingApproval by remember { mutableStateOf<MediaPending?>(null) }
    var pendingRemoval by remember { mutableStateOf<MediaEntry?>(null) }
    var pendingSkip by remember { mutableStateOf<MediaPending?>(null) }

    LaunchedEffect(model.connected) {
        if (model.connected && model.preview == null && !model.busy) model.refresh()
    }
    val preview = model.preview
    LazyColumn(modifier.padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 16.dp)) {
        item("summary") {
            ScreenPanel {
                ScreenHeading("My-CT Media", "Transmission → hardlinks → Jellyfin. Importa vídeos concluídos sem mover os originais ou interromper o seeding.")
                StatusNotice(model.message)
                if (model.stale) StatusNotice("Estado remoto não confirmado. Atualize antes de repetir qualquer ação.", true)
                if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                MyCtButton(onClick = { model.refresh() },
                    enabled = model.connected && !model.busy) { Text("Atualizar prévia") }
                if (preview != null) {
                    HorizontalDivider()
                    DataLine("Reconhecidos", preview.proposals.size.toString())
                    DataLine("Já existentes", preview.existing.toString())
                    DataLine("Pendentes para decisão", preview.pending.size.toString())
                    DataLine("Ignorados", preview.ignored.toString())
                    DataLine("Hardlinks gerenciados", model.entries.size.toString())
                    MyCtButton(onClick = { bulkConfirmation = true },
                        enabled = model.actionsEnabled && preview.proposals.isNotEmpty()) {
                        Text("Importar reconhecidos…")
                    }
                }
            }
        }
        if (preview != null) {
            item("proposals") {
                ScreenPanel {
                    Text("Importação automática", style = MaterialTheme.typography.titleMedium)
                    StatusNotice("Uma importação em lote só começa após sua confirmação. Fontes e seeding são preservados.")
                    ExpandDetails("Ver destinos reconhecidos (${preview.proposals.size})") {
                        preview.proposals.forEach { proposal ->
                            DetailValue(proposal.target, proposal.id.substringBefore(':'), maxLines = 2)
                        }
                    }
                }
            }
            item("review-heading") { ScreenPanel { ScreenHeading("Revisão manual", "Nomes desconhecidos, propagandas ou colisões não entram automaticamente.") } }
            items(preview.pending, key = { "review:" + it.id }) { item ->
                ScreenPanel {
                    DetailValue("Arquivo", item.name.ifBlank { item.id.substringAfter(':') })
                    StatusNotice(item.reason)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MyCtTextButton(onClick = { pendingApproval = item }, enabled = model.actionsEnabled) { Text("Classificar…") }
                        MyCtTextButton(onClick = { pendingSkip = item }, enabled = model.actionsEnabled) { Text("Ignorar…") }
                    }
                }
            }
        }
        item("managed-heading") {
            ScreenPanel { ScreenHeading("Na biblioteca", "Remover um link gerenciado não apaga o arquivo do Transmission.") }
        }
        items(model.entries, key = { "managed:" + it.path }) { entry ->
            ScreenPanel {
                DetailValue("Hardlink", entry.path)
                MyCtTextButton(onClick = { pendingRemoval = entry }, enabled = model.actionsEnabled) {
                    Text("Retirar da biblioteca…")
                }
            }
        }
    }

    if (bulkConfirmation) AlertDialog(onDismissRequest = { bulkConfirmation = false },
        title = { Text("Importar os reconhecidos?") },
        text = { Text("Serão criados até ${preview?.proposals?.size ?: 0} hardlinks. Arquivos originais e torrents continuam nos mesmos lugares. Destinos ocupados não serão substituídos.") },
        confirmButton = { MyCtTextButton(onClick = { bulkConfirmation = false; model.sync() },
            enabled = model.actionsEnabled) { Text("Confirmar importação") } },
        dismissButton = { MyCtTextButton(onClick = { bulkConfirmation = false }) { Text("Cancelar") } })

    pendingSkip?.let { item ->
        AlertDialog(onDismissRequest = { pendingSkip = null },
            title = { Text("Ignorar arquivo?") },
            text = { Text("O arquivo continuará no Transmission, mas o My-CT Media não tentará importá-lo automaticamente.") },
            confirmButton = { MyCtTextButton(onClick = { pendingSkip = null; model.skip(item.id) },
                enabled = model.actionsEnabled) { Text("Ignorar") } },
            dismissButton = { MyCtTextButton(onClick = { pendingSkip = null }) { Text("Cancelar") } })
    }
    pendingRemoval?.let { entry ->
        AlertDialog(onDismissRequest = { pendingRemoval = null },
            title = { Text("Retirar da biblioteca?") },
            text = { Text("Remover somente o hardlink gerenciado:\n${entry.path}\n\nO arquivo original e o seeding do Transmission permanecem.") },
            confirmButton = { MyCtTextButton(onClick = { pendingRemoval = null; model.remove(entry.path) },
                enabled = model.actionsEnabled) { Text("Retirar link") } },
            dismissButton = { MyCtTextButton(onClick = { pendingRemoval = null }) { Text("Cancelar") } })
    }
    pendingApproval?.let { item ->
        var kind by remember(item.id) { mutableStateOf("series") }
        var title by remember(item.id) { mutableStateOf("") }
        var season by remember(item.id) { mutableStateOf("1") }
        var episode by remember(item.id) { mutableStateOf("1") }
        var year by remember(item.id) { mutableStateOf("2026") }
        val valid = title.trim().isNotEmpty() && (if (kind == "series")
            season.toIntOrNull() in 0..99 && episode.toIntOrNull() in 1..999
            else year.toIntOrNull() in 1900..2100)
        AlertDialog(onDismissRequest = { pendingApproval = null },
            title = { Text("Classificar arquivo") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(item.name, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(kind == "series", { kind = "series" }, label = { Text("Série / anime") })
                        FilterChip(kind == "movie", { kind = "movie" }, label = { Text("Filme") })
                    }
                    OutlinedTextField(title, { title = it.take(100) }, label = { Text("Título correto") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (kind == "series") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(season, { season = it.take(2) }, label = { Text("Temporada") },
                                modifier = Modifier.weight(1f), singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            OutlinedTextField(episode, { episode = it.take(3) }, label = { Text("Episódio") },
                                modifier = Modifier.weight(1f), singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        }
                    } else OutlinedTextField(year, { year = it.take(4) }, label = { Text("Ano") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    StatusNotice("A operação cria um novo hardlink. Não substitui arquivos existentes.")
                }
            },
            confirmButton = {
                MyCtTextButton(onClick = {
                    if (kind == "series") model.approveSeries(item.id, title.trim(), season.toInt(), episode.toInt())
                    else model.approveMovie(item.id, title.trim(), year.toInt())
                    pendingApproval = null
                }, enabled = model.actionsEnabled && valid) { Text("Adicionar") }
            },
            dismissButton = { MyCtTextButton(onClick = { pendingApproval = null }) { Text("Cancelar") } })
    }
}
