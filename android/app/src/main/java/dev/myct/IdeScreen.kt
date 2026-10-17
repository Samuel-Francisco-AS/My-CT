package dev.myct

import android.graphics.Typeface
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.OpenableColumns
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.core.view.doOnLayout
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.text.LineSeparator
import io.github.rosemoe.sora.widget.EditorSearcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
internal fun IdeScreen(model: IdeModel, terminal: () -> Unit, files: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        model.local.importDocument(uri?.let { { context.contentResolver.openInputStream(it) } }) {
            context.contentResolver.query(requireNotNull(uri), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "documento.txt"
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        model.local.exportDocument(uri?.let { { context.contentResolver.openOutputStream(it, "w") } },
            verify = { uri?.let { context.contentResolver.openInputStream(it) } })
    }
    val states = rememberSaveableStateHolder()
    states.SaveableStateProvider(model.environment.name) {
        IdeSessionScreen(model, model.session, terminal, files,
            importDocument = { try { importer.launch(arrayOf("*/*")) } catch (_: Exception) { model.local.pickerFailed() } },
            exportDocument = { try { model.local.prepareExport()?.let(exporter::launch) } catch (_: Exception) { model.local.pickerFailed() } }, modifier)
    }
}

@Composable
private fun IdeSessionScreen(owner: IdeModel, model: IdeSession, terminal: () -> Unit, files: (String) -> Unit,
                             importDocument: () -> Unit, exportDocument: () -> Unit, modifier: Modifier) {
    val local = owner.environment == IdeEnvironment.LOCAL
    val doc = model.document
    val browsing = model.browse || doc == null
    var compileSaveAsked by remember { mutableStateOf(false) }
    val compiler = owner.offlineC
    var pendingOpen by remember { mutableStateOf<String?>(null) }
    var closeAsked by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var notices by remember { mutableStateOf<String?>(null) }
    var find by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var environmentMenu by remember { mutableStateOf(false) }
    var nameAction by remember { mutableStateOf<String?>(null) }
    var renamePath by remember { mutableStateOf<String?>(null) }
    var itemName by remember { mutableStateOf("") }
    var pathDialog by remember { mutableStateOf(false) }
    var keysVisible by rememberSaveable { mutableStateOf(true) }
    var query by rememberSaveable { mutableStateOf("") }
    var replacement by rememberSaveable { mutableStateOf("") }
    var location by remember(model.listing?.path) { mutableStateOf(model.listing?.path.orEmpty()) }
    var editor by remember { mutableStateOf<IdeEditorView?>(null) }
    val keys = remember { IdeModifiers() }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var ready by remember { mutableStateOf(false) }
    var languageError by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try { withContext(Dispatchers.IO) { IdeLanguages.load(context.applicationContext.assets) }; ready = true }
        catch (_: Exception) { languageError = true }
        if (model.connected && model.listing == null) model.loadDirectory()
    }
    LaunchedEffect(model.connected) { if (model.connected && model.listing == null && !model.busy) model.loadDirectory() }
    DisposableEffect(lifecycle, keys) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) keys.clear() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); keys.clear() }
    }
    LaunchedEffect(doc, browsing, model.busy) { keys.clear() }
    var previousMessage by remember { mutableStateOf(model.message) }
    LaunchedEffect(model.busy, model.message) {
        if (previousMessage != model.message && !model.busy && !model.noticeIsError)
            Toast.makeText(context, model.message, Toast.LENGTH_SHORT).show()
        previousMessage = model.message
    }
    fun restoreFocus() { editor?.post { if (editor?.isReleased == false) editor?.requestFocus() } }
    fun action(block: () -> Unit) { menu = false; keys.clear(); block() }
    fun open(path: String) { if (doc?.dirty == true) pendingOpen = path else model.open(path) }

    val listing = model.listing
    val entries = listing?.entries.orEmpty()
    // Construct the correct position before the new folder can draw, as in FilesScreen.
    val listState = key(listing?.path) {
        val saved = listing?.let { model.scroll.position(it.path) }
        rememberLazyListState(saved?.restoredIndex(entries) ?: 0, saved?.offset ?: 0)
    }
    fun saveScroll() {
        listing?.let { model.scroll.remember(it.path, entries, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
    }
    fun directory(path: String? = null) { saveScroll(); model.loadDirectory(path) }
    DisposableEffect(model, listing?.path, browsing) {
        val current = listing
        onDispose {
            if (browsing && current != null && model.listing?.path == current.path)
                model.scroll.remember(current.path, entries, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }
    }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    Column(modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        // Local contextual strip only. No status paragraphs/path rows above the code.
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                MyCtTextButton(onClick = { keys.clear(); environmentMenu = true },
                    modifier = Modifier.semantics { contentDescription = "Ambiente: ${owner.environment.label}. Selecionar Local ou Remoto" }) {
                    Text(if (local) "Local ▾" else "Remoto ▾")
                }
                DropdownMenu(environmentMenu, onDismissRequest = { environmentMenu = false }) {
                    IdeEnvironment.entries.forEach { value -> DropdownMenuItem(text = { Text(value.label) }, onClick = {
                        saveScroll(); environmentMenu = false; keys.clear(); owner.select(value)
                    }) }
                }
            }
            Column(Modifier.weight(1f)) {
                Text(if (browsing) "IDE · Projetos" else "${if (doc?.dirty == true) "● " else ""}${doc?.path?.substringAfterLast('/').orEmpty()}",
                modifier = Modifier.padding(horizontal = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall)
                if (local && !browsing) Text(if (owner.local.imported) "Cópia SAF · workspace" else "Workspace privada",
                maxLines = 1, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            MyCtButton(onClick = model::save, enabled = model.canSave) { Text("Salvar${if (doc?.dirty == true) " *" else ""}") }
            Box {
                IconButton(onClick = { keys.clear(); menu = true }, modifier = Modifier.semantics { contentDescription = "Ações da IDE" }) { Text("⋮") }
                DropdownMenu(menu, onDismissRequest = { menu = false }, properties = PopupProperties(focusable = false)) {
                    DropdownMenuItem(text = { Text(if (browsing) "Voltar ao código" else "Projetos") }, enabled = doc != null,
                        onClick = { action { saveScroll(); model.browse = !model.browse } })
                    DropdownMenuItem(text = { Text(if (local) "Terminal · Local" else "Terminal · Fedora") }, onClick = { action { saveScroll(); terminal() } })
                    DropdownMenuItem(text = { Text("Arquivos") }, enabled = !local && model.connected && listing != null,
                        onClick = { action { saveScroll(); listing?.path?.let(files) } })
                    if (local) {
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Nova pasta / projeto…") }, enabled = !model.busy,
                            onClick = { action { nameAction = "folder"; itemName = "" } })
                        DropdownMenuItem(text = { Text("Novo arquivo…") }, enabled = !model.busy,
                            onClick = { action { nameAction = "file"; itemName = "" } })
                        DropdownMenuItem(text = { Text("Importar cópia · SAF…") }, enabled = !model.busy,
                            onClick = { action { saveScroll(); importDocument() } })
                        DropdownMenuItem(text = { Text("Exportar cópia salva · SAF…") },
                            enabled = !model.busy && doc != null && !doc.dirty && !doc.uncertain,
                            onClick = { action(exportDocument) })
                    }
                    if (!browsing) {
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Desfazer") }, enabled = !model.busy && doc?.readOnly == false,
                            onClick = { action { editor?.undo(); restoreFocus() } })
                        DropdownMenuItem(text = { Text("Refazer") }, enabled = !model.busy && doc?.readOnly == false,
                            onClick = { action { editor?.redo(); restoreFocus() } })
                        DropdownMenuItem(text = { Text("Buscar / substituir") }, onClick = { action { find = true } })
                        DropdownMenuItem(text = { Text(if (keysVisible) "Recolher teclas" else "Mostrar teclas") },
                            onClick = { action { keysVisible = !keysVisible; restoreFocus() } })
                        DropdownMenuItem(text = { Text("Fechar arquivo") }, enabled = !model.busy,
                            onClick = { action { closeAsked = true } })
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Ir para caminho…") }, enabled = model.connected && !model.busy,
                        onClick = { action { pathDialog = true } })
                    if (model.busy && !local) DropdownMenuItem(text = { Text("Cancelar operação") }, onClick = { action(model::cancel) })
                    DropdownMenuItem(text = { Text("Detalhes") }, onClick = { action { details = true } })
                }
            }
        }
        if (local && compiler != null) {
            if (!browsing && canCompileC(owner.environment, doc)) Row(Modifier.fillMaxWidth()) {
                MyCtTextButton(onClick = {
                    keys.clear()
                    if (doc?.dirty == true) compileSaveAsked = true else compiler.start(owner.environment, doc)
                }, enabled = !model.busy && !compiler.active) { Text("Compilar e executar") }
                if (compiler.phase != CPhase.IDLE) MyCtTextButton(onClick = { compiler.consoleOpen = true }) { Text("Console") }
            } else if (compiler.phase != CPhase.IDLE) MyCtTextButton(onClick = { compiler.consoleOpen = true }) { Text("Console C · ${compiler.phase.label}") }
        }
        if (browsing) {
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = !model.busy && listing != null && !model.atRoot(listing.path),
                    onClick = { listing?.let { directory(model.parent(it.path)) } }) {
                    Icon(FileGlyphs.Parent, contentDescription = "Subir um diretório", tint = MaterialTheme.colorScheme.primary)
                }
                DetailValue("Caminho", listing?.path?.let(model::displayPath) ?: if (local) "Projetos locais/" else "Diretório inicial", maxLines = 1, showLabel = false, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                MyCtTextButton(onClick = { directory() }, enabled = model.connected && !model.busy) { Text(if (local) "Projetos" else "Home") }
                MyCtTextButton(onClick = { directory(listing?.path) }, enabled = model.connected && !model.busy) { Text("Atualizar") }
                MyCtTextButton(onClick = { pathDialog = true }, enabled = model.connected && !model.busy) { Text("Ir…") }
                Text("${entries.size}", style = MaterialTheme.typography.labelSmall)
            }
            // Keep the old list and its viewport unchanged while listing/opening is in flight.
            Row(Modifier.fillMaxWidth().height(40.dp).clickable { details = true }, verticalAlignment = Alignment.CenterVertically) {
                Text(if (model.busy) "Carregando…" else model.message, Modifier.weight(1f), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                    color = if (model.noticeIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = model::cancel, enabled = model.busy && !local, modifier = Modifier.size(40.dp)) { Text("×", Modifier.semantics { contentDescription = "Cancelar operação IDE" }) }
            }
        } else if (model.noticeIsError || languageError) {
            Row(Modifier.fillMaxWidth().clickable { details = true }, verticalAlignment = Alignment.CenterVertically) {
                Text(if (languageError) "Falha nas gramáticas locais. Reabra a IDE." else model.message,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { details = true }) { Text("ⓘ") }
            }
        }
        Box(Modifier.fillMaxWidth().height(4.dp)) { if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth()) }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (browsing) {
                LazyColumn(Modifier.fillMaxSize(), state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(entries, key = { it.name }) { entry ->
                        Surface(shape = MyCtGeometry.Control, color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                            Row(Modifier.fillMaxWidth().clickable(enabled = model.connected && !model.busy && entry.kind in setOf(RemoteKind.FILE, RemoteKind.DIRECTORY)) {
                                listing?.path?.let { parent ->
                                    val path = model.child(parent, entry.name)
                                    if (entry.kind == RemoteKind.DIRECTORY) directory(path) else { saveScroll(); open(path) }
                                }
                            }.heightIn(min = 64.dp).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(FileGlyphs.forKind(entry.kind), contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(24.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                    Text("${when (entry.kind) { RemoteKind.DIRECTORY -> "Pasta"; RemoteKind.FILE -> "Arquivo · ${formatFileSize(entry.size)}"; RemoteKind.LINK -> "Link simbólico · sem edição"; RemoteKind.OTHER -> "Tipo especial · sem edição" }} · ${dateFormat.format(Date(entry.modified * 1000))}",
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (local) IconButton(enabled = !model.busy, onClick = {
                                    saveScroll(); renamePath = listing?.path?.let { model.child(it, entry.name) }
                                    itemName = entry.name; nameAction = "rename"
                                }, modifier = Modifier.semantics { contentDescription = "Renomear ${entry.name}" }) { Text("⋮") }
                            }
                        }
                    }
                }
            } else if (ready && !languageError) key(doc) {
                AndroidView(factory = { ctx ->
                    IdeEditorView(ctx).apply {
                        typefaceText = Typeface.MONOSPACE
                        setTextSize(14f); tabWidth = 4; isLineNumberEnabled = true
                        colorScheme = TextMateColorScheme.create(io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry.getInstance())
                        setEditorLanguage(codeScope(doc.path)?.let { TextMateLanguage.create(it, false) } ?: EmptyLanguage())
                        setText(doc.content, true, null)
                        val original = doc.snapshot.bytes.toString(Charsets.UTF_8)
                        lineSeparator = when { original.contains("\r\n") -> LineSeparator.CRLF; original.contains('\r') -> LineSeparator.CR; else -> LineSeparator.LF }
                        onEditorFocusLost = keys::clear
                        subscribeEvent(ContentChangeEvent::class.java) { _, _ -> keys.clear() }
                        doOnLayout { if (!isReleased) restoreViewport(doc.viewport) }
                        editor = this
                    }
                }, update = { it.isEditable = !model.busy && !doc.readOnly }, onRelease = { view ->
                    doc.viewport = view.rememberViewport()
                    if (editor === view) editor = null
                    keys.clear(); view.onEditorFocusLost = null; view.release()
                }, modifier = Modifier.fillMaxSize())
            } else Text("Preparando editor…")
            // Overlay rather than resizing the code viewport when a modifier is armed.
            if (!browsing && doc != null && keysVisible && (keys.ctrl || keys.alt)) {
                Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), shape = MyCtGeometry.Control,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        val choices = if (keys.alt) listOf(IdeShortcut.LINE_ABOVE) else IdeShortcut.entries.filter { it != IdeShortcut.LINE_ABOVE }
                        choices.forEach { shortcut -> MyCtTextButton(onClick = { editor?.let { keys.shortcut(shortcut, it) } },
                            enabled = editor != null && !model.busy && (!shortcut.edits || !doc.readOnly)) { Text(shortcut.label) } }
                    }
                }
            }
        }
        if (!browsing && doc != null && keysVisible) {
            MyCtKeyGrid(keys.ctrl, keys.alt, enabled = editor != null && !model.busy,
                onKey = { key -> editor?.let { keys.press(key, it) } }, editor = true)
        }
    }
    if (local && compiler != null) OfflineCConsole(compiler)
    if (compileSaveAsked) AlertDialog(onDismissRequest = { compileSaveAsked = false },
        title = { Text("Salvar antes de compilar") },
        text = { Text("Há alterações no Sora. Salve o arquivo e toque novamente em Compilar e executar.") },
        confirmButton = { MyCtTextButton(onClick = { compileSaveAsked = false; model.save() }, enabled = model.canSave) { Text("Salvar") } },
        dismissButton = { MyCtTextButton(onClick = { compileSaveAsked = false }) { Text("Cancelar") } })
    if (pathDialog) AlertDialog(onDismissRequest = { pathDialog = false; restoreFocus() }, title = { Text(if (local) "Pasta em Projetos locais" else "Diretório no Fedora") },
        text = { OutlinedTextField(location, { location = it }, singleLine = true, label = { Text(if (local) "Caminho relativo · vazio para raiz" else "Caminho absoluto") }) },
        confirmButton = { MyCtTextButton(onClick = { pathDialog = false; directory(location.ifBlank { null }) }) { Text("Ir") } },
        dismissButton = { MyCtTextButton(onClick = { pathDialog = false; restoreFocus() }) { Text("Cancelar") } })
    if (nameAction != null) AlertDialog(onDismissRequest = { nameAction = null; restoreFocus() },
        title = { Text(when (nameAction) { "folder" -> "Nova pasta / projeto"; "rename" -> "Renomear"; else -> "Novo arquivo" }) },
        text = { OutlinedTextField(itemName, { itemName = it }, singleLine = true, label = { Text("Nome") }) },
        confirmButton = { MyCtTextButton(enabled = itemName.isNotBlank() && !model.busy, onClick = {
            saveScroll()
            when (nameAction) {
                "rename" -> renamePath?.let { owner.local.rename(it, itemName) }
                else -> owner.local.create(itemName, nameAction == "folder")
            }
            nameAction = null
        }) { Text(if (nameAction == "rename") "Renomear" else "Criar") } },
        dismissButton = { MyCtTextButton(onClick = { nameAction = null; restoreFocus() }) { Text("Cancelar") } })
    if (find) AlertDialog(onDismissRequest = { find = false; restoreFocus() }, title = { Text("Buscar / substituir") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            OutlinedTextField(query, { query = it; if (it.isEmpty()) editor?.searcher?.stopSearch() else editor?.searcher?.search(it, EditorSearcher.SearchOptions(false, false)) },
                label = { Text("Busca literal · diferencia maiúsculas") }, singleLine = true)
            Row {
                MyCtTextButton(onClick = { if (query.isNotEmpty()) { editor?.searcher?.search(query, EditorSearcher.SearchOptions(false, false)); editor?.searcher?.gotoPrevious() } }) { Text("Anterior") }
                MyCtTextButton(onClick = { if (query.isNotEmpty()) { editor?.searcher?.search(query, EditorSearcher.SearchOptions(false, false)); editor?.searcher?.gotoNext() } }) { Text("Próximo") }
            }
            OutlinedTextField(replacement, { replacement = it }, label = { Text("Substituir por") }, singleLine = true)
            MyCtTextButton(onClick = { if (query.isNotEmpty()) editor?.searcher?.replaceCurrentMatch(replacement) }, enabled = !model.busy && doc?.readOnly == false) { Text("Trocar ocorrência") }
        } }, confirmButton = { MyCtTextButton(onClick = { find = false; restoreFocus() }) { Text("Voltar ao código") } })
    if (pendingOpen != null || closeAsked) AlertDialog(onDismissRequest = { pendingOpen = null; closeAsked = false },
        title = { Text("${if (doc?.dirty == true) "Descartar alterações?" else "Fechar arquivo?"}") },
        text = { Text("${doc?.path.orEmpty()}\n${if (doc?.dirty == true) "O buffer ainda não foi salvo. Cancele e toque em Salvar no ambiente de origem." else "O arquivo salvo será preservado."}") },
        confirmButton = { TextButton(onClick = {
            val next = pendingOpen; pendingOpen = null; closeAsked = false
            if (next != null) model.open(next) else model.closeDocument()
        }) { Text(if (doc?.dirty == true) "Descartar" else "Fechar") } },
        dismissButton = { TextButton(onClick = { pendingOpen = null; closeAsked = false }) { Text("Cancelar") } })
    if (details) AlertDialog(onDismissRequest = { details = false; restoreFocus() }, title = { Text("${owner.environment.label} · UTF-8") },
        text = { SelectionContainer { Column(Modifier.verticalScroll(rememberScrollState())) {
            DetailValue("Arquivo", model.displayPath(doc?.path ?: listing?.path.orEmpty()))
            if (local) Text(if (owner.local.imported) "Cópia importada via SAF. Salvar altera apenas a workspace; exportar cria outra cópia." else "Workspace privada do My-CT. Desinstalar/limpar dados apaga os projetos; exporte cópias pelo SAF.")
            Text(model.message)
            Text("CTRL: atalhos locais na faixa temporária; CTRL + setas move por palavra e CTRL + HOME/END vai ao início/fim do documento. ALT oferece CTRL+ALT+ENTER (linha acima). Modificadores são consumidos pela próxima tecla da barra/atalho e cancelados ao editar pelo Gboard, abrir menu ou sair da tela. Sem alterar a composição do teclado Android.")
            Text("Selecione o código para copiar/colar. Indentação: 4 espaços. Busca literal diferencia maiúsculas. Sem autosave. Limite: 1 MiB / 30 mil linhas. Um arquivo aberto por ambiente.")
            Text("Alternar seções ou desconectar mantém o buffer e undo em memória. Fechar o processo Android perde o buffer; salve antes de sair. Não edite o mesmo arquivo simultaneamente em outro programa.")
            doc?.backupPath?.let { Text("Caminho de recuperação (se criado): $it") }
            if (doc?.uncertain == true) Text(if (local)
                "Resultado incerto: confira o arquivo em Projetos locais; copie o buffer pelo clipboard antes de descartar/reabrir. Não há backup local automático."
                else "Resultado incerto: confira original/backup em Arquivos ou Terminal, preserve uma cópia do buffer e reabra antes de salvar novamente.")
            Text("Sora Editor 0.23.6 · LGPL-2.1-or-later. Fontes/licenças e instruções de reconstrução na release e no repositório My-CT.")
            if (local) TextButton(onClick = {
                notices = "C de console: uma fonte .c salva; WCPL 1.04 → WASI / WAMR 2.4.4 interpretado. Não é C++ nem GCC completo. Entrada por linha; execução até 60 s, memória WASM até 16 MiB. Headers de projeto/build systems não são incluídos.\n\n" +
                    context.assets.list("licenses/offline-c").orEmpty().sorted().joinToString("\n\n") { name ->
                        "$name\n" + context.assets.open("licenses/offline-c/$name").bufferedReader().use { it.readText() }
                    }
            }) { Text("Compilador C · limites e licenças") }
            TextButton(onClick = { notices = context.assets.open("ide/NOTICE.txt").bufferedReader().use { it.readText() } }) { Text("Licenças e fontes") }
        } } }, confirmButton = { TextButton(onClick = { details = false; restoreFocus() }) { Text("Fechar") } })
    notices?.let { text -> AlertDialog(onDismissRequest = { notices = null }, title = { Text("Licenças da IDE") },
        text = { SelectionContainer { Text(text, Modifier.verticalScroll(rememberScrollState())) } },
        confirmButton = { TextButton(onClick = { notices = null }) { Text("Fechar") } }) }
}
