package dev.myct

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MyCtButton(onClick = { submit() }, enabled = canSearch && model.query.isNotBlank()) { Text("Pesquisar") }
                    if (model.busy) MyCtTextButton(onClick = { model.hide() }) { Text("Cancelar") }
                    else MyCtTextButton(onClick = { model.checkStatus() }, enabled = canSearch) { Text("Verificar serviço") }
                }
                if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                StatusNotice(model.message, model.state == SearchState.Error)
                if (model.truncated) StatusNotice("Listagem limitada a 20 resultados. Refine a consulta para encontrar outros.")
                MyCtTextButton(onClick = openDownloads) { Text("Ver em Downloads") }
            }
        }
        itemsIndexed(model.items, key = { index, _ -> "result-$index" }) { _, item ->
            ScreenPanel {
                Text(item.title, style = MaterialTheme.typography.titleSmall)
                Text(listOfNotNull(item.size?.let(::formatFileSize), item.source).joinToString(" · ")
                    .ifBlank { "Tamanho e origem indisponíveis" }, style = MaterialTheme.typography.bodySmall)
                Text(listOfNotNull(item.seeders?.let { "Seeders: $it" }, item.leechers?.let { "Leechers: $it" })
                    .joinToString(" · ").ifBlank { "Peers não informados" }, style = MaterialTheme.typography.bodySmall)
                MyCtButton(onClick = { model.add(item) }, enabled = visible && model.canAdd(item)) {
                    Text("Adicionar aos downloads")
                }
                if (item.magnet == null) StatusNotice("Sem magnet disponível para adicionar.")
                if (item.magnet != null && item.magnet == model.additionMagnet) {
                    if (torrents.busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        StatusNotice("Enviando pedido ao Fedora…")
                    } else StatusNotice(torrents.message, torrents.stale)
                    MyCtTextButton(onClick = openDownloads) { Text("Ver em Downloads → Torrents") }
                }
            }
        }
    }
}
