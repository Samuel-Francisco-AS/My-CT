package dev.myct

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal fun formatCleanupBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    var value = bytes.toDouble()
    var index = -1
    val units = listOf("KiB", "MiB", "GiB", "TiB")
    do { value /= 1024.0; index++ } while (value >= 1024 && index < units.lastIndex)
    return String.format(java.util.Locale.ROOT, "%.1f %s", value, units[index])
}

@Composable
internal fun MediaStorageScreen(model: MediaModel, openFiles: (String) -> Unit, modifier: Modifier = Modifier) {
    var cloud by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val panels = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!cloud, { cloud = false }, label = { Text("Biblioteca local") })
            FilterChip(cloud, { cloud = true }, label = { Text("Nuvem") })
        }
        panels.SaveableStateProvider(cloud) {
            if (cloud) CloudScreen(model.cloud, openFiles, Modifier.fillMaxSize())
            else MediaLocalStorageScreen(model, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun MediaLocalStorageScreen(model: MediaModel, modifier: Modifier = Modifier) {
    val marked = remember { mutableStateMapOf<String, Boolean>() }
    val openFolders = remember { mutableStateMapOf<String, Boolean>() }
    var mode by remember { mutableStateOf("links") }
    var confirm by remember { mutableStateOf(false) }
    val files = model.cleanupFiles
    val grouped = files.groupBy { it.path.split('/').take(2).joinToString("/") }.toSortedMap()
    val selected = files.filter { it.eligible && marked[it.path] == true }
    val selectedPaths = selected.map { it.path }.sorted()
    val selection = if (selectedPaths.isNotEmpty() && selectedPaths.size <= 256)
        CleanupSelection(mode, selectedPaths) else null
    val plan = model.cleanupPlan?.takeIf { it.mode == mode && model.cleanupSelection == selection }
    LaunchedEffect(files) {
        val allowed = files.filter { it.eligible }.map { it.path }.toSet()
        marked.keys.toList().filterNot { it in allowed }.forEach { marked.remove(it) }
    }
    Column(modifier) {
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp)) {
            item("header") {
                ScreenPanel {
                    ScreenHeading("Gerenciar armazenamento",
                        "Selecione arquivos, temporadas ou pastas. Todas as exclusões exigem prévia.")
                    StatusNotice(model.message)
                    MyCtButton(onClick = { model.loadCleanup() },
                        enabled = model.connected && !model.busy) {
                        Text("Consultar arquivos da biblioteca")
                    }
                    if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (model.stale) StatusNotice(
                        "Estado incerto: consulte novamente antes de excluir.", true)
                    Text("Só arquivos comprovadamente ligados a torrents My-CT ficam disponíveis.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            grouped.forEach { (folder, entries) ->
                val expanded = openFolders[folder] == true
                val eligible = entries.filter { it.eligible }
                val count = eligible.count { marked[it.path] == true }
                item("folder:" + folder) {
                    ScreenPanel {
                        Row(Modifier.fillMaxWidth().clickable {
                            openFolders[folder] = !expanded
                        }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (expanded) "▾" else "▸")
                            Column(Modifier.weight(1f)) {
                                Text(folder.substringAfter('/'),
                                    style = MaterialTheme.typography.titleSmall)
                                Text("$count/${eligible.size} selecionados · " +
                                    formatCleanupBytes(entries.sumOf { it.bytes }),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MyCtTextButton(onClick = { openFolders[folder] = !expanded }) {
                                Text(if (expanded) "Recolher" else "Ver arquivos")
                            }
                            MyCtTextButton(onClick = {
                                if (eligible.isNotEmpty() && count == eligible.size) {
                                    eligible.forEach { marked.remove(it.path) }
                                } else {
                                    eligible.forEach {
                                        if (marked.size < 256 || marked[it.path] == true)
                                            marked[it.path] = true
                                    }
                                }
                            }, enabled = model.connected && !model.busy && eligible.isNotEmpty()) {
                                Text(if (count == eligible.size && eligible.isNotEmpty())
                                    "Desmarcar pasta" else "Marcar pasta")
                            }
                        }
                    }
                }
                if (expanded) {
                    val seasons = entries.groupBy { it.path.substringBeforeLast('/') }.toSortedMap()
                    seasons.forEach { (subfolder, children) ->
                        val expandedSub = openFolders[subfolder] == true
                        val eligibleSub = children.filter { it.eligible }
                        val countSub = eligibleSub.count { marked[it.path] == true }
                        item("subfolder:" + subfolder) {
                            Row(Modifier.fillMaxWidth().padding(start = 14.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(if (expandedSub) "▾" else "▸",
                                    modifier = Modifier.clickable {
                                        openFolders[subfolder] = !expandedSub
                                    }.padding(8.dp))
                                Text(subfolder.substringAfterLast('/'),
                                    modifier = Modifier.weight(1f).clickable {
                                        openFolders[subfolder] = !expandedSub
                                    })
                                MyCtTextButton(onClick = {
                                    if (eligibleSub.isNotEmpty() && countSub == eligibleSub.size)
                                        eligibleSub.forEach { marked.remove(it.path) }
                                    else eligibleSub.forEach {
                                        if (marked.size < 256 || marked[it.path] == true)
                                            marked[it.path] = true
                                    }
                                }, enabled = model.connected && !model.busy && eligibleSub.isNotEmpty()) {
                                    Text(if (countSub == eligibleSub.size && eligibleSub.isNotEmpty())
                                        "Desmarcar" else "Marcar")
                                }
                            }
                        }
                        if (expandedSub) items(children, key = { "media:" + it.path }) { file ->
                            Row(Modifier.fillMaxWidth().clickable(
                                enabled = file.eligible && model.connected && !model.busy
                            ) {
                                if (marked[file.path] == true) marked.remove(file.path)
                                else if (marked.size < 256) marked[file.path] = true
                            }.padding(horizontal = 14.dp, vertical = 3.dp)) {
                                Checkbox(marked[file.path] == true, onCheckedChange = null,
                                    enabled = file.eligible && model.connected && !model.busy)
                                Column(Modifier.weight(1f)) {
                                    Text(file.path.substringAfterLast('/'),
                                        style = MaterialTheme.typography.bodySmall)
                                    Text(if (!file.eligible) "Não verificado — protegido"
                                        else if (file.managed) "Link gerenciado"
                                        else "Link verificado pelo inode",
                                        style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
            if (plan != null) item("preview") {
                ScreenPanel {
                    ScreenHeading(if (mode == "links") "Prévia: somente hardlinks"
                        else "Prévia: exclusão definitiva", "")
                    DataLine("Selecionados", plan.selected.toString())
                    DataLine("Volume selecionado (não garante espaço livre)",
                        formatCleanupBytes(plan.bytes))
                    if (mode == "links")
                        Text("Os arquivos originais e o seeding serão preservados.")
                    else {
                        Text("Esta ação remove os torrents completos, inclusive arquivos extras, " +
                            "e encerra seu seeding.")
                        plan.torrents.forEach { torrent ->
                            Text(torrent.name.ifBlank { torrent.hash.take(12) },
                                style = MaterialTheme.typography.bodySmall)
                            if (torrent.videosOutsideLibrary > 0) Text(
                                "${torrent.videosOutsideLibrary} outros vídeos fora da biblioteca " +
                                "também serão excluídos.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    plan.blockers.forEach { StatusNotice(it, true) }
                }
            }
        }
        Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().padding(10.dp)) {
                Text("${selected.size} selecionados", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MyCtButton(onClick = {
                        mode = "links"
                        if (selectedPaths.isNotEmpty())
                            model.previewCleanup(CleanupSelection("links", selectedPaths))
                    }, enabled = selection != null && model.actionsEnabled) {
                        Text("Retirar links…")
                    }
                    MyCtTextButton(onClick = {
                        mode = "storage"
                        if (selectedPaths.isNotEmpty())
                            model.previewCleanup(CleanupSelection("storage", selectedPaths))
                    }, enabled = selection != null && model.actionsEnabled) {
                        Text("Liberar espaço…")
                    }
                }
                if (plan != null && plan.canApply) {
                    MyCtButton(onClick = { confirm = true }, enabled = model.actionsEnabled) {
                        Text(if (mode == "links") "Confirmar retirada dos links…"
                            else "Excluir dados dos torrents…")
                    }
                }
            }
        }
    }
    if (confirm && plan != null && plan.canApply && selection != null) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(if (mode == "links") "Retirar hardlinks?"
            else "EXCLUSÃO DEFINITIVA: encerrar e apagar torrents?") },
        text = { Text(if (mode == "links")
            "Os ${plan.selected} hardlinks serão retirados da biblioteca. " +
            "Os arquivos originais e o seeding permanecerão intactos."
        else "O Transmission encerrará o seeding e excluirá todos os arquivos " +
            "dos ${plan.torrents.size} torrents mostrados na prévia, mesmo extras. " +
            "Depois os hardlinks selecionados serão retirados. Esta ação é irreversível.") },
        confirmButton = {
            MyCtTextButton(onClick = {
                confirm = false
                model.applyCleanup(selection, plan.fingerprint)
            }, enabled = model.actionsEnabled) {
                Text(if (mode == "links") "Retirar links" else "Excluir definitivamente")
            }
        },
        dismissButton = {
            MyCtTextButton(onClick = { confirm = false }) { Text("Cancelar") }
        }
    )
}
