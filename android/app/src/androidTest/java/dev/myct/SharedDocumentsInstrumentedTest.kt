package dev.myct

import androidx.core.net.toUri
import androidx.core.content.IntentCompat
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document as Doc
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Generated cache fixtures in the TEST APK. No production document, storage permission or shell. */
class SharedFixtureProvider : DocumentsProvider() {
    private data class Item(val id: String, val name: String, val parent: String?, val file: File, val mime: String)
    private val items = linkedMapOf<String, Item>()
    private var sequence = 0
    override fun onCreate(): Boolean {
        val dir = File(requireNotNull(context).cacheDir, "shared-provider-fixture-${System.nanoTime()}").apply { mkdirs() }
        items["root"] = Item("root", "Fixture", null, dir, Doc.MIME_TYPE_DIR)
        return true
    }
    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: arrayOf(
        Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS)).apply {
        val row = newRow()
        columnNames.forEach { column -> row.add(column, when (column) {
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID -> "root"
            Root.COLUMN_TITLE -> "MyCT test fixture"
            Root.COLUMN_FLAGS -> Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD
            else -> null
        }) }
    }
    private fun cursor(projection: Array<out String>?, values: List<Item>): Cursor = MatrixCursor(projection ?: arrayOf(
        Doc.COLUMN_DOCUMENT_ID, Doc.COLUMN_DISPLAY_NAME, Doc.COLUMN_MIME_TYPE, Doc.COLUMN_FLAGS,
        Doc.COLUMN_SIZE, Doc.COLUMN_LAST_MODIFIED)).apply {
        values.forEach { item ->
            val row = newRow()
            columnNames.forEach { column -> row.add(column, when (column) {
                Doc.COLUMN_DOCUMENT_ID -> item.id
                Doc.COLUMN_DISPLAY_NAME -> item.name
                Doc.COLUMN_MIME_TYPE -> item.mime
                Doc.COLUMN_FLAGS -> if (item.file.isDirectory) Doc.FLAG_DIR_SUPPORTS_CREATE or Doc.FLAG_SUPPORTS_RENAME
                    else Doc.FLAG_SUPPORTS_WRITE or Doc.FLAG_SUPPORTS_RENAME
                Doc.COLUMN_SIZE -> if (item.file.isDirectory) null else item.file.length()
                Doc.COLUMN_LAST_MODIFIED -> null // exercise a provider with missing timestamps
                else -> null
            }) }
        }
    }
    override fun queryDocument(documentId: String, projection: Array<out String>?) = cursor(projection, listOf(items.getValue(documentId)))
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?) =
        cursor(projection, items.values.filter { it.parent == parentDocumentId })
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        var current: String? = documentId
        repeat(64) {
            if (current == parentDocumentId) return true
            current = items[current]?.parent ?: return false
        }
        return false
    }
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = items.getValue(parentDocumentId)
        val id = "opaque:${++sequence}/id" // intentionally unrelated to display name or disk path
        val file = File(parent.file, "fixture-${sequence}")
        check(if (mimeType == Doc.MIME_TYPE_DIR) file.mkdir() else file.createNewFile())
        items[id] = Item(id, displayName, parentDocumentId, file, mimeType)
        return id
    }
    override fun renameDocument(documentId: String, displayName: String): String {
        val item = items.remove(documentId)!!
        val id = "renamed:${++sequence}/opaque"
        items[id] = item.copy(id = id, name = displayName)
        return id
    }
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?) =
        ParcelFileDescriptor.open(items.getValue(documentId).file, ParcelFileDescriptor.parseMode(mode))
    override fun deleteDocument(documentId: String) {
        val item = items.getValue(documentId)
        check(item.file.delete()); items.remove(documentId)
    }
}

/** Compile on host, execute explicitly on Android; picker/persistence still need the owner's gate. */
class SharedDocumentsInstrumentedTest {
    private val resolver get() = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
    private val tree = DocumentsContract.buildTreeDocumentUri("dev.myct.test.shared", "root").toString()
    @Test fun treeIntentRequestsExplicitReadWriteAndPersistenceWithoutBroadStoragePermission() {
        val intent = sharedTreeIntent(tree)
        assertEquals(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE, intent.action)
        assertEquals(3, intent.flags and 3)
        assertTrue(intent.flags and android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0)
        assertEquals(tree, IntentCompat.getParcelableExtra(intent, DocumentsContract.EXTRA_INITIAL_URI, android.net.Uri::class.java)?.toString())
    }
    @Test fun resolverCreatesListsTruncatesSameUriAndFollowsRenameId() {
        if (android.os.Build.VERSION.SDK_INT < 29) {
            org.junit.Assume.assumeTrue("Fixture resolver test needs shell permission adoption (API 29+)", false)
            return
        }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity(android.Manifest.permission.MANAGE_DOCUMENTS)
        val provider = AndroidSharedDocuments(resolver)
        var dir: SharedNode? = null
        var file: SharedNode? = null
        try {
            val root = provider.root(tree)
            dir = provider.create(tree, root.uri, "Projeto-${System.nanoTime()}", true)
            file = provider.create(tree, dir.uri, "teste.c", false)
            assertEquals("teste.c", file.name)
            val originalUri = file.uri
            provider.write(tree, originalUri).use { it.write("long original code".toByteArray()) }
            provider.write(tree, originalUri).use { it.write("C\n".toByteArray()) }
            assertEquals("C\n", provider.read(tree, originalUri).use { it.readBytes().toString(Charsets.UTF_8) })
            assertEquals(listOf(originalUri), provider.children(tree, dir.uri).nodes.map { it.uri })
            assertNull(provider.stat(tree, originalUri).modified)
            file = provider.rename(tree, originalUri, "renamed.c")
            assertNotEquals(originalUri, file.uri)
            assertEquals("C\n", provider.read(tree, file.uri).use { it.readBytes().toString(Charsets.UTF_8) })
            try { provider.read(tree, "/storage/emulated/0/teste.c"); fail("POSIX path must be rejected") }
            catch (_: EditRejected) { }
        } finally {
            try {
                file?.let { DocumentsContract.deleteDocument(resolver, it.uri.toUri()) }
                dir?.let { DocumentsContract.deleteDocument(resolver, it.uri.toUri()) }
            } finally { automation.dropShellPermissionIdentity() }
        }
    }
    @Test fun preferenceStorageRoundTripsOpaqueUris() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "ide-shared-tree-test-${System.nanoTime()}"
        val memory = AndroidSharedTreeMemory(context, name) // isolated generated test preferences only
        val original = memory.read()
        try {
            val value = SharedTreeState(tree, listOf("content://fixture/document/a%2Fb", "content://fixture/document/opaque%3AID"))
            memory.write(value)
            assertEquals(value, AndroidSharedTreeMemory(context, name).read())
        } finally { memory.write(original); context.deleteSharedPreferences(name) }
    }
}
