package dev.myct

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * Manual organizer. The user chooses completed torrent files or a whole folder,
 * then determines their Jellyfin category and episode order. Server owns final paths.
 */
@Composable
internal fun MediaBatchScreen(model: MediaModel, modifier: Modifier = Modifier) {
    var kind by remember { mutableStateOf("anime") }
    var title by remember { mutableStateOf("") }
    var season by remember { mutableStateOf("1") }
    var firstEpisode by remember { mutableStateOf("1") }
    var year by remember { mutableStateOf("2026") }
    var confirmation by remember { mutableStateOf(false) }
    val marked = remember { mutableStateMapOf<String, Boolean>() }
    val episodeOverrides = remember { mutableStateMapOf<String, String>() }
    val selected = model.sources.filter { marked[it.id] == true }.sortedBy { it.path }
    val sourceFolders = model.sources.groupBy {
        it.torrent to it.path.substringBeforeLast('/', "")
    }.toList()
    val seasonValue = season.toIntOrNull()
    val first = firstEpisode.toIntOrNull()
    val yearValue = year.toIntOrNull()
    val numbers = selected.mapIndexed { index, file ->
        if (episodeOverrides.containsKey(file.id)) episodeOverrides[file.id]?.toIntOrNull()
        else first?.plus(index)
    }
    val validNumbers = kind == "movie" || (numbers.all { it != null && it in 1..999 } &&
        numbers.distinct().size == numbers.size)
    val formValid = title.trim().length in 1..100 && title.none { it.isISOControl() || it == '/' || it == '\\' } &&
        selected.isNotEmpty() && selected.size <= 128 && validNumbers &&
        (if (kind == "movie") selected.size == 1 && yearValue != null && yearValue in 1900..2100
        else seasonValue != null && seasonValue in 0..99)
    val spec = if (formValid) ManualBatch(kind, title.trim(),
        if (kind == "movie") null else seasonValue, if (kind == "movie") yearValue else null,
        selected.mapIndexed { index, file ->
            ManualSelection(file.id, if (kind == "movie") null else numbers[index])
        }) else null
    val plan = model.manualPlan?.takeIf { spec != null && spec == model.plannedSpec }

    LazyColumn(modifier.padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 20.dp)) {
        item("intro") {
            ScreenPanel {
                ScreenHeading("Organizar biblioteca",
                    "Escolha arquivos concluídos de um torrent ou pasta, defina o tipo e confira os destinos antes de criar hardlinks.")
                StatusNotice(model.message)
                if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (model.stale) StatusNotice("Atualize o estado remoto antes de confirmar importações.", true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(kind == "anime", { kind = "anime" }, label = { Text("Anime") })
                    FilterChip(kind == "series", { kind = "series" }, label = { Text("Série") })
                    FilterChip(kind == "movie", { kind = "movie" }, label = { Text("Filme") })
                }
                StatusNotice(when (kind) {
                    "anime" -> "Destino: Media/Series (biblioteca Animes já existente)."
                    "series" -> "Destino: Media/Series-TV. Configure a biblioteca Séries no Jellyfin antes de usar."
                    else -> "Destino: Media/Filmes. Configure a biblioteca Filmes no Jellyfin antes de usar."
                })
                OutlinedTextField(title, { title = it.take(100) }, label = {
                    Text(if (kind == "movie") "Título do filme" else "Título do anime ou série")
                }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (kind == "movie") {
                    OutlinedTextField(year, { year = it.take(4) }, label = { Text("Ano") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(season, { season = it.take(2) }, label = { Text("Temporada") },
                            singleLine = true, modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(firstEpisode, { firstEpisode = it.take(3) },
                            label = { Text("Primeiro episódio") },
                            singleLine = true, modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                }
            }
        }
        item("sources") {
            ScreenPanel {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Arquivos concluídos", style = MaterialTheme.typography.titleMedium)
                    Text("${selected.size} selecionados", style = MaterialTheme.typography.labelMedium)
                }
                MyCtButton(onClick = { model.loadSources() },
                    enabled = model.connected && !model.busy) {
                    Text("Consultar arquivos do Fedora")
                }
                StatusNotice("Selecione uma pasta inteira ou marque vídeos individuais. Máximo: 128 por lote." +
                    if (kind == "movie") " Para filmes, escolha um arquivo por vez." else "")
            }
        }
        items(sourceFolders, key = { it.first.first + "/" + it.first.second }) { group ->
            val files = group.second
            val folderName = group.first.second.ifEmpty { "Raiz do torrent" }
            val count = files.count { marked[it.id] == true }
            ScreenPanel {
                Text(folderName, style = MaterialTheme.typography.titleSmall)
                Text("Torrent ${group.first.first.take(12)}… · $count/${files.size} selecionados",
                    style = MaterialTheme.typography.bodySmall)
                if (kind != "movie") {
                    MyCtTextButton(onClick = {
                        if (count == files.size) files.forEach { marked.remove(it.id) }
                        else files.forEach {
                            if (marked.size < 128 || marked[it.id] == true) marked[it.id] = true
                        }
                    }, enabled = model.connected && !model.busy) {
                        Text(if (count == files.size) "Desmarcar pasta" else "Selecionar pasta inteira")
                    }
                }
                files.forEach { file ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked = marked[file.id] == true,
                            onCheckedChange = { active ->
                                if (active) {
                                    if (kind == "movie") marked.clear()
                                    if (marked.size < 128) marked[file.id] = true
                                } else marked.remove(file.id)
                            }, enabled = model.connected && !model.busy)
                        Text(file.name, modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (kind != "movie" && selected.isNotEmpty()) {
            item("episode-header") {
                ScreenPanel {
                    ScreenHeading("Numeração dos episódios",
                        "Numeração inicial sugerida pela ordem dos nomes. Você pode corrigir qualquer episódio individualmente.")
                    if (!validNumbers) StatusNotice("Corrija números vazios, repetidos ou fora de 1–999.", true)
                }
            }
            items(selected, key = { "number:" + it.id }) { file ->
                val index = selected.indexOfFirst { it.id == file.id }
                ScreenPanel {
                    Text(file.name, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(episodeOverrides[file.id] ?: first?.plus(index)?.toString().orEmpty(),
                        { episodeOverrides[file.id] = it.take(3) },
                        label = { Text("Episódio correspondente") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            }
        }
        item("preview-action") {
            ScreenPanel {
                MyCtButton(onClick = { spec?.let(model::planManual) },
                    enabled = spec != null && model.actionsEnabled) { Text("Pré-visualizar destinos") }
                if (spec == null) StatusNotice("Preencha título, categoria e episódios; selecione os arquivos desejados.")
                if (plan != null) {
                    HorizontalDivider()
                    DataLine("Prontos para importar", plan.ready.toString())
                    DataLine("Já existentes", plan.existing.toString())
                    DataLine("Conflitos", plan.collisions.toString())
                    if (plan.collisions > 0)
                        StatusNotice("Há destinos ocupados. Desmarque os itens em conflito ou corrija a numeração e consulte a prévia novamente.", true)
                    else if (plan.ready > 0)
                        MyCtButton(onClick = { confirmation = true }, enabled = model.actionsEnabled) {
                            Text("Criar ${plan.ready} hardlinks…")
                        }
                }
            }
        }
        if (plan != null) items(plan.items, key = { "plan:" + it.id }) { entry ->
            ScreenPanel {
                Text(entry.name, style = MaterialTheme.typography.bodySmall)
                DetailValue("Destino", entry.target)
                StatusNotice(when (entry.status) {
                    "ready" -> "Pronto para criar hardlink."
                    "existing" -> "Arquivo já existe com o mesmo inode; não será duplicado."
                    else -> "COLISÃO: já há outro arquivo neste destino."
                }, entry.status == "collision")
            }
        }
    }

    if (confirmation && plan != null && spec != null) AlertDialog(
        onDismissRequest = { confirmation = false },
        title = { Text("Importar lote para a biblioteca?") },
        text = { Text("Criar ${plan.ready} hardlinks em ${if (kind == "movie") "Filmes" else if (kind == "anime") "Animes" else "Séries"}? " +
            "O Transmission e seus arquivos originais não serão alterados. Os links gerenciados poderão ser retirados depois.") },
        confirmButton = { MyCtTextButton(onClick = { confirmation = false; model.importManual(spec) },
            enabled = model.actionsEnabled) { Text("Confirmar importação") } },
        dismissButton = { MyCtTextButton(onClick = { confirmation = false }) { Text("Cancelar") } })
}
