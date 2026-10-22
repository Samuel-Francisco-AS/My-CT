package dev.myct

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
internal fun CloudScreen(model: CloudModel, openFiles: (String) -> Unit, modifier: Modifier = Modifier) {
    val activity = checkNotNull(LocalActivity.current as? androidx.activity.ComponentActivity)
    var visible by remember { mutableStateOf(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) visible = true
            if (event == Lifecycle.Event.ON_STOP) { visible = false; model.invalidatePreview() }
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer); model.invalidatePreview() }
    }
    LaunchedEffect(visible, model.connected) {
        if (visible && model.connected) {
            model.refresh()
            while (isActive) {
                delay(3000)
                model.poll()
            }
        }
    }
    val listState = rememberLazyListState()
    val selected = model.selected
    val entry = model.entries.find { it.id == selected }
    val torrent = model.torrents.find { it.hash == (entry?.hash ?: selected) }
    BackHandler(selected != null) { model.select(null) }
    Column(modifier.padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (selected != null) MyCtTextButton(onClick = { model.select(null) }) { Text("‹ Nuvem") }
            else Text("Nuvem", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(8.dp))
            MyCtTextButton(onClick = { model.refreshFromUser() }, enabled = model.connected && !model.busy) { Text("Atualizar") }
        }
        if (model.busy || model.tasks.any { it.active }) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(model.message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp),
            color = if (model.stale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        if (model.stale && model.connected) StatusNotice("Estado atual não confirmado. Atualize antes de agir.", true)
        if (selected == null) LazyColumn(Modifier.weight(1f), state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            item("jobs") { CloudTasks(model) }
            item("heading") { Text("Pacotes torrent", style = MaterialTheme.typography.titleMedium) }
            items(model.entries.filter { it.kind == "torrent" }, key = { it.id }) { item ->
                val rpc = model.torrents.find { it.hash == item.hash }
                val task = model.tasks.firstOrNull { it.work.target in setOf(item.id, item.hash) }
                ScreenPanel(Modifier.clickable { model.select(item.id) }) {
                    Text(item.name, style = MaterialTheme.typography.titleSmall)
                    Text("${formatCleanupBytes(item.size)} · ${cloudState(item, rpc, task)}", style = MaterialTheme.typography.bodySmall)
                    Text("Local: ${if (item.local) "diretório presente; integridade a conferir" else "ausente"} · Drive: ${cloudCopyLabel(item.state)}", style = MaterialTheme.typography.bodySmall)
                    Text(if (item.restorable) "Restauração disponível (revalida ao iniciar)" else "Restauração indisponível", style = MaterialTheme.typography.labelSmall)
                    Text("Transmission: ${rpc?.state?.label ?: "ausente ou indisponível"}", style = MaterialTheme.typography.bodySmall)
                    Text(if (item.libraryChecked) "Jellyfin: ${item.existingLinks} nomes presentes · ${item.linkedFiles} hardlinks ligados ao pacote"
                        else "Jellyfin: existência a conferir em Consultar espaço", style = MaterialTheme.typography.labelSmall)
                    if (item.verifiedAt.isNotBlank()) Text("Último arquivamento: ${cloudDate(item.verifiedAt)} · ${cloudCopyLabel(item.state)}", style = MaterialTheme.typography.labelSmall)
                    MyCtTextButton(onClick = { model.select(item.id) }) { Text("Gerenciar ›") }
                }
            }
            val archivedHashes = model.entries.map { it.hash }.toSet()
            val candidates = model.torrents.filter { it.hash !in archivedHashes && it.progress == 1f }
            item("local-heading") { Text("Arquivar um torrent local", style = MaterialTheme.typography.titleMedium) }
            if (candidates.isEmpty()) item("no-local") { Text("Nenhum outro torrent gerenciado completo disponível.", style = MaterialTheme.typography.bodySmall) }
            items(candidates, key = { it.hash }) { item ->
                ScreenPanel(Modifier.clickable { model.select(item.hash) }) {
                    Text(item.name, style = MaterialTheme.typography.titleSmall)
                    Text("Local · ${item.total?.let(::formatCleanupBytes) ?: "Tamanho a conferir"} · ${item.state.label}", style = MaterialTheme.typography.bodySmall)
                    MyCtTextButton(onClick = { model.select(item.hash) }) { Text("Conferir para arquivar ›") }
                }
            }
            item("files-heading") {
                ScreenPanel {
                    ScreenHeading("Arquivos comuns", "Selecione um arquivo estável no explorador do Fedora. O original será preservado.")
                    MyCtTextButton(onClick = { openFiles(".") }, enabled = model.connected) { Text("Escolher em Arquivos…") }
                    MyCtTextButton(onClick = { model.start(CloudWork("file", listOf("status"))) }, enabled = model.actionsEnabled) { Text("Consultar acesso e cota do Drive") }
                    if (model.resultWork?.action == "status") model.result?.optJSONObject("quota_bytes")?.let { quota ->
                        if (quota.has("free")) DataLine("Livre no Drive", formatCleanupBytes(quota.getLong("free")))
                        if (quota.has("total")) DataLine("Cota", formatCleanupBytes(quota.getLong("total")))
                    }
                }
            }
            items(model.entries.filter { it.kind == "file" }, key = { it.id }) { file ->
                ScreenPanel {
                    Text(file.name, style = MaterialTheme.typography.titleSmall)
                    Text("${formatCleanupBytes(file.size)} · ${cloudCopyLabel(file.state)}", style = MaterialTheme.typography.bodySmall)
                    if (file.verifiedAt.isNotBlank()) Text(cloudDate(file.verifiedAt), style = MaterialTheme.typography.labelSmall)
                    if (file.restoreState.isNotBlank()) Text("Última restauração: ${cloudRestoreLabel(file.restoreState)}", style = MaterialTheme.typography.bodySmall)
                    MyCtTextButton(onClick = { model.start(CloudWork("file", listOf("restore", file.id))) },
                        enabled = model.actionsEnabled && file.restorable && file.restoreState != "restored") { Text("Restaurar arquivo") }
                }
            }
            if (model.resultWork?.kind == "file" && model.resultWork?.action == "restore") item("file-restore-result") {
                model.result?.optString("path")?.takeIf { it.isNotBlank() }?.let { path ->
                    ScreenPanel {
                        Text("Arquivo restaurado no Fedora.")
                        MyCtTextButton(onClick = { openFiles(path.substringBeforeLast('/')) }, enabled = model.connected) { Text("Ver em Arquivos") }
                    }
                }
            }
        } else key(selected) {
            CloudDetail(model, entry, torrent, Modifier.weight(1f))
        }
    }
    CloudFileArchiveDialog(model)
}

@Composable
private fun CloudTasks(model: CloudModel) {
    var cancelling by remember { mutableStateOf<CloudTask?>(null) }
    val recent = model.tasks.take(5)
    if (recent.isNotEmpty()) ScreenPanel {
        val active = recent.firstOrNull { it.active || it.uncertain }
        if (active != null) {
            Text("${active.work.title} · ${active.label}", style = MaterialTheme.typography.titleSmall)
            CloudTransferProgress(active, model.connected && !model.stale)
            if (active.canCancel) MyCtTextButton(onClick = { cancelling = active }, enabled = model.connected && !model.busy) { Text("Cancelar transferência…") }
            Text("Executado no Fedora. Pode fechar a tela ou desconectar.", style = MaterialTheme.typography.bodySmall)
        }
        ExpandDetails("Últimas operações") {
            recent.forEach { task ->
                val target = model.entries.firstOrNull { task.work.target in setOf(it.id, it.hash) }?.name
                    ?: model.torrents.firstOrNull { it.hash == task.work.target }?.name
                    ?: if (task.work.kind == "file" && task.work.action == "archive") task.work.target.substringAfterLast('/') else ""
                if (target.isNotBlank()) Text(target, style = MaterialTheme.typography.labelSmall, maxLines = 2)
                Row(Modifier.fillMaxWidth()) {
                    Text("${task.work.title} · ${task.label}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    MyCtTextButton(onClick = { model.viewTask(task) }, enabled = model.connected && !model.busy) { Text(if (task.active) "Detalhes / progresso" else "Resultado") }
                }
                if (task.id != active?.id) CloudTransferProgress(task, model.connected && !model.stale)
            }
        }
    }
    cancelling?.let { task ->
        val current = model.tasks.firstOrNull { it.id == task.id && it.work == task.work }
        val target = model.entries.firstOrNull { task.work.target in setOf(it.id, it.hash) }?.name
            ?: model.torrents.firstOrNull { it.hash == task.work.target }?.name
            ?: task.work.target.substringAfterLast('/')
        AlertDialog(onDismissRequest = { cancelling = null }, title = { Text("Cancelar transferência?") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(target)
                Text("Interrompe somente esta transferência no Fedora. Não apaga os arquivos originais nem backup anterior verificado. Pode restar um objeto parcial no Drive ou arquivos de restauração; o motor mantém suas verificações. Não haverá nova tentativa automática.")
                if (current?.canCancel != true) Text("A operação já terminou ou o cancelamento foi solicitado.")
            }
        }, confirmButton = { MyCtTextButton(onClick = { cancelling = null; model.cancel(task) },
            enabled = model.connected && !model.busy && current?.canCancel == true) { Text("Confirmar cancelamento") } },
            dismissButton = { MyCtTextButton(onClick = { cancelling = null }) { Text("Continuar transferência") } })
    }
}

@Composable
private fun CloudTransferProgress(task: CloudTask, connected: Boolean) {
    val progress = task.progress
    if (progress == null) {
        if (task.active && task.work.action in setOf("archive", "restore", "space")) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Telemetria indisponível para esta operação. Tempo estimado indisponível.", style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(task.active) {
        while (task.active && isActive) { now = System.currentTimeMillis(); delay(1000) }
    }
    val percent = progress.percent?.takeIf { task.state == "succeeded" || (task.active && it < 100) }
    if (percent != null) LinearProgressIndicator(progress = { (percent / 100).toFloat() }, modifier = Modifier.fillMaxWidth())
    else if (task.active) LinearProgressIndicator(Modifier.fillMaxWidth())
    val reading = progress.metric == "read"
    Text(task.label, style = MaterialTheme.typography.labelSmall)
    if (reading && progress.readPass != null) Text("Leitura ${progress.readPass} · porcentagem e ETA desta leitura", style = MaterialTheme.typography.labelSmall)
    if (reading) Text("Métricas desta fase de leitura; podem incluir releituras/cache. Não são tráfego de rede nem bytes físicos do SSD.", style = MaterialTheme.typography.bodySmall)
    val fraction = percent?.let { String.format(java.util.Locale.forLanguageTag("pt-BR"), "%.1f%%", kotlin.math.floor(it * 10) / 10) }
        ?: if (task.active && progress.bytesTotal != null && progress.bytesTotal > 0 && progress.bytesDone == progress.bytesTotal)
            if (reading) "Leitura da fase finalizada; consulta ainda em andamento" else "Transporte finalizado; verificação pendente" else "Porcentagem indisponível"
    Text(if (reading && percent != null) "$fraction desta fase" else fraction, style = MaterialTheme.typography.bodySmall)
    val total = progress.bytesTotal?.let { "${formatCleanupBytes(it)} ($it bytes)" } ?: "total desconhecido"
    Text("${if (reading) "Lidos: " else ""}${formatCleanupBytes(progress.bytesDone)} (${progress.bytesDone} bytes) / $total", style = MaterialTheme.typography.bodySmall)
    val liveRate = connected && task.active && !task.cancelRequested && progress.transferring(now)
    Text(if (liveRate && progress.speed != null) "${if (reading) "Leitura observada" else "Velocidade"}: ${formatCleanupBytes(progress.speed.toLong())}/s"
        else "Velocidade indisponível", style = MaterialTheme.typography.bodySmall)
    Text(if (connected && task.active && !task.cancelRequested) progress.estimate(now) else "Tempo estimado indisponível", style = MaterialTheme.typography.bodySmall)
    if (progress.totalFiles != null && progress.totalFiles > 0) Text("Etapa de transferência ${progress.fileIndex ?: 0}/${progress.totalFiles} (inclui metadados)", style = MaterialTheme.typography.labelSmall)
    if (task.active && (!connected || !progress.fresh(now))) Text("Último andamento recebido; aguardando atualização.", style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun CloudDetail(model: CloudModel, entry: CloudEntry?, torrent: ServerTorrent?, modifier: Modifier) {
    val key = model.selected ?: return
    val archiveHash = entry?.hash ?: key
    val plan = model.space?.takeIf { it.id == key }
    val selectedLinks = model.selectedLinks(key)
    val choices = model.libraryChoices(key)
    var confirm by remember(key) { mutableStateOf<CloudSpace?>(null) }
    var libraryConfirmed by remember(confirm) { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (isActive) { now = System.currentTimeMillis(); delay(1000) } }
    val paths = selectedLinks.sorted()
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        item("summary") {
            ScreenPanel {
                Text(entry?.name ?: torrent?.name ?: "Torrent", style = MaterialTheme.typography.titleMedium)
                if (entry != null) {
                    Text("${formatCleanupBytes(entry.size)} · ${cloudState(entry, torrent, model.tasks.firstOrNull { it.work.target == key })}")
                    Text("Drive: ${cloudCopyLabel(entry.state)} · ${if (entry.restorable) "pode restaurar" else "restauração bloqueada"}")
                    MyCtButton(onClick = { model.start(CloudWork("torrent", listOf("space", key) + paths.flatMap { listOf("--remove-library", it) })) }, enabled = model.actionsEnabled) { Text("Consultar espaço / prévia") }
                    if (entry.state != "verified" && torrent?.progress == 1f) {
                        MyCtTextButton(onClick = { model.start(CloudWork("torrent", listOf("inspect", archiveHash))) }, enabled = model.actionsEnabled) { Text("Conferir para arquivar novamente") }
                    }
                } else {
                    Text("Local · ${torrent?.total?.let(::formatCleanupBytes) ?: "Tamanho a conferir"}")
                    MyCtButton(onClick = { model.start(CloudWork("torrent", listOf("inspect", key))) }, enabled = model.actionsEnabled) { Text("Conferir pacote") }
                }
                model.inspection?.let { inspect ->
                    DataLine("Pacote integral", formatCleanupBytes(inspect.getLong("total_size")))
                    DataLine("Arquivos", inspect.cloudObjects("files").size.toString())
                    DataLine("Hardlinks Jellyfin", inspect.cloudObjects("library_links").size.toString())
                    inspect.cloudStrings("blockers").forEach { StatusNotice(it, true) }
                    Text("O torrent será pausado durante o envio. Originais e hardlinks serão preservados.")
                    MyCtButton(onClick = { model.start(CloudWork("torrent", listOf("archive", archiveHash))) },
                        enabled = model.actionsEnabled && inspect.getBoolean("can_archive")) { Text("Arquivar no Drive") }
                }
            }
        }
        item("tasks") { CloudTasks(model) }
        if (entry != null) {
            item("actions") {
                ScreenPanel {
                    ScreenHeading("Restaurar e recuperar", "Restauração integral. A recuperação confere todas as peças e deixa o torrent pausado.")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MyCtTextButton(onClick = { model.start(CloudWork("torrent", listOf("restore", key))) }, enabled = model.actionsEnabled && entry.restorable) { Text("Restaurar") }
                        MyCtTextButton(onClick = { model.start(CloudWork("torrent", listOf("recover", key))) }, enabled = model.actionsEnabled && entry.restorable) { Text("Recuperar torrent") }
                        MyCtTextButton(onClick = { model.start(CloudWork("torrent", listOf("recover", key, "--resume"))) },
                            enabled = model.actionsEnabled && entry.recoveryState == "verified" && torrent?.paused == true && torrent.progress == 1f) { Text("Retomar seeding") }
                    }
                    Text("Retomar faz nova verificação antes de iniciar. Seeding iniciado não comprova upload para peers.", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (choices.isNotEmpty()) item("library-selection") {
                ScreenPanel {
                    Text("Excluir links da biblioteca (opcional)", style = MaterialTheme.typography.titleSmall)
                    Text("Desmarcado preserva Jellyfin. Alterar a seleção exige nova prévia.", style = MaterialTheme.typography.bodySmall)
                    Text("${selectedLinks.size}/256 links selecionados", style = MaterialTheme.typography.labelSmall)
                    choices.forEach { path ->
                        val enabled = model.actionsEnabled && (path in selectedLinks || selectedLinks.size < 256)
                        Row(Modifier.fillMaxWidth().clickable(enabled = enabled) {
                            model.toggleLink(key, path)
                        }) {
                            Checkbox(path in selectedLinks, null, enabled = enabled)
                            Text(path, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        if (plan != null) {
            item("space") {
                ScreenPanel {
                    ScreenHeading("Espaço e liberação", "Nada foi removido. Drive e metainfo serão preservados.")
                    DataLine("Blocos conhecidos ocupados", formatCleanupBytes(plan.physical))
                    DataLine("Estimativa de liberação (até)", formatCleanupBytes(plan.estimate))
                    DataLine("Blocos retidos por outros nomes", formatCleanupBytes(plan.retained))
                    Text("Economia garantida: 0 B. Btrfs, snapshots e arquivos abertos podem reter espaço.", style = MaterialTheme.typography.bodySmall)
                    plan.trees.forEach { tree ->
                        DetailValue(if (tree.getString("role") == "original") "Árvore original" else "Árvore restaurada", tree.getString("path"))
                        Text(if (tree.getBoolean("present")) "Presente · ${if (!tree.has("verified")) "integridade não confirmada" else if (tree.getBoolean("verified")) "verificada" else "divergente"}" else "Ausente", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("A liberação encerra o seeding e remove a entrada do Transmission e as árvores indicadas.")
                    ExpandDetails("Arquivos atingidos (${plan.paths.size})") { plan.paths.forEach { Text(it, style = MaterialTheme.typography.bodySmall) } }
                    plan.blockers.forEach { StatusNotice("Bloqueio: $it", true) }
                    if (!model.previewAuthorized(plan)) StatusNotice("Seleção visual preservada. Consulte espaço novamente para obter autorização atual.", true)
                    if (now >= plan.expiresAt) StatusNotice("Prévia expirada. Consulte espaço novamente.", true)
                    MyCtButton(onClick = { confirm = plan }, enabled = model.canRelease(plan, paths) && plan.valid(now)) { Text("Liberar espaço…") }
                }
            }
            item("links") {
                ScreenPanel {
                    Text("Vínculos Jellyfin", style = MaterialTheme.typography.titleSmall)
                    if (!plan.libraryComplete) StatusNotice("Inventário incompleto. Opções anteriores preservadas; a liberação permanece bloqueada.", true)
                    if (plan.library.isEmpty()) Text("Nenhum vínculo associado.", style = MaterialTheme.typography.bodySmall)
                    plan.library.forEach { link ->
                        val path = link.getString("path")
                        val status = when (link.optString("status")) {
                            "managed" -> "Hardlink gerenciado e comprovado"
                            "unmanaged" -> "Hardlink presente sem registro Media"
                            "divergent" -> "Registro ou origem divergente — protegido"
                            "absent" -> "Vínculo ausente"
                            "uninspectable" -> "Vínculo não inspecionado — protegido"
                            else -> if (link.optBoolean("linked_to_package")) "Hardlink presente" else if (link.optBoolean("present")) "Destino ocupado — conferir" else "Vínculo ausente"
                        }
                        DetailValue(status, path)
                        if (plan.libraryComplete && link.optString("status") != "uninspectable" && !link.optBoolean("present")) MyCtTextButton(onClick = {
                            model.start(CloudWork("torrent", listOf("relink", key, "--library", path)))
                        }, enabled = model.actionsEnabled && entry?.recoveryState == "verified" && torrent?.paused == true && !link.optBoolean("ignored")) { Text("Reconstruir vínculo") }
                    }
                }
            }
        }
    }
    confirm?.let { snapshot ->
        val valid = model.canRelease(snapshot, paths) && snapshot.valid(now)
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Liberar espaço do SSD?") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${snapshot.paths.size} arquivos da prévia serão retirados. Estimativa: até ${formatCleanupBytes(snapshot.estimate)}, sem garantia de liberação imediata.")
                Text("Transmission será removido e o seeding encerrado. Drive e metainfo serão preservados.")
                if (paths.isEmpty()) Text("Hardlinks Jellyfin serão preservados.")
                else {
                    Text("${paths.size} hardlinks Jellyfin também serão removidos da biblioteca.")
                    Row(Modifier.clickable { libraryConfirmed = !libraryConfirmed }) {
                        Checkbox(libraryConfirmed, null)
                        Text("Confirmo também a remoção destes hardlinks Jellyfin.", Modifier.weight(1f))
                    }
                }
                if (!valid) StatusNotice("Prévia obsoleta ou conexão alterada. Consulte novamente.", true)
            }
        }, confirmButton = { MyCtTextButton(onClick = {
            confirm = null; model.release(snapshot, paths, libraryConfirmed)
        }, enabled = valid && (paths.isEmpty() || libraryConfirmed)) { Text("Confirmar liberação") } },
            dismissButton = { MyCtTextButton(onClick = { confirm = null }) { Text("Cancelar") } })
    }
}

@Composable
internal fun CloudFileArchiveDialog(model: CloudModel) {
    model.selectedFile?.let { path ->
        AlertDialog(onDismissRequest = { model.dismissFile() }, title = { Text("Arquivar arquivo no Drive?") }, text = {
            Column {
                DetailValue("Arquivo no Fedora", path.substringAfterLast('/'))
                Text("Escolha um arquivo estável. A cópia será conferida; o original permanece. O trabalho continua no Fedora ao fechar o app.")
                StatusNotice(model.message, model.stale)
            }
        }, confirmButton = { MyCtTextButton(onClick = { model.start(CloudWork("file", listOf("archive", path))) }, enabled = model.actionsEnabled) { Text("Arquivar") } },
            dismissButton = { MyCtTextButton(onClick = { model.dismissFile() }) { Text("Cancelar") } })
    }
}

private fun cloudCopyLabel(state: String) = when (state) {
    "verified" -> "Arquivado e verificado"; "pending" -> "Pendente de confirmação"
    "failed" -> "Erro"; "unverified" -> "Não verificado"; else -> state
}
private fun cloudRestoreLabel(state: String) = when (state) {
    "restored", "verified" -> "Concluída"; "pending" -> "Pendente de confirmação"; else -> "Erro / bloqueada"
}
private fun cloudDate(value: String) = value.replace('T', ' ').replace("+00:00", " UTC")
