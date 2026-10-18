package dev.myct

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Naturally orders episode_2 before episode_10 even without zero-padding. */
internal fun mediaNaturalCompare(a: String, b: String): Int {
    val tokens = Regex("""\d+|\D+""")
    val left = tokens.findAll(a).map { it.value }.toList()
    val right = tokens.findAll(b).map { it.value }.toList()
    for (i in 0 until minOf(left.size, right.size)) {
        val x = left[i].toIntOrNull()
        val y = right[i].toIntOrNull()
        val result = if (x != null && y != null) x.compareTo(y)
            else left[i].compareTo(right[i], ignoreCase = true)
        if (result != 0) return result
    }
    return left.size.compareTo(right.size)
}

private fun folderKey(torrent: String, path: String) = "$torrent/$path"

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
    var editEpisodes by remember { mutableStateOf(false) }
    var showDestinations by remember { mutableStateOf(false) }
    val expandedFolders = remember { mutableStateMapOf<String, Boolean>() }
    val marked = remember { mutableStateMapOf<String, Boolean>() }
    val episodeOverrides = remember { mutableStateMapOf<String, String>() }
    val selected = model.sources.filter { marked[it.id] == true }
        .sortedWith { a, b -> mediaNaturalCompare(a.path, b.path) }
    val sourceFolders = model.sources.groupBy {
        it.torrent to it.path.substringBeforeLast('/', "")
    }.toList()
    val selectedByFolder = selected.groupBy {
        folderKey(it.torrent, it.path.substringBeforeLast('/', ""))
    }
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

    val lazyState = rememberLazyListState()
    val jumpScope = rememberCoroutineScope()
    // Every expanded video is its own LazyColumn row (not a giant folder card).
    val folderRows = sourceFolders.sumOf { (key, files) ->
        1 + if (expandedFolders[folderKey(key.first, key.second)] == true) files.size else 0
    }
    val episodesAnchor = 2 + folderRows
    val episodesRows = if (kind != "movie" && selected.isNotEmpty())
        1 + if (editEpisodes) selected.size else 0 else 0
    val previewAnchor = episodesAnchor + episodesRows
    fun jumpTo(index: Int) {
        jumpScope.launch { lazyState.animateScrollToItem(index) }
    }
    LaunchedEffect(plan) {
        if (plan != null) lazyState.animateScrollToItem(previewAnchor)
    }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip(onClick = { jumpTo(0) }, label = { Text("Dados") })
            AssistChip(onClick = { jumpTo(1) }, label = { Text("Pastas") })
            if (kind != "movie" && selected.isNotEmpty())
                AssistChip(onClick = { jumpTo(episodesAnchor) }, label = { Text("Episódios") })
            AssistChip(onClick = { jumpTo(previewAnchor) }, label = { Text("Prévia") })
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp),
            state = lazyState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp)) {
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
        sourceFolders.forEach { (key, files) ->
            val name = folderKey(key.first, key.second)
            val expanded = expandedFolders[name] == true
            val count = selectedByFolder[name]?.size ?: 0
            item(key = "folder:" + name) {
                ScreenPanel {
                    Row(
                        Modifier.fillMaxWidth().clickable { expandedFolders[name] = !expanded },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(if (expanded) "▾" else "▸",
                            style = MaterialTheme.typography.titleLarge)
                        Column(Modifier.weight(1f)) {
                            Text(key.second.ifEmpty { "Raiz do torrent" },
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            // The first file distinguishes torrents that have no subfolder.
                            Text(files.firstOrNull()?.name.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("$count/${files.size} selecionados · ${key.first.take(10)}…",
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MyCtTextButton(onClick = { expandedFolders[name] = !expanded }) {
                            Text(if (expanded) "Recolher" else "Ver arquivos")
                        }
                        if (kind != "movie") {
                            MyCtTextButton(onClick = {
                                if (count == files.size) files.forEach { marked.remove(it.id) }
                                else files.forEach {
                                    if (marked.size < 128 || marked[it.id] == true)
                                        marked[it.id] = true
                                }
                            }, enabled = model.connected && !model.busy) {
                                Text(if (count == files.size) "Desmarcar" else "Marcar todos")
                            }
                        }
                    }
                }
            }
            if (expanded) {
                items(files.sortedWith { a, b -> mediaNaturalCompare(a.path, b.path) },
                    key = { "file:" + it.id }) { file ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = model.connected && !model.busy) {
                                if (marked[file.id] == true) marked.remove(file.id)
                                else {
                                    if (kind == "movie") marked.clear()
                                    if (marked.size < 128) marked[file.id] = true
                                }
                            }
                            .padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = marked[file.id] == true,
                            onCheckedChange = null,
                            enabled = model.connected && !model.busy
                        )
                        Text(file.name, modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (kind != "movie" && selected.isNotEmpty()) {
            item(key = "episode-header") {
                ScreenPanel {
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Numeração", style = MaterialTheme.typography.titleMedium)
                            Text("${selected.size} episódios · início $firstEpisode",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        MyCtTextButton(onClick = { editEpisodes = !editEpisodes }) {
                            Text(if (editEpisodes) "Recolher" else "Editar")
                        }
                    }
                    if (!validNumbers)
                        StatusNotice("Há números repetidos, inválidos ou vazios.", true)
                    else Text("Revise a ordem sugerida e expanda para corrigir os episódios.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            if (editEpisodes) {
                itemsIndexed(selected, key = { _, file -> "number:" + file.id }) { index, file ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(file.name, modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        OutlinedTextField(
                            value = if (episodeOverrides.containsKey(file.id))
                                episodeOverrides[file.id].orEmpty()
                                else first?.plus(index)?.toString().orEmpty(),
                            onValueChange = { episodeOverrides[file.id] = it.take(3) },
                            label = { Text("EP") },
                            modifier = Modifier.width(88.dp),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }
                }
            }
        }
        item(key = "preview-action") {
            ScreenPanel {
                Text("Prévia dos destinos", style = MaterialTheme.typography.titleMedium)
                if (spec == null)
                    Text("Marque os arquivos e preencha os dados para gerar a prévia.",
                        style = MaterialTheme.typography.bodySmall)
                if (plan != null) {
                    HorizontalDivider()
                    DataLine("Prontos para importar", plan.ready.toString())
                    DataLine("Já existentes", plan.existing.toString())
                    DataLine("Conflitos", plan.collisions.toString())
                    if (plan.collisions > 0)
                        StatusNotice("Destino ocupado. Desmarque ou renumere os arquivos conflitantes.", true)
                    MyCtTextButton(onClick = {
                        showDestinations = !showDestinations
                    }) {
                        Text(if (showDestinations) "Recolher destinos"
                             else "Ver ${plan.items.size} destinos")
                    }
                }
            }
        }
        if (plan != null && showDestinations) {
            items(plan.items, key = { "plan:" + it.id }) { entry ->
                ScreenPanel {
                    Text(entry.name, style = MaterialTheme.typography.bodySmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    DetailValue("Destino", entry.target)
                    StatusNotice(when (entry.status) {
                        "ready" -> "Pronto para criar hardlink."
                        "existing" -> "Já existente com o mesmo inode."
                        else -> "COLISÃO: destino ocupado."
                    }, entry.status == "collision")
                }
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
