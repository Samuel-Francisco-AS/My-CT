package dev.myct

import java.io.InputStream
import java.io.OutputStream

/** Opaque SAF document URIs, never filesystem paths. Nullable metadata is normal for providers. */
internal data class SharedNode(val uri: String, val name: String, val folder: Boolean,
    val size: Long? = null, val modified: Long? = null, val writable: Boolean = false,
    val canCreate: Boolean = false, val canRename: Boolean = false, val virtual: Boolean = false)
internal data class SharedChildren(val nodes: List<SharedNode>, val limited: Boolean = false)
internal data class SharedTreeState(val tree: String? = null, val trail: List<String> = emptyList())
internal interface SharedTreeMemory { fun read(): SharedTreeState; fun write(state: SharedTreeState) }
internal interface SharedDocuments {
    fun persist(tree: String, flags: Int)
    fun permitted(tree: String): Boolean
    fun root(tree: String): SharedNode
    fun stat(tree: String, uri: String): SharedNode
    fun children(tree: String, uri: String): SharedChildren
    fun create(tree: String, parent: String, name: String, folder: Boolean): SharedNode
    fun rename(tree: String, uri: String, name: String): SharedNode
    fun read(tree: String, uri: String): InputStream
    /** Truncate the SAME document. Implementations must not create a replacement/copy. */
    fun write(tree: String, uri: String): OutputStream
}
internal class SharedPermissionLost : Exception()
internal class SharedWriteUncertain(cause: Exception? = null) : Exception(cause)

/** Small SAF backend for the existing IDE. All provider access runs on IO. */
internal class SharedWorkspace(private val provider: SharedDocuments, private val memory: SharedTreeMemory) {
    var tree: String? = memory.read().tree; private set
    private var trail = emptyList<SharedNode>()
    private val known = mutableMapOf<String, SharedNode>()
    private val parents = mutableMapOf<String, String>()
    var entries = emptyList<SharedNode>(); private set
    val current get() = trail.lastOrNull()?.uri
    fun node(uri: String) = known[uri] ?: throw EditRejected("Documento fora da navegação autorizada. Atualize a pasta.")
    fun parent(uri: String) = parents[uri] ?: trail.firstOrNull()?.uri.orEmpty()
    fun atRoot(uri: String) = trail.firstOrNull()?.uri == uri
    fun display(uri: String): String {
        val names = mutableListOf(node(uri).name)
        var current = uri
        repeat(64) {
            val next = parents[current] ?: return "Pasta compartilhada/" + names.reversed().joinToString("/")
            names.add(node(next).name); current = next
        }
        return "Pasta compartilhada/" + names.reversed().joinToString("/")
    }
    fun currentListing(): RemoteListing = publish(node(requireNotNull(current)), SharedChildren(entries))
    private fun granted(): String {
        val value = tree ?: throw SharedPermissionLost()
        if (!provider.permitted(value)) throw SharedPermissionLost()
        return value
    }
    private fun remember() = memory.write(SharedTreeState(tree, trail.map { it.uri }))
    fun select(value: String, flags: Int): RemoteListing {
        provider.persist(value, flags)
        if (!provider.permitted(value)) throw SharedPermissionLost()
        if (tree == value) return list()
        val root = provider.root(value)
        if (!root.folder) throw EditRejected("Selecione uma pasta pelo Android.")
        val children = provider.children(value, root.uri)
        memory.write(SharedTreeState(value, listOf(root.uri)))
        tree = value; trail = listOf(root); known.clear(); parents.clear()
        return publish(root, children)
    }
    private fun publish(directory: SharedNode, children: SharedChildren): RemoteListing {
        known[directory.uri] = directory
        entries = children.nodes
        entries.forEach { known[it.uri] = it; parents[it.uri] = directory.uri }
        return RemoteListing(directory.uri, entries.map { RemoteEntry(it.name,
            if (it.folder) RemoteKind.DIRECTORY else if (it.virtual) RemoteKind.OTHER else RemoteKind.FILE,
            it.size ?: 0, (it.modified ?: 0) / 1000) }, children.limited)
    }
    fun list(uri: String? = null): RemoteListing {
        val grant = granted()
        if (trail.isEmpty()) {
            val root = provider.root(grant); known[root.uri] = root; trail = listOf(root)
            // Restore only ancestors rediscovered through provider child IDs, never URI prefixes.
            for (saved in memory.read().trail.drop(1).take(64)) {
                val next = provider.children(grant, trail.last().uri).nodes.find { it.uri == saved && it.folder } ?: break
                parents[next.uri] = trail.last().uri; known[next.uri] = next; trail = trail + next
            }
        }
        val target = if (uri.isNullOrEmpty()) { if (uri == "") trail.first().uri else trail.last().uri } else uri
        val directory = node(target)
        if (!directory.folder) throw EditRejected("O item não é uma pasta.")
        val children = provider.children(grant, target)
        val index = trail.indexOfFirst { it.uri == target }
        val nextTrail = if (index >= 0) trail.take(index + 1) else {
            if (parents[target] != trail.last().uri) throw EditRejected("Navegue a partir da pasta atual.")
            if (trail.size >= 64) throw EditRejected("Limite de navegação: 64 diretórios.")
            trail + directory
        }
        trail = nextTrail; remember()
        return publish(directory, children)
    }
    private fun metadata(node: SharedNode) = EditMetadata(node.size ?: -1, node.modified ?: -1,
        if (node.writable) 0x180 else 0x100, 0, 0)
    fun load(uri: String): EditSnapshot {
        node(uri) // only documents discovered inside this tree
        val grant = granted()
        val before = provider.stat(grant, uri)
        if (before.folder || before.virtual) throw EditRejected("Abra um documento textual comum, não uma pasta ou documento virtual.")
        if ((before.size ?: 0) > IDE_MAX_BYTES) throw EditRejected("Limite de edição: 1 MiB.")
        val bytes = provider.read(grant, uri).use(::readIdeBytes)
        val after = provider.stat(grant, uri)
        if (before != after || (before.size != null && before.size != bytes.size.toLong())) throw EditConflict()
        known[uri] = after
        return EditSnapshot(uri, bytes, metadata(after))
    }
    fun save(original: EditSnapshot, bytes: ByteArray): EditSnapshot {
        decodeCode(bytes)
        val current = load(original.path)
        if (current.metadata != original.metadata || !current.bytes.contentEquals(original.bytes)) throw EditConflict()
        if (!node(original.path).writable) throw EditRejected("O provedor permite apenas leitura deste documento.")
        val grant = granted()
        // From open-for-write onward a provider may already truncate, even if opening throws.
        try {
            provider.write(grant, original.path).use { it.write(bytes); it.flush() }
            return load(original.path).also { if (!it.bytes.contentEquals(bytes)) throw IntegrityFailure() }
        } catch (e: Exception) { throw SharedWriteUncertain(e) }
    }
    private fun validName(value: String): String {
        if (value.isBlank() || value in setOf(".", "..") || value.any { it == '/' || it == '\\' || it.code < 32 || it.code == 127 } ||
            value.toByteArray(Charsets.UTF_8).size > 240) throw EditRejected("Nome inválido: use até 240 bytes, sem barras ou controles.")
        return value
    }
    private fun unique(directory: String, name: String, except: String? = null) {
        val list = provider.children(granted(), directory)
        if (list.limited) throw EditRejected("Pasta muito grande para conferir nomes; escolha uma subpasta.")
        if (list.nodes.any { it.name == name && it.uri != except }) throw EditRejected("Já existe um item com esse nome.")
    }
    fun create(name: String, folder: Boolean): SharedNode {
        val grant = granted(); val directory = current ?: throw SharedPermissionLost()
        validName(name)
        if (!provider.stat(grant, directory).canCreate) throw EditRejected("O provedor não permite criar nesta pasta.")
        unique(directory, name)
        // Creation failure may leave a new empty item; never retry automatically.
        val result = provider.create(grant, directory, name, folder)
        known[result.uri] = result; parents[result.uri] = directory
        return result
    }
    fun rename(uri: String, name: String, open: EditSnapshot?): Pair<SharedNode, EditSnapshot?> {
        val grant = granted(); validName(name)
        val item = node(uri)
        if (!item.canRename || atRoot(uri)) throw EditRejected("O provedor não permite renomear este item.")
        if (item.folder && open != null) {
            var ancestor = parent(open.path)
            while (true) {
                if (ancestor == uri) throw EditRejected("Feche o documento antes de renomear sua pasta; o provedor pode mudar os IDs dos descendentes.")
                val next = parent(ancestor); if (next == ancestor) break; ancestor = next
            }
        }
        unique(parent(uri), name, uri)
        val editing = open?.takeIf { it.path == uri }
        editing?.let { original ->
            val current = load(uri)
            if (current.metadata != original.metadata || !current.bytes.contentEquals(original.bytes)) throw EditConflict()
        }
        try {
            val result = provider.rename(grant, uri, name)
            known[result.uri] = result; parents[result.uri] = parent(uri)
            val snapshot = editing?.let { original -> load(result.uri).also {
                if (!it.bytes.contentEquals(original.bytes)) throw EditConflict()
            } }
            entries = entries.map { if (it.uri == uri) result else it }
            if (result.uri != uri) known.remove(uri)
            return result to snapshot
        } catch (e: Exception) { throw SharedWriteUncertain(e) }
    }
}
