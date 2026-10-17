package dev.myct

import android.graphics.Typeface
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.text.LineSeparator
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun IdeScreen(model: IdeModel, terminal: () -> Unit, files: (String) -> Unit, modifier: Modifier = Modifier) {
    val doc = model.document
    var pendingOpen by remember { mutableStateOf<String?>(null) }
    var closeAsked by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var notices by remember { mutableStateOf<String?>(null) }
    var find by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var replacement by rememberSaveable { mutableStateOf("") }
    var location by remember(model.listing?.path) { mutableStateOf(model.listing?.path.orEmpty()) }
    var editor by remember { mutableStateOf<CodeEditor?>(null) }
    val context = LocalContext.current
    var ready by remember { mutableStateOf(false) }
    var languageError by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try { withContext(Dispatchers.IO) { IdeLanguages.load(context.applicationContext.assets) }; ready = true }
        catch (_: Exception) { languageError = true }
        if (model.connected && model.listing == null) model.loadDirectory()
    }
    fun open(path: String) { if (doc?.dirty == true) pendingOpen = path else model.open(path) }
    Column(modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { model.browse = !model.browse }) { Text(if (model.browse) "Editor" else "Projetos") }
            Button(onClick = { model.save() }, enabled = model.canSave) { Text("Salvar${if (doc?.dirty == true) " *" else ""}") }
            TextButton(onClick = terminal) { Text("Terminal") }
            TextButton(onClick = { details = true }) { Text("Detalhes") }
            if (model.busy) TextButton(onClick = model::cancel) { Text("Cancelar") }
        }
        if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(model.message, style = MaterialTheme.typography.bodySmall, maxLines = 3)
        if (model.browse || doc == null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(location, { location = it }, label = { Text("Diretório no Fedora") }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(onClick = { model.loadDirectory(location.ifBlank { null }) }, enabled = model.connected && !model.busy) { Text("Ir") }
            }
            Row {
                TextButton(onClick = { model.loadDirectory() }, enabled = model.connected && !model.busy) { Text("Home") }
                TextButton(onClick = { model.listing?.path?.let { model.loadDirectory(RemotePath.parent(it)) } }, enabled = model.connected && !model.busy) { Text("↑ Pai") }
                TextButton(onClick = { model.listing?.path?.let(files) }, enabled = model.connected) { Text("Arquivos") }
                if (doc != null) TextButton(onClick = { model.browse = false }) { Text("Voltar ao código${if (doc.dirty) " *" else ""}") }
            }
            LazyColumn(Modifier.weight(1f)) {
                items(model.listing?.entries.orEmpty(), key = { it.name }) { entry ->
                    TextButton(onClick = {
                        model.listing?.path?.let { directory ->
                            val path = RemotePath.child(directory, entry.name)
                            if (entry.kind == RemoteKind.DIRECTORY) model.loadDirectory(path) else open(path)
                        }
                    }, enabled = model.connected && !model.busy && entry.kind in setOf(RemoteKind.FILE, RemoteKind.DIRECTORY),
                        modifier = Modifier.fillMaxWidth()) {
                        Text("${if (entry.kind == RemoteKind.DIRECTORY) "▸" else "◇"} ${entry.name}", modifier = Modifier.weight(1f))
                        if (entry.kind == RemoteKind.FILE) Text("${entry.size} B", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        } else {
            Text("${if (doc.dirty) "● " else ""}${doc.path}", style = MaterialTheme.typography.labelSmall, maxLines = 2)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                TextButton(onClick = { editor?.undo() }, enabled = !model.busy && !doc.readOnly) { Text("Desfazer") }
                TextButton(onClick = { editor?.redo() }, enabled = !model.busy && !doc.readOnly) { Text("Refazer") }
                TextButton(onClick = { editor?.insertText("    ", 4) }, enabled = !model.busy && !doc.readOnly) { Text("Tab") }
                TextButton(onClick = { find = !find; if (!find) editor?.searcher?.stopSearch() }) { Text("Buscar") }
                TextButton(onClick = { closeAsked = true }, enabled = !model.busy) { Text("Fechar") }
            }
            if (find) {
                Row {
                    OutlinedTextField(query, { query = it; if (it.isEmpty()) editor?.searcher?.stopSearch() else editor?.searcher?.search(it, EditorSearcher.SearchOptions(false, false)) },
                        label = { Text("Buscar texto") }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(onClick = { if (query.isNotEmpty()) { editor?.searcher?.search(query, EditorSearcher.SearchOptions(false, false)); editor?.searcher?.gotoNext() } }) { Text("Próximo") }
                }
                Row {
                    OutlinedTextField(replacement, { replacement = it }, label = { Text("Substituir por") }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(onClick = { if (query.isNotEmpty()) editor?.searcher?.replaceCurrentMatch(replacement) }, enabled = !model.busy && !doc.readOnly) { Text("Trocar") }
                }
            }
            if (languageError) Text("Falha ao carregar gramáticas locais. Reabra a IDE.")
            else if (!ready) Text("Preparando editor…")
            else key(doc) {
                AndroidView(factory = { ctx ->
                    CodeEditor(ctx).apply {
                        typefaceText = Typeface.MONOSPACE
                        setTextSize(14f); tabWidth = 4; isLineNumberEnabled = true
                        colorScheme = TextMateColorScheme.create(io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry.getInstance())
                        setEditorLanguage(codeScope(doc.path)?.let { TextMateLanguage.create(it, false) } ?: EmptyLanguage())
                        setText(doc.content, true, null)
                        // Content retains original per-line separators/BOM. New lines use the first existing style.
                        lineSeparator = when {
                            doc.snapshot.bytes.toString(Charsets.UTF_8).contains("\r\n") -> LineSeparator.CRLF
                            doc.snapshot.bytes.toString(Charsets.UTF_8).contains('\r') -> LineSeparator.CR
                            else -> LineSeparator.LF
                        }
                        post { if (!isReleased) ensureSelectionVisible() }
                        editor = this
                    }
                }, update = { it.isEditable = !model.busy && !doc.readOnly }, onRelease = { view ->
                    if (editor === view) editor = null
                    view.release()
                }, modifier = Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
    if (pendingOpen != null || closeAsked) AlertDialog(onDismissRequest = { pendingOpen = null; closeAsked = false },
        title = { Text("${if (doc?.dirty == true) "Descartar alterações?" else "Fechar arquivo?"}") },
        text = { Text("${doc?.path.orEmpty()}\n${if (doc?.dirty == true) "O buffer ainda não foi salvo. Cancele e toque em Salvar para gravá-lo no Fedora." else "O arquivo remoto será preservado."}") },
        confirmButton = { TextButton(onClick = {
            val next = pendingOpen; pendingOpen = null; closeAsked = false
            if (next != null) model.open(next) else model.closeDocument()
        }) { Text(if (doc?.dirty == true) "Descartar" else "Fechar") } },
        dismissButton = { TextButton(onClick = { pendingOpen = null; closeAsked = false }) { Text("Cancelar") } })
    if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text("IDE remota · UTF-8") },
        text = { SelectionContainer { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Selecione o código para copiar/colar. Indentação: 4 espaços. Busca literal diferencia maiúsculas. Sem autosave remoto. Limite: 1 MiB / 30 mil linhas. Um arquivo aberto por vez.")
            Text("Alternar seções ou desconectar mantém o buffer e undo em memória. Fechar o processo Android perde o buffer; salve antes de sair. Não edite o mesmo arquivo simultaneamente em outro programa.")
            doc?.backupPath?.let { Text("Caminho de recuperação (se criado): $it") }
            if (doc?.uncertain == true) Text("Resultado incerto: confira original/backup em Arquivos ou Terminal, preserve uma cópia do buffer e reabra antes de salvar novamente.")
            Text("Sora Editor 0.23.6 · LGPL-2.1-or-later. Fontes/licenças e instruções de reconstrução na release e no repositório My-CT.")
            TextButton(onClick = { notices = context.assets.open("ide/NOTICE.txt").bufferedReader().use { it.readText() } }) { Text("Licenças e fontes") }
        } } }, confirmButton = { TextButton(onClick = { details = false }) { Text("Fechar") } })
    notices?.let { text -> AlertDialog(onDismissRequest = { notices = null }, title = { Text("Licenças da IDE") },
        text = { SelectionContainer { Text(text, Modifier.verticalScroll(rememberScrollState())) } },
        confirmButton = { TextButton(onClick = { notices = null }) { Text("Fechar") } }) }
}
