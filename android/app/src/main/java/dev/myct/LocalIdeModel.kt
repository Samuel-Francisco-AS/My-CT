package dev.myct

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream

internal class ExportCopyFailure(cause: Exception) : Exception(cause)

/** SAF grants are used only for the chosen stream; never retained or sent to SFTP. */
internal object IdeDocuments {
    fun importBytes(open: () -> InputStream?): ByteArray =
        (open() ?: throw EditRejected("O provedor não abriu o documento. Confira a permissão.")).use(::readIdeBytes)

    /** Caller must supply a NEW document returned by ACTION_CREATE_DOCUMENT. */
    fun exportCopy(bytes: ByteArray, open: () -> OutputStream?, verify: () -> InputStream?): Boolean {
        decodeCode(bytes)
        (open() ?: throw EditRejected("O provedor não abriu o destino. Confira a permissão.")).use {
            it.write(bytes); it.flush()
        }
        // Some providers do not allow immediate read-back. Do not call that verified success.
        val input = runCatching { verify() }.getOrNull() ?: return false
        return input.use { readIdeBytes(it).contentEquals(bytes) }.also { if (!it) throw IntegrityFailure() }
    }
}

internal class LocalIdeModel(private val workspace: LocalWorkspace,
                             dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
                             private val io: CoroutineDispatcher = Dispatchers.IO) : IdeSession {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    override val scroll = IdeScrollHistory()
    override val connected = true // available without authentication
    override var busy by mutableStateOf(false); private set
    override var listing by mutableStateOf<RemoteListing?>(null); private set
    override var document by mutableStateOf<IdeDocument?>(null); private set
    override var browse by mutableStateOf(true)
    override var message by mutableStateOf("Projetos locais · workspace privada · disponível offline."); private set
    override var noticeIsError by mutableStateOf(false); private set
    var imported by mutableStateOf(false); private set
    // Captured before launching SAF. Returning to another environment cannot change export source.
    private var exportChoice: EditSnapshot? = null
    override val canSave get() = !busy && document?.let { it.identity == LOCAL_ID && it.dirty && !it.uncertain } == true
    override fun child(parent: String, name: String) = workspace.child(parent, name)
    override fun parent(path: String) = workspace.parent(path)
    override fun atRoot(path: String) = path.isEmpty()
    override fun displayPath(path: String) = "Projetos locais" + if (path.isEmpty()) "/" else "/$path"

    private fun <T> operation(work: () -> T, done: (T) -> Unit) {
        if (busy) return
        busy = true; noticeIsError = false
        scope.launch {
            try { val result = withContext(io) { work() }; done(result) }
            catch (e: Exception) {
                noticeIsError = true
                message = when (e) {
                    is EditConflict -> "Conflito local: o arquivo mudou. Nada foi sobrescrito; buffer preservado. Reabra para comparar."
                    is EditRejected -> e.explanation
                    is LocalWriteUncertain -> {
                        document?.uncertain = true
                        "Resultado local incerto. Buffer preservado; confira o arquivo e reabra antes de salvar novamente."
                    }
                    is ExportCopyFailure -> "Exportação sem confirmação: cópia externa pode estar vazia ou parcial. Confira o destino e a permissão; workspace e buffer preservados."
                    is SecurityException -> "Permissão do documento negada ou revogada. Buffer preservado. Selecione novamente pelo Android."
                    is java.nio.file.FileAlreadyExistsException -> "Já existe um item com esse nome. Nada foi substituído."
                    else -> "Falha de leitura/escrita local ou do provedor. Buffer preservado; confira espaço e permissões."
                }
            } finally { busy = false }
        }
    }
    override fun cancel() {
        // Small private-file transactions must finish to report a definite result.
        // SAF providers may block; cancelling a coroutine cannot safely interrupt their commit.
        message = "Aguarde a operação local terminar; não há cancelamento durante a gravação."
    }
    override fun loadDirectory(path: String?) {
        operation({ workspace.list(path ?: "") }) {
            listing = it; browse = true
            message = if (it.limited) "Lista limitada a 2000 entradas." else "Workspace privada · toque em texto para editar."
        }
    }
    override fun open(path: String) {
        operation({ workspace.load(path) to workspace.isImported(path) }) { (snapshot, origin) ->
            document = IdeDocument(LOCAL_ID, snapshot); imported = origin; browse = false
            message = "${if (origin) "Cópia importada via SAF" else "Workspace privada"} · UTF-8 · ${snapshot.bytes.size} bytes · salve explicitamente."
        }
    }
    override fun closeDocument() { if (!busy) { document = null; imported = false; browse = true } }
    override fun save() {
        if (!canSave) return
        val doc = document ?: return
        val version = doc.content.documentVersion
        val text = doc.content.toString() // one snapshot per explicit save, never per keystroke
        operation({ workspace.save(doc.snapshot, encodeCode(text)) }) {
            if (document === doc) {
                doc.confirmSaved(EditSaved(it), version)
                message = if (doc.dirty) "Versão salva localmente; há novas alterações no buffer." else "Salvo na workspace e relido com sucesso."
            }
        }
    }
    fun create(name: String, folder: Boolean) {
        val directory = listing?.path ?: ""
        operation({ val path = workspace.create(directory, name, folder); path to workspace.list(directory) }) { (path, list) ->
            listing = list; browse = true
            message = "${if (folder) "Pasta" else "Arquivo"} criado: ${path.substringAfterLast('/')}. Toque para abrir."
        }
    }
    fun rename(path: String, name: String) {
        val directory = listing?.path ?: ""
        operation({ val next = workspace.rename(path, name); next to workspace.list(directory) }) { (next, list) ->
            // Rename of an open file or ancestor keeps Content, undo, dirty and snapshot bytes.
            document?.let { doc -> if (doc.path == path || doc.path.startsWith("$path/"))
                doc.snapshot = doc.snapshot.copy(path = next + doc.path.removePrefix(path)) }
            listing = list; message = "Renomeado para $name. Buffer preservado."
        }
    }
    fun importDocument(open: (() -> InputStream?)?, filename: () -> String = { "documento.txt" }) {
        if (open == null) { if (!busy) { noticeIsError = false; message = "Importação cancelada; nenhum arquivo alterado." }; return }
        operation({
            val path = workspace.importCopy(filename(), IdeDocuments.importBytes(open))
            path to workspace.list(workspace.parent(path))
        }) { (path, list) ->
            listing = list; browse = true
            message = "Cópia importada: ${displayPath(path)}. Origem externa preservada; toque para abrir."
        }
    }
    fun prepareExport(): String? {
        if (busy) return null
        val doc = document ?: return null
        if (doc.identity != LOCAL_ID || doc.dirty || doc.uncertain) return null
        exportChoice = doc.snapshot.copy(bytes = doc.snapshot.bytes.copyOf())
        return doc.path.substringAfterLast('/')
    }
    fun exportDocument(open: (() -> OutputStream?)?, verify: () -> InputStream? = { null }) {
        val choice = exportChoice; exportChoice = null
        if (open == null) { if (!busy) { noticeIsError = false; message = "Exportação cancelada; workspace preservada." }; return }
        if (choice == null) { noticeIsError = true; message = "Exportação sem origem válida. Salve e escolha o destino novamente."; return }
        operation({
            try { IdeDocuments.exportCopy(choice.bytes, open, verify) }
            catch (e: Exception) { throw ExportCopyFailure(e) }
        }) { verified ->
            noticeIsError = !verified
            message = if (verified) "Cópia exportada e relida com sucesso. Workspace preservada."
                else "Cópia enviada ao provedor, sem confirmação de releitura. Confira o destino; workspace preservada."
        }
    }
    fun pickerFailed() {
        exportChoice = null
        noticeIsError = true; message = "Não foi possível abrir o seletor Android. Workspace e buffer preservados."
    }
    companion object { const val LOCAL_ID = "android-private-workspace" }
}
