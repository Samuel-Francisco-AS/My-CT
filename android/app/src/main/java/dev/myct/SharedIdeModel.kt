package dev.myct

import androidx.compose.runtime.*
import kotlinx.coroutines.*

internal data class PendingSharedTree(val uri: String, val flags: Int)

internal class SharedIdeModel(private val workspace: SharedWorkspace,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val io: CoroutineDispatcher = Dispatchers.IO) : IdeSession {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    override val scroll = IdeScrollHistory()
    override val connected = true
    override var busy by mutableStateOf(false); private set
    override var listing by mutableStateOf<RemoteListing?>(null, referentialEqualityPolicy()); private set
    var listingRevision by mutableIntStateOf(0); private set
    override var document by mutableStateOf<IdeDocument?>(null); private set
    override var browse by mutableStateOf(true)
    override var message by mutableStateOf("Selecione Documents/MyCT pelo Android para editar os mesmos arquivos no Termux."); private set
    override var noticeIsError by mutableStateOf(false); private set
    var permissionNeeded by mutableStateOf(workspace.tree == null); private set
    var pendingTree by mutableStateOf<PendingSharedTree?>(null); private set
    val tree get() = workspace.tree
    override val canSave get() = !busy && !permissionNeeded && document?.let { it.dirty && !it.uncertain && !it.readOnly } == true
    override fun parent(path: String) = workspace.parent(path)
    override fun child(parent: String, name: String) = listedNodes.single { it.name == name }.uri
    private var listedNodes = emptyList<SharedNode>()
    private fun publish(value: RemoteListing) {
        listedNodes = workspace.entries.toList()
        listing = value
        listingRevision++ // URI changes can be invisible in name/size/date presentation metadata.
    }
    override fun entryPath(parent: String, entry: RemoteEntry): String = listedNodes[
        requireNotNull(listing).entries.indexOfFirst { it === entry }].uri
    override fun atRoot(path: String) = workspace.atRoot(path)
    override fun fileName(path: String) = runCatching { workspace.node(path).name }.getOrDefault("Documento compartilhado")
    override fun displayPath(path: String) = runCatching { workspace.display(path) }.getOrDefault("Pasta compartilhada")
    fun canRename(path: String) = runCatching { workspace.node(path).canRename }.getOrDefault(false)
    val canCreate get() = listing?.let { runCatching { workspace.node(it.path).canCreate }.getOrDefault(false) } == true && !permissionNeeded
    private fun <T> operation(work: () -> T, done: (T) -> Unit) {
        if (busy) return
        busy = true; noticeIsError = false
        scope.launch {
            try { val result = withContext(io) { work() }; done(result); permissionNeeded = false }
            catch (e: Exception) {
                noticeIsError = true
                message = when (e) {
                    is SharedPermissionLost, is SecurityException -> {
                        permissionNeeded = true
                        "Autorização ausente ou revogada. Selecione novamente a pasta pelo Android; buffer preservado."
                    }
                    is EditConflict -> "Conflito externo: o documento mudou. Nada foi sobrescrito; buffer e undo preservados. Copie suas alterações antes de reabrir."
                    is SharedWriteUncertain -> {
                        document?.uncertain = true
                        if (e.cause is SecurityException || e.cause is SharedPermissionLost) permissionNeeded = true
                        "Resultado incerto no provedor: documento pode estar vazio, parcial ou renomeado. Buffer preservado. Confira no Termux/Arquivos e copie o buffer antes de reabrir; selecione a pasta novamente se a autorização foi revogada."
                    }
                    is EditRejected -> e.explanation
                    else -> "Falha no provedor SAF. Buffer preservado; confira permissão, espaço e se o documento ainda existe. Selecione a pasta novamente se necessário."
                }
            } finally { busy = false }
        }
    }
    fun chooseTree(uri: String?, flags: Int = 0, discard: Boolean = false) {
        if (busy) return
        if (uri == null) { message = "Seleção cancelada; pasta e buffer preservados."; return }
        if (uri != tree && (document?.dirty == true || document?.uncertain == true) && !discard) {
            pendingTree = PendingSharedTree(uri, flags); return
        }
        val same = uri == tree
        operation({ workspace.select(uri, flags) }) {
            publish(it); scroll.clear()
            if (!same) document = null
            browse = document == null
            pendingTree = null
            message = "Pasta compartilhada autorizada. Salvar atualiza o documento original."
        }
    }
    fun cancelTreeChange() { pendingTree = null }
    fun pickerFailed() { noticeIsError = true; message = "Não foi possível abrir o seletor Android. Pasta e buffer preservados." }
    override fun loadDirectory(path: String?) = operation({ workspace.list(path) }) {
        publish(it); browse = true
        message = if (it.limited) "Lista limitada a 2000 itens; use subpastas." else "Pasta compartilhada · documentos originais · toque para editar."
    }
    override fun open(path: String) = operation({ workspace.load(path) }) {
        document = IdeDocument("saf:$tree", it); browse = false
        message = if (document?.readOnly == true) "Compartilhado · somente leitura pelo provedor."
            else "Compartilhado · UTF-8 · Salvar grava neste mesmo documento."
    }
    override fun save() {
        if (!canSave) return
        val doc = document ?: return
        val version = doc.content.documentVersion
        val bytes = try { encodeCode(doc.content.toString()) } catch (e: Exception) {
            noticeIsError = true; message = (e as? EditRejected)?.explanation ?: "Texto inválido; buffer preservado."; return
        }
        operation({ workspace.save(doc.snapshot, bytes) }) {
            doc.confirmSaved(EditSaved(it), version)
            message = if (doc.dirty) "Versão salva no documento original; há novas alterações no buffer."
                else "Salvo no documento original e relido. Confira o mesmo arquivo no Termux."
        }
    }
    fun create(name: String, folder: Boolean) = operation({
        val node = workspace.create(name, folder); node to workspace.list()
    }) { (node, list) -> publish(list); browse = true; message = "${if (folder) "Pasta" else "Arquivo"} criado: ${node.name}. Toque para abrir." }
    fun rename(path: String, name: String) = operation({
        workspace.rename(path, name, document?.snapshot)
    }) { (node, snapshot) ->
        if (snapshot != null) document?.snapshot = snapshot
        message = "Renomeado para ${node.name}; buffer preservado. Atualize a pasta."
        publish(workspace.currentListing())
    }
    override fun closeDocument() { if (!busy) { document = null; browse = true } }
    override fun cancel() { message = "Aguarde o provedor terminar. Interromper a gravação não garante preservar o documento." }
}
