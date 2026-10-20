package dev.myct

import androidx.activity.compose.LocalActivity
import android.content.Intent
import android.net.Uri
import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

@Composable
internal fun SearchScreen(model: SearchModel, torrents: TorrentsModel, openDownloads: () -> Unit,
                          modifier: Modifier = Modifier) {
    val activity = checkNotNull(LocalActivity.current as? androidx.activity.ComponentActivity)
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    var visible by remember { mutableStateOf(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(activity, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) visible = true
            if (event == Lifecycle.Event.ON_STOP) { visible = false; model.hide() }
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer); model.hide() }
    }
    var pageFailure by remember { mutableStateOf<String?>(null) }
    var choosingSource by remember { mutableStateOf(false) }
    val canSearch = visible && model.connected && !model.busy
    fun submit() {
        if (!canSearch) return
        keyboard?.hide(); focus.clearFocus()
        model.search()
    }
    // Form scrolls with results, keeping the small-screen viewport usable with IME open.
    LazyColumn(modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = "form") {
            ScreenPanel {
                ScreenHeading("Busca", "Pesquise no Fedora. Escolha um resultado para adicionar aos downloads.")
                OutlinedTextField(model.query, { if (it.codePointCount(0, it.length) <= 200) model.edit(it) },
                    label = { Text("O que procurar?") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit() }))
                Box {
                    MyCtTextButton(onClick = { choosingSource = true }, enabled = visible && model.connected) {
                        Text("Fonte: " + (model.indexers.firstOrNull { it.id == model.selectedIndexer }?.name ?: "Todos"))
                    }
                    DropdownMenu(expanded = choosingSource, onDismissRequest = { choosingSource = false }) {
                        DropdownMenuItem(text = { Text("Todos") }, onClick = { model.selectIndexer(null); choosingSource = false })
                        model.indexers.forEach { source ->
                            DropdownMenuItem(text = { Text(source.name + if (source.available) "" else " (indisponível)") },
                                enabled = source.available, onClick = { model.selectIndexer(source.id); choosingSource = false })
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MyCtButton(onClick = { submit() }, enabled = canSearch && model.query.isNotBlank()) { Text("Pesquisar") }
                    if (model.busy) MyCtTextButton(onClick = { model.hide() }) { Text("Cancelar") }
                    else MyCtTextButton(onClick = { model.checkStatus() }, enabled = canSearch) { Text("Atualizar fontes") }
                }
                if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                StatusNotice(model.message, model.state == SearchState.Error)
                if (model.limited) StatusNotice(if (model.paginated)
                    "Limite técnico de 500 resultados únicos. Refine a pesquisa para encontrar outros."
                    else "Helper antigo: listagem limitada a 20. Atualize myct-search no Fedora para carregar mais.")
                if (model.paginated && model.items.isNotEmpty())
                    Text("Páginas consultadas ao vivo; a ordem e a quantidade podem mudar. Repetições são ocultadas.",
                        style = MaterialTheme.typography.bodySmall)
                MyCtTextButton(onClick = openDownloads) { Text("Ver em Downloads") }
            }
        }
        items(model.items, key = { it.identity() }) { item ->
            ScreenPanel {
                Text(item.title, style = MaterialTheme.typography.titleSmall)
                Text(listOfNotNull(item.size?.let(::formatFileSize), item.source).joinToString(" · ")
                    .ifBlank { "Tamanho e origem indisponíveis" }, style = MaterialTheme.typography.bodySmall)
                Text(listOfNotNull(item.seeders?.let { "Seeders: $it" }, item.leechers?.let { "Leechers: $it" })
                    .joinToString(" · ").ifBlank { "Peers não informados" }, style = MaterialTheme.typography.bodySmall)
                if (item.magnet != null) MyCtButton(onClick = { model.add(item) }, enabled = visible && model.canAdd(item)) {
                    Text("Adicionar aos downloads")
                }
                if (item.torrentAvailable && item.resultRef != null && item.indexerId != null) {
                    MyCtButton(onClick = { model.addFile(item) }, enabled = visible && model.canAddFile(item)) {
                        Text("Adicionar arquivo torrent")
                    }
                    Text("A fonte pode não entregar o arquivo. A inclusão só ocorre após validar .torrent no Fedora.",
                        style = MaterialTheme.typography.bodySmall)
                }
                item.publicUrl?.let { url ->
                    MyCtTextButton(onClick = {
                        if (publicSearchPage(url) != null) try {
                            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
                            pageFailure = null
                        } catch (_: ActivityNotFoundException) { pageFailure = item.identity() }
                        catch (_: SecurityException) { pageFailure = item.identity() }
                    }, enabled = visible) { Text("Abrir página da fonte") }
                }
                if (pageFailure == item.identity()) StatusNotice("Não foi possível abrir um navegador externo.", true)
                if (item.magnet == null && !item.torrentAvailable && item.publicUrl == null)
                    StatusNotice("Esta fonte não disponibilizou magnet, arquivo .torrent recuperável ou página pública segura.")
                if ((item.magnet != null && item.magnet == model.additionMagnet) ||
                    (item.resultRef != null && item.resultRef == model.additionRef)) {
                    if (torrents.busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        StatusNotice("Enviando pedido ao Fedora…")
                        MyCtTextButton(onClick = { model.cancelAddition() }) { Text("Interromper espera") }
                    } else StatusNotice(torrents.message, torrents.stale)
                    MyCtTextButton(onClick = openDownloads) { Text("Ver em Downloads → Torrents") }
                }
            }
        }
        if (model.items.isNotEmpty()) item(key = "pagination") {
            ScreenPanel {
                Text("${model.items.size} resultados carregados", style = MaterialTheme.typography.bodySmall)
                model.pageError?.let { StatusNotice(it, true) }
                if (model.hasMore) MyCtButton(onClick = { model.loadMore() }, enabled = visible && model.connected && !model.busy) {
                    Text(if (model.loadingMore) "Carregando…" else "Carregar mais")
                }
                if (model.loadingMore) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    MyCtTextButton(onClick = { model.hide() }) { Text("Cancelar") }
                }
            }
        }
    }
}
