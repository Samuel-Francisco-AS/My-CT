package dev.myct

import androidx.activity.compose.LocalActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
    var awaiting by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val activity = checkNotNull(LocalActivity.current as? androidx.activity.ComponentActivity)
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { confirm = false; awaiting = false; model.invalidateCleanup() }
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer); model.invalidateCleanup() }
    }
    val files = model.cleanupFiles
    val grouped = files.groupBy { it.path.split('/').take(2).joinToString("/") }.toSortedMap()
    val selected = files.filter { it.eligible && marked[it.path] == true }
    val selectedPaths = selected.map { it.path }.sorted()
    val selection = if (selectedPaths.isNotEmpty() && selectedPaths.size <= 256)
        CleanupSelection(mode, selectedPaths) else null
    val plan = model.cleanupPlan?.takeIf { it.mode == mode && model.cleanupSelection == selection }
    LaunchedEffect(plan) {
        if (awaiting && plan != null) { confirm = plan.canApply; awaiting = false }
    }
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
                        "Selecione arquivos, temporadas ou pastas. Confira os alvos em uma única confirmação.")
                    StatusNotice(model.message)
                    MyCtButton(onClick = { model.loadCleanup() },
                        enabled = model.connected && !model.busy) {
                        Text("Consultar arquivos da biblioteca")
                    }
                    if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (model.stale) StatusNotice(
                        "Estado incerto: consulte novamente antes de excluir.", true)
                    Text("Arquivos locais podem ser excluídos sem backup na Nuvem.",
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
                                        else "Arquivo local · gestão não comprovada",
                                        style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
            if (plan != null && !plan.canApply) item("blocked") {
                plan.blockers.forEach { StatusNotice(it, true) }
            }
        }
        Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().padding(10.dp)) {
                Text("${selected.size} selecionados", style = MaterialTheme.typography.labelMedium)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MyCtButton(onClick = {
                        mode = "links"
                        awaiting = true
                        if (selectedPaths.isNotEmpty())
                            model.previewCleanup(CleanupSelection("links", selectedPaths))
                    }, modifier = Modifier.fillMaxWidth(), enabled = selection != null && model.actionsEnabled) {
                        Text("Retirar da biblioteca…")
                    }
                    MyCtTextButton(onClick = {
                        mode = "storage"
                        awaiting = true
                        if (selectedPaths.isNotEmpty())
                            model.previewCleanup(CleanupSelection("storage", selectedPaths))
                    }, modifier = Modifier.fillMaxWidth(), enabled = selection != null && model.actionsEnabled) {
                        Text("Excluir permanentemente…")
                    }
                }

            }
        }
    }
    if (confirm && plan != null && plan.canApply && selection != null) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(if (mode == "links") "Retirar da biblioteca?"
            else "Excluir permanentemente?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(if (mode == "links") "Somente os caminhos abaixo serão retirados. Originais existentes serão preservados. Sem outra ligação, os dados do arquivo deixarão de existir."
                    else "Os caminhos abaixo serão apagados sem exigir backup. Esta ação é irreversível.")
                plan.targets.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (plan.torrents.isNotEmpty()) {
                    Text("Estas transferências serão retiradas do Transmission, interrompendo download e seeding. " +
                        "Os demais arquivos dos torrents serão preservados:")
                    plan.torrents.forEach { Text(it.name.ifBlank { it.hash }) }
                }
                Text("Outros hardlinks ou snapshots podem reter os dados. A liberação de espaço não é garantida.")
            }
        },
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
