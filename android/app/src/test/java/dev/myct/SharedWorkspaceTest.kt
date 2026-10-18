package dev.myct

import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

class SharedWorkspaceTest {
    internal class Memory : SharedTreeMemory {
        var state = SharedTreeState()
        override fun read() = state
        override fun write(state: SharedTreeState) { this.state = state }
    }
    /** Simulates a provider with opaque IDs (including ID changes on rename), never disk paths. */
    internal class Provider : SharedDocuments {
        val tree = "content://fixture/tree/root%3Agrant"
        val root = SharedNode("content://fixture/tree/root%3Agrant/document/opaque-root", "MyCT", true, canCreate = true)
        val nodes = linkedMapOf(root.uri to root)
        val parents = mutableMapOf<String, String>()
        val bytes = mutableMapOf<String, ByteArray>()
        var granted = false
        var denyPersist = false
        var writes = 0
        var creates = 0
        var writeFailure = false
        var partialFailure = false
        var failReadback = false
        var truncateWrong = false
        var readFailure = false
        var limited = false
        var duringRead: (() -> Unit)? = null
        private var sequence = 0
        override fun persist(tree: String, flags: Int) {
            if (denyPersist || flags and 3 != 3) throw SecurityException()
            granted = true
        }
        override fun permitted(tree: String) = granted
        override fun root(tree: String) = root
        override fun stat(tree: String, uri: String) = nodes[uri] ?: throw java.io.FileNotFoundException()
        override fun children(tree: String, uri: String) = SharedChildren(nodes.values.filter { parents[it.uri] == uri }, limited)
        override fun create(tree: String, parent: String, name: String, folder: Boolean): SharedNode {
            creates++
            val uri = "content://fixture/tree/root%3Agrant/document/id%3A${++sequence}%2Fopaque"
            val node = SharedNode(uri, name, folder, if (folder) null else 0, null, !folder, folder, true)
            nodes[uri] = node; parents[uri] = parent
            if (!folder) bytes[uri] = byteArrayOf()
            return node
        }
        override fun rename(tree: String, uri: String, name: String): SharedNode {
            val old = nodes.remove(uri)!!
            val renamed = old.copy(uri = "${uri}-new-id", name = name, modified = 123)
            nodes[renamed.uri] = renamed; parents[renamed.uri] = parents.remove(uri)!!
            bytes.remove(uri)?.let { bytes[renamed.uri] = it }
            parents.replaceAll { _, value -> if (value == uri) renamed.uri else value }
            return renamed
        }
        override fun read(tree: String, uri: String): ByteArrayInputStream {
            if (readFailure) throw IOException()
            val result = bytes.getValue(uri).copyOf()
            duringRead?.also { duringRead = null }?.invoke()
            return ByteArrayInputStream(result)
        }
        override fun write(tree: String, uri: String): OutputStream {
            writes++
            bytes[uri] = byteArrayOf() // opening can truncate BEFORE an error is raised
            if (writeFailure) throw IOException()
            return object : ByteArrayOutputStream() {
                override fun write(value: ByteArray, offset: Int, count: Int) {
                    if (partialFailure) {
                        bytes[uri] = value.copyOfRange(offset, offset + minOf(count, 2))
                        throw IOException("Partial provider write")
                    }
                    super.write(value, offset, count)
                }
                override fun close() {
                    if (partialFailure) return
                    if (failReadback) readFailure = true
                    bytes[uri] = toByteArray() + if (truncateWrong) "tail".toByteArray() else byteArrayOf()
                    nodes[uri] = nodes.getValue(uri).copy(size = bytes.getValue(uri).size.toLong())
                }
            }
        }
        fun external(uri: String, value: String, metadata: Boolean = true) {
            bytes[uri] = value.toByteArray()
            if (metadata) nodes[uri] = nodes.getValue(uri).copy(size = value.toByteArray().size.toLong(), modified = 99)
        }
    }
    private class Fixture {
        val p = Provider(); val m = Memory(); val w = SharedWorkspace(p, m)
        init { w.select(p.tree, 3) }
        fun file() = w.create("teste.c", false).also { w.list() }.uri
        fun model() = SharedIdeModel(w, Dispatchers.Unconfined, Dispatchers.Unconfined).also { it.loadDirectory() }
    }
    private inline fun <reified T : Exception> rejected(block: () -> Unit) {
        try { block(); fail("Expected ${T::class.java.simpleName}") } catch (e: Exception) { assertTrue("Got $e", e is T) }
    }
    @Test fun selectPersistCreateReadWriteSameUriAndReopenWithoutDuplicate() {
        val f = Fixture(); val folder = f.w.create("ProjetoC", true)
        f.w.list(folder.uri); val uri = f.file()
        var snapshot = f.w.load(uri)
        repeat(4) { i ->
            snapshot = f.w.save(snapshot, "int main() { return $i; }\n".toByteArray())
            assertEquals(uri, snapshot.path)
        }
        assertEquals(2, f.p.creates); assertEquals(4, f.p.writes)
        val recreated = SharedWorkspace(f.p, f.m)
        val listing = recreated.list()
        assertEquals(folder.uri, listing.path)
        assertEquals(listOf("teste.c"), listing.entries.map { it.name })
        assertEquals("int main() { return 3; }\n", decodeCode(recreated.load(uri).bytes))
        assertEquals(f.p.tree, f.m.state.tree)
    }
    @Test fun navigationUsesIdsAndReturnsToRememberedParentAndRoot() {
        val f = Fixture(); val a = f.w.create("A", true); f.w.list(a.uri)
        val b = f.w.create("B", true); f.w.list(b.uri)
        assertEquals(a.uri, f.w.parent(b.uri)); assertEquals(b.uri, f.w.list().path)
        assertEquals("Pasta compartilhada/MyCT/A/B", f.w.display(b.uri))
        f.w.list(a.uri); assertEquals(a.uri, f.m.state.trail.last())
        assertTrue(f.w.atRoot(f.w.list("").path))
    }
    @Test fun restoredMissingFolderFallsBackToExistingAncestor() {
        val f = Fixture(); val a = f.w.create("A", true); f.w.list(a.uri)
        f.p.nodes.remove(a.uri)
        assertEquals(f.p.root.uri, SharedWorkspace(f.p, f.m).list().path)
    }
    @Test fun unselectedAndRevokedPermissionPreventWritingAndReselectionRestoresAccess() {
        val f = Fixture(); val uri = f.file(); val original = f.w.load(uri)
        f.p.granted = false
        rejected<SharedPermissionLost> { f.w.save(original, "mine".toByteArray()) }
        assertEquals(0, f.p.writes)
        f.w.select(f.p.tree, 3); f.w.save(original, "mine".toByteArray())
        assertEquals(1, f.p.writes)
        rejected<SharedPermissionLost> { SharedWorkspace(f.p, Memory()).list() }
    }
    @Test fun deniedSelectionRetainsPreviousGrantAndNavigation() {
        val f = Fixture(); val old = f.m.state
        f.p.denyPersist = true
        rejected<SecurityException> { f.w.select("another-tree", 3) }
        assertEquals(old, f.m.state); assertEquals(f.p.tree, f.w.tree)
        rejected<SecurityException> { f.w.select(f.p.tree, 1) }
    }
    @Test fun sameSizedExternalEditWithUnchangedMetadataIsDetectedBeforeTruncate() {
        val f = Fixture(); val uri = f.file(); f.p.external(uri, "old"); val original = f.w.load(uri)
        f.p.external(uri, "new", false)
        rejected<EditConflict> { f.w.save(original, "mine".toByteArray()) }
        assertEquals(0, f.p.writes); assertEquals("new", decodeCode(f.p.bytes.getValue(uri)))
    }
    @Test fun metadataChangesAndChangesDuringReadAreConflicts() {
        val f = Fixture(); val uri = f.file(); val original = f.w.load(uri)
        f.p.nodes[uri] = f.p.nodes.getValue(uri).copy(modified = 1)
        rejected<EditConflict> { f.w.save(original, byteArrayOf()) }
        f.p.duringRead = { f.p.nodes[uri] = f.p.nodes.getValue(uri).copy(modified = 2) }
        rejected<EditConflict> { f.w.load(uri) }; assertEquals(0, f.p.writes)
    }
    @Test fun unknownSizeAndTimestampStillCompareFullBytes() {
        val f = Fixture(); val uri = f.file(); f.p.nodes[uri] = f.p.nodes.getValue(uri).copy(size = null)
        f.p.external(uri, "old", false); val snapshot = f.w.load(uri)
        assertEquals(-1L, snapshot.metadata.size)
        f.p.external(uri, "new", false)
        rejected<EditConflict> { f.w.save(snapshot, "mine".toByteArray()) }; assertEquals(0, f.p.writes)
    }
    @Test fun failureOpeningWriteAfterTruncateAndFailedReadbackAreUncertain() {
        val f = Fixture(); val uri = f.file(); val original = f.w.load(uri)
        f.p.writeFailure = true
        rejected<SharedWriteUncertain> { f.w.save(original, "mine".toByteArray()) }
        f.p.writeFailure = false; f.p.truncateWrong = true
        rejected<SharedWriteUncertain> { f.w.save(original, "mine".toByteArray()) }
        assertEquals("minetail", decodeCode(f.p.bytes.getValue(uri)))
    }
    @Test fun partialStreamWriteAndUnreadableResultKeepBufferAndBlockRepeatSave() {
        for (partial in listOf(true, false)) {
            val f = Fixture(); val uri = f.file(); val model = f.model(); model.open(uri)
            val doc = model.document!!; doc.content.insert(0, 0, "mine")
            f.p.partialFailure = partial; f.p.failReadback = !partial
            model.save()
            assertTrue(doc.dirty); assertTrue(doc.uncertain); assertFalse(model.canSave)
            assertEquals("mine", doc.content.toString()); assertTrue(doc.content.canUndo())
            assertEquals(if (partial) "mi" else "mine", decodeCode(f.p.bytes.getValue(uri)))
            model.save(); assertEquals(1, f.p.writes)
        }
    }
    @Test fun documentCanStayOpenWhileBrowsingOtherFoldersAndSameTreeReselection() {
        val f = Fixture(); val folder = f.w.create("Projeto", true); f.w.list(folder.uri)
        val uri = f.file(); val model = f.model(); model.open(uri)
        val doc = model.document!!; doc.content.insert(0, 0, "mine")
        model.loadDirectory(""); model.chooseTree(f.p.tree, 3)
        assertSame(doc, model.document); assertTrue(doc.dirty); assertTrue(doc.content.canUndo())
        assertEquals("Pasta compartilhada/MyCT/Projeto/teste.c", model.displayPath(uri))
        model.save(); assertFalse(doc.dirty); assertEquals("mine", decodeCode(f.p.bytes.getValue(uri)))
    }
    @Test fun readonlyVirtualBinaryOversizeAndUnknownUriAreRejectedBeforeWriting() {
        val f = Fixture(); val uri = f.file()
        rejected<EditRejected> { f.w.load("/storage/emulated/0/teste.c") }
        f.p.nodes[uri] = f.p.nodes.getValue(uri).copy(writable = false)
        rejected<EditRejected> { f.w.save(f.w.load(uri), "mine".toByteArray()) }
        f.p.nodes[uri] = f.p.nodes.getValue(uri).copy(virtual = true)
        rejected<EditRejected> { f.w.load(uri) }
        f.p.nodes[uri] = f.p.nodes.getValue(uri).copy(virtual = false, size = null)
        f.p.bytes[uri] = byteArrayOf(0)
        rejected<EditRejected> { f.w.load(uri) }
        f.p.bytes[uri] = ByteArray(IDE_MAX_BYTES + 1) { 65 }
        rejected<EditRejected> { f.w.load(uri) }; assertEquals(0, f.p.writes)
    }
    @Test fun createAndRenameRejectCollisionsUnsafeNamesUnsupportedFlagsAndLimitedDirectory() {
        val f = Fixture(); val uri = f.file()
        rejected<EditRejected> { f.w.create("teste.c", false) }
        for (name in listOf("..", "../a", "a/b", "a\\b", "\u0001", "")) rejected<EditRejected> { f.w.create(name, false) }
        f.p.limited = true; rejected<EditRejected> { f.w.create("other.c", false) }; f.p.limited = false
        val other = f.w.create("other.c", false)
        rejected<EditRejected> { f.w.rename(other.uri, "teste.c", null) }
        f.p.nodes[uri] = f.p.nodes.getValue(uri).copy(canRename = false); f.w.list()
        rejected<EditRejected> { f.w.rename(uri, "new.c", null) }
        assertEquals(0, f.p.writes)
    }
    @Test fun renameFollowsReturnedIdKeepsBufferUndoAndSavesNewId() {
        val f = Fixture(); val uri = f.file(); val model = f.model(); model.open(uri)
        val doc = model.document!!; doc.content.insert(0, 0, "mine")
        model.rename(uri, "renamed.c")
        assertFalse(model.noticeIsError); assertTrue(doc.dirty); assertTrue(doc.content.canUndo())
        assertNotEquals(uri, doc.path); assertEquals("renamed.c", model.fileName(doc.path))
        assertEquals(listOf("renamed.c"), model.listing!!.entries.map { it.name })
        model.save(); assertFalse(doc.dirty); assertEquals("mine", decodeCode(f.p.bytes.getValue(doc.path)))
        assertFalse(f.p.bytes.containsKey(uri))
    }
    @Test fun folderWithOpenDescendantCannotInvalidateBufferOnRename() {
        val f = Fixture(); val folder = f.w.create("A", true); f.w.list(folder.uri); val uri = f.file()
        val snapshot = f.w.load(uri); f.w.list("")
        rejected<EditRejected> { f.w.rename(folder.uri, "B", snapshot) }
        assertEquals("A", f.p.nodes.getValue(folder.uri).name)
        val renamed = f.w.rename(folder.uri, "B", null).first; assertEquals("B", renamed.name)
    }
    @Test fun modelConflictRevocationAndUncertainSavePreserveDirtyBufferAndUndo() {
        val f = Fixture(); val uri = f.file(); val model = f.model(); model.open(uri)
        val doc = model.document!!; doc.content.insert(0, 0, "mine")
        f.p.external(uri, "external"); model.save()
        assertTrue(model.noticeIsError); assertFalse(doc.uncertain); assertSame(doc, model.document)
        assertTrue(doc.dirty); assertTrue(doc.content.canUndo()); assertEquals(0, f.p.writes)
        f.p.granted = false; model.save(); assertTrue(model.permissionNeeded); assertFalse(model.canSave)
        model.chooseTree(f.p.tree, 3); assertSame(doc, model.document); assertTrue(model.canSave)
        f.p.external(uri, ""); f.p.nodes[uri] = f.p.nodes.getValue(uri).copy(modified = null)
        f.p.writeFailure = true; model.save()
        assertTrue(doc.uncertain); assertFalse(model.canSave); assertEquals("mine", doc.content.toString())
        assertTrue(doc.dirty); assertTrue(doc.content.canUndo())
    }
    @Test fun treeChangeRequiresConfirmationAndCancelOrFailureNeverDropsDirtyBuffer() {
        val f = Fixture(); val uri = f.file(); val model = f.model(); model.open(uri)
        val doc = model.document!!; doc.content.insert(0, 0, "mine")
        model.chooseTree("other-tree", 3); assertNotNull(model.pendingTree); assertSame(doc, model.document)
        model.cancelTreeChange(); assertNull(model.pendingTree); assertTrue(doc.dirty)
        model.chooseTree(null); assertSame(doc, model.document)
        model.pickerFailed(); assertSame(doc, model.document)
        f.p.denyPersist = true; model.chooseTree("other-tree", 3, discard = true); assertSame(doc, model.document)
        f.p.denyPersist = false; model.chooseTree("other-tree", 3, discard = true)
        assertNull(model.document); assertEquals("other-tree", model.tree)
    }
    @Test fun threeOriginsRetainIndependentBuffersAndSaveOnlySelectedSource() {
        val f = Fixture(); val uri = f.file(); val shared = f.model(); shared.open(uri)
        val dir = java.nio.file.Files.createTempDirectory("myct-l1d-test").toFile()
        try {
            val local = LocalIdeModel(LocalWorkspace(dir), Dispatchers.Unconfined, Dispatchers.Unconfined)
            local.loadDirectory(); local.create("private.c", false); local.open("private.c")
            val owner = IdeModel(dir, local = local, shared = shared)
            local.document!!.content.insert(0, 0, "private"); shared.document!!.content.insert(0, 0, "shared")
            owner.selectStorage(IdeStorage.SHARED); assertSame(shared, owner.session)
            owner.session.save(); assertTrue(local.document!!.dirty); assertFalse(shared.document!!.dirty)
            owner.select(IdeEnvironment.REMOTE); assertSame(owner.remote, owner.session)
            owner.select(IdeEnvironment.LOCAL); owner.selectStorage(IdeStorage.PRIVATE)
            assertSame(local, owner.session); assertEquals("private", owner.session.document!!.content.toString())
            assertTrue(owner.hasDirtyBuffers); assertEquals(1, f.p.writes)
            assertEquals("shared", decodeCode(f.p.bytes.getValue(uri)))
        } finally { dir.deleteRecursively() }
    }
    @Test fun visibleRowsKeepTheirUriWhenAnotherDirectoryIsPublished() {
        val f = Fixture(); val uri = f.file(); val folder = f.w.create("Projeto", true)
        val model = f.model(); val old = model.rows(model.listing)
        model.loadDirectory(folder.uri)
        assertTrue(model.rows(model.listing).isEmpty())
        assertEquals(uri, old.single { it.entry.name == "teste.c" }.reference)
        assertEquals(folder.uri, old.single { it.entry.name == "Projeto" }.reference)
        model.loadDirectory("")
        assertEquals(old.map { it.reference }, model.rows(model.listing).map { it.reference })
    }
    @Test fun refreshedFileWithSameDisplayMetadataAndNewUriInvalidatesVisibleRows() {
        val f = Fixture(); val uri = f.file(); val model = f.model()
        val before = model.listing!!; val rows = model.rows(before); val revision = model.listingRevision
        f.p.nodes.remove(uri); f.p.parents.remove(uri); f.p.bytes.remove(uri)
        val replacement = f.p.create(f.p.tree, f.p.root.uri, "teste.c", false)
        model.loadDirectory()
        assertEquals(before, model.listing) // presentation metadata alone cannot identify this replacement
        assertNotSame(before, model.listing)
        assertNotEquals(revision, model.listingRevision)
        assertEquals(uri, rows.single().reference)
        assertEquals(replacement.uri, model.rows(model.listing).single().reference)
        model.open(replacement.uri); assertFalse(model.noticeIsError)
    }
    @Test fun duplicateDisplayNamesStillHaveDistinctEntryUris() {
        val f = Fixture(); val a = f.file(); val b = f.p.create(f.p.tree, f.p.root.uri, "teste.c", false)
        val model = f.model(); val listing = model.listing!!
        assertEquals(listOf(a, b.uri), listing.entries.map { model.entryPath(listing.path, it) })
    }
}
