package dev.myct

import android.annotation.SuppressLint
import androidx.core.net.toUri
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document as Doc
import org.json.JSONArray

internal fun sharedTreeIntent(initial: String? = null) = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
    initial?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it.toUri()) }
}

internal class AndroidSharedTreeMemory(context: Context, preferencesName: String = "ide-shared-tree") : SharedTreeMemory {
    private val prefs = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    override fun read() = SharedTreeState(prefs.getString("tree", null), runCatching {
        val array = JSONArray(prefs.getString("trail", "[]") ?: "[]")
        (0 until minOf(array.length(), 64)).map { array.getString(it) }
    }.getOrDefault(emptyList()))
    @SuppressLint("UseKtx") // commit() result is required to report persistence failure.
    override fun write(state: SharedTreeState) {
        if (!prefs.edit().putString("tree", state.tree).putString("trail", JSONArray(state.trail).toString()).commit())
            throw EditRejected("Não foi possível persistir a pasta. Confira o espaço privado do aplicativo.")
    }
}

/** ContentResolver adapter. Never derives a path from an ID, even for ExternalStorageProvider. */
internal class AndroidSharedDocuments(private val resolver: ContentResolver) : SharedDocuments {
    private val columns = arrayOf(Doc.COLUMN_DOCUMENT_ID, Doc.COLUMN_DISPLAY_NAME, Doc.COLUMN_MIME_TYPE,
        Doc.COLUMN_SIZE, Doc.COLUMN_LAST_MODIFIED, Doc.COLUMN_FLAGS)
    private val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    private fun tree(value: String): Uri = value.toUri().also {
        if (it.scheme != "content" || it.authority.isNullOrEmpty() || !DocumentsContract.isTreeUri(it))
            throw EditRejected("O seletor não retornou uma árvore SAF válida.")
    }
    private fun document(grant: String, value: String): Uri = value.toUri().also {
        val root = tree(grant)
        if (it.scheme != "content" || it.authority != root.authority || !DocumentsContract.isTreeUri(it) ||
            DocumentsContract.getTreeDocumentId(it) != DocumentsContract.getTreeDocumentId(root))
            throw EditRejected("Documento fora da árvore autorizada.")
        DocumentsContract.getDocumentId(it) // require a document URI, not a POSIX path/tree alone
    }
    override fun persist(tree: String, flags: Int) {
        val uri = tree(tree)
        if (flags and rw != rw) throw SharedPermissionLost()
        resolver.takePersistableUriPermission(uri, flags and rw)
    }
    override fun permitted(tree: String): Boolean {
        val uri = tree(tree)
        return resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }
    }
    private fun row(grant: String, cursor: android.database.Cursor): SharedNode {
        fun string(column: String) = cursor.getString(cursor.getColumnIndexOrThrow(column))
        fun long(column: String): Long? = cursor.getColumnIndexOrThrow(column).let { if (cursor.isNull(it)) null else cursor.getLong(it) }
        val flags = long(Doc.COLUMN_FLAGS)?.toInt() ?: 0
        val id = string(Doc.COLUMN_DOCUMENT_ID) ?: throw EditRejected("Documento sem identificador SAF.")
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree(grant), id)
        return SharedNode(uri.toString(), string(Doc.COLUMN_DISPLAY_NAME) ?: "(sem nome)",
            string(Doc.COLUMN_MIME_TYPE) == Doc.MIME_TYPE_DIR,
            long(Doc.COLUMN_SIZE), long(Doc.COLUMN_LAST_MODIFIED),
            flags and Doc.FLAG_SUPPORTS_WRITE != 0, flags and Doc.FLAG_DIR_SUPPORTS_CREATE != 0,
            flags and Doc.FLAG_SUPPORTS_RENAME != 0, flags and Doc.FLAG_VIRTUAL_DOCUMENT != 0)
    }
    override fun root(tree: String) = stat(tree, DocumentsContract.buildDocumentUriUsingTree(tree(tree),
        DocumentsContract.getTreeDocumentId(tree(tree))).toString())
    override fun stat(tree: String, uri: String): SharedNode =
        resolver.query(document(tree, uri), columns, null, null, null)?.use {
            if (!it.moveToFirst()) throw java.io.FileNotFoundException()
            row(tree, it)
        } ?: throw EditRejected("O provedor não respondeu. Tente atualizar a pasta.")
    override fun children(tree: String, uri: String): SharedChildren {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree(tree),
            DocumentsContract.getDocumentId(document(tree, uri)))
        return resolver.query(childrenUri, columns, null, null, null)?.use { cursor ->
            if (cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false))
                throw EditRejected("O provedor ainda está carregando. Atualize a pasta em instantes.")
            cursor.extras.getString(DocumentsContract.EXTRA_ERROR)?.let { throw EditRejected("O provedor não conseguiu listar a pasta.") }
            val nodes = mutableListOf<SharedNode>()
            while (nodes.size < 2000 && cursor.moveToNext()) nodes.add(row(tree, cursor))
            SharedChildren(nodes.sortedWith(compareBy<SharedNode> { !it.folder }.thenBy { it.name }), cursor.moveToNext())
        } ?: throw EditRejected("O provedor não respondeu. Atualize a pasta.")
    }
    override fun create(tree: String, parent: String, name: String, folder: Boolean): SharedNode {
        val uri = DocumentsContract.createDocument(resolver, document(tree, parent),
            if (folder) Doc.MIME_TYPE_DIR else android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT))
                ?: "application/octet-stream", name)
            ?: throw EditRejected("Criação sem confirmação. Atualize a pasta antes de tentar novamente; pode haver um item vazio.")
        return stat(tree, uri.toString())
    }
    override fun rename(tree: String, uri: String, name: String): SharedNode {
        val result = DocumentsContract.renameDocument(resolver, document(tree, uri), name)
            ?: throw SharedWriteUncertain()
        return stat(tree, result.toString()) // returned ID can differ completely
    }
    override fun read(tree: String, uri: String) = resolver.openInputStream(document(tree, uri))
        ?: throw EditRejected("O provedor não abriu o documento para leitura.")
    override fun write(tree: String, uri: String) = resolver.openOutputStream(document(tree, uri), "wt")
        ?: throw SharedWriteUncertain() // explicit truncate, no fallback to append/non-truncating mode
}
