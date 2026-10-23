package dev.myct

import androidx.compose.runtime.*
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.ContentListener
import kotlinx.coroutines.*

internal class IdeDocument(val identity: String, var snapshot: EditSnapshot) {
    val content = Content(decodeCode(snapshot.bytes))
    var viewport: IdeViewport? = null
    var dirty by mutableStateOf(false)
    var uncertain by mutableStateOf(false)
    var backupPath by mutableStateOf<String?>(null)
    val path get() = snapshot.path
    val readOnly get() = snapshot.metadata.mode and 0x80 == 0 || snapshot.metadata.mode and 0xE00 != 0
    fun confirmSaved(saved: EditSaved, sentVersion: Long) {
        snapshot = saved.snapshot
        // A queued IME event may arrive before AndroidView becomes read-only. Never mark
        // newer local edits clean when an older save acknowledgement arrives.
        dirty = content.documentVersion != sentVersion
        backupPath = saved.cleanupPath
    }
    init {
        content.addContentListener(object : ContentListener {
            override fun beforeReplace(content: Content) = Unit
            override fun afterInsert(content: Content, sl: Int, sc: Int, el: Int, ec: Int, text: CharSequence) { dirty = true }
            override fun afterDelete(content: Content, sl: Int, sc: Int, el: Int, ec: Int, text: CharSequence) { dirty = true }
        })
    }
}

/** Process-owned buffer/undo, independent of section composition and of SSH connection lifetime. */
internal class RemoteIdeModel(
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val openStore: (SshConnection, TransferControl) -> RemoteEditorStore = { connection, token -> connection.openFiles(token) as RemoteEditorStore }
) : IdeSession {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var connection: SshConnection? = null
    private var identity = ""
    private var epoch = 0
    override val scroll = IdeScrollHistory()
    private var control: TransferControl? = null
    private var savingDocument: IdeDocument? = null
    private var savingBackupPath: String? = null
    override var connected by mutableStateOf(false); private set
    override var busy by mutableStateOf(false); private set
    override var listing by mutableStateOf<RemoteListing?>(null); private set
    override var document by mutableStateOf<IdeDocument?>(null); private set
    override var browse by mutableStateOf(true)
    override var message by mutableStateOf("Conecte pelo Terminal para abrir arquivos do Fedora."); private set
    override var noticeIsError by mutableStateOf(false); private set
    override val canSave get() = connected && !busy && document?.let { it.identity == identity && it.dirty && !it.uncertain && !it.readOnly } == true

    override fun child(parent: String, name: String) = RemotePath.child(parent, name)
    override fun parent(path: String) = RemotePath.parent(path)
    override fun displayPath(path: String) = path
    override fun atRoot(path: String) = path == "/"

    fun attach(value: SshConnection?, remoteIdentity: String = identity) {
        if (value === connection && remoteIdentity == identity) return
        savingDocument?.apply { uncertain = true; backupPath = savingBackupPath }
        savingDocument = null
        epoch++
        control?.let { token -> scope.launch(io) { token.cancel() } }
        control = null; busy = false
        connection = value; connected = value != null
        if (remoteIdentity != identity) { listing = null; scroll.clear() }
        identity = remoteIdentity
        noticeIsError = value == null && document?.dirty == true
        message = if (value == null) "SSH desconectado. Buffer preservado em memória; reconecte ao mesmo servidor para salvar."
            else if (document != null && document?.identity != identity) "Buffer de outra conexão: salve na conexão original ou descarte explicitamente."
            else "SFTP pronto. Abra uma pasta/projeto ou continue a edição."
    }
    private fun <T> operation(work: (RemoteEditorStore, TransferControl) -> T, done: (T) -> Unit) {
        val current = connection ?: return
        if (busy) return
        val id = epoch
        val token = TransferControl(); control = token; busy = true; noticeIsError = false
        scope.launch {
            try {
                val result = withContext(io) {
                    openStore(current, token).use { store -> work(store, token) }
                }
                if (id == epoch) done(result)
            } catch (error: Exception) {
                if (id == epoch) {
                    noticeIsError = true
                    message = when (error) {
                        is EditConflict -> "Conflito: o arquivo mudou no Fedora. Seu buffer foi mantido. Reabra para comparar; nada foi sobrescrito."
                        is EditRejected -> error.explanation
                        is EditUncertain -> {
                            document?.apply { uncertain = true; backupPath = error.backupPath }
                            "Gravação sem confirmação. Buffer mantido; confira o destino e o backup antes de reabrir."
                        }
                        is EditCleanupFailed -> "Original preservado, mas a limpeza SFTP falhou em ${error.directory}. Confira antes de remover."
                        is java.util.concurrent.CancellationException -> "Operação cancelada; buffer mantido."
                        else -> "Falha SFTP ou permissão negada. Buffer mantido; confira a conexão e as permissões."
                    }
                }
            } finally {
                if (id == epoch) { control = null; busy = false; savingDocument = null }
            }
        }
    }
    override fun cancel() { control?.let { token -> scope.launch(io) { token.cancel() } } }
    override fun loadDirectory(path: String?) {
        operation({ store, token ->
            val directory = store.canonical(path?.let(RemotePath::absolute) ?: store.home())
            token.check(); store.list(directory)
        }) { listing = it; browse = true; message = if (it.limited) "Lista limitada a 2000 entradas." else "Toque em um arquivo textual para editar." }
    }
    /** Caller confirms dirty-buffer replacement first. No remote write on opening or discarding. */
    override fun open(path: String) {
        val owner = identity
        operation({ store, token -> RemoteEditor.load(store, path, token) }) {
            document = IdeDocument(owner, it); browse = false
            message = if (document?.readOnly == true) "Somente leitura: arquivo sem escrita de proprietário ou com bits especiais."
                else "UTF-8 · ${it.bytes.size} bytes · alterações só serão enviadas ao tocar em Salvar."
        }
    }
    override fun closeDocument() { if (!busy) { document = null; browse = true } }
    override fun save() {
        if (!canSave) return
        val doc = document ?: return
        // A single snapshot per explicit save; never copy the whole document on each keystroke.
        val version = doc.content.documentVersion
        val text = doc.content.toString()
        val directory = RemotePath.child(RemotePath.parent(doc.path), ".myct-edit-${java.util.UUID.randomUUID()}")
        savingDocument = doc
        savingBackupPath = RemotePath.child(directory, "original")
        operation({ store, token -> RemoteEditor.save(store, doc.snapshot, encodeCode(text), token, directory) }) { result ->
            if (document === doc) {
                doc.confirmSaved(result, version)
                message = when {
                    result.cleanupPath != null -> "Arquivo salvo e verificado, mas a limpeza do diretório temporário falhou. Consulte Detalhes."
                    doc.dirty -> "Versão enviada salva. Ainda há alterações locais não salvas."
                    else -> "Salvo e relido. Backup e temporários removidos."
                }
            }
        }
    }
}
