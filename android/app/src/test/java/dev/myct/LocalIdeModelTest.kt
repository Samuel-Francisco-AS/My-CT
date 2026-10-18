package dev.myct

import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class LocalIdeModelTest {
    @get:Rule val temp = TemporaryFolder()
    private fun local() = LocalIdeModel(LocalWorkspace(temp.root), Dispatchers.Unconfined, Dispatchers.Unconfined)
    private fun newFile(model: LocalIdeModel) { model.loadDirectory(); model.create("teste.c", false); model.open("teste.c") }

    @Test fun offlineCreateEditExplicitSaveAndReopenThroughNewModel() {
        val model = local(); assertTrue(model.connected); newFile(model)
        val doc = model.document!!; doc.content.insert(0, 0, "// ação 😀\nint main() {}\n")
        assertTrue(doc.dirty); assertTrue(model.canSave)
        assertEquals(0, LocalWorkspace(temp.root).load("teste.c").bytes.size)
        model.save(); assertFalse(doc.dirty); assertFalse(model.noticeIsError)
        val reopened = local(); reopened.open("teste.c")
        assertEquals(doc.content.toString(), reopened.document!!.content.toString())
    }
    @Test fun bothDirtyBuffersUndoAndViewportSurviveSwitchingAndSavesStayInTheirOrigin() {
        val local = local(); newFile(local)
        val store = RemoteEditorTest.Store()
        val remote = RemoteIdeModel(Dispatchers.Unconfined, Dispatchers.Unconfined) { _, _ ->
            store.closed = false; store
        }
        val owner = IdeModel(temp.root, local, remote)
        remote.attach(SshConnection(), "fedora")
        remote.open("/work/code.py")
        val remoteDoc = remote.document!!; val localDoc = local.document!!
        remoteDoc.content.insert(0, 0, "# remote\n")
        localDoc.content.insert(0, 0, "// local\n")
        localDoc.viewport = IdeViewport(0, 85)
        val localVersion = localDoc.content.documentVersion
        repeat(3) { owner.select(IdeEnvironment.REMOTE); assertSame(remoteDoc, owner.session.document)
            owner.select(IdeEnvironment.LOCAL); assertSame(localDoc, owner.session.document) }
        assertTrue(owner.hasDirtyBuffers); assertEquals(localVersion, localDoc.content.documentVersion)
        assertEquals(85, localDoc.viewport!!.y); assertTrue(localDoc.content.canUndo()); assertTrue(remoteDoc.dirty)
        local.save(); assertFalse(localDoc.dirty); assertTrue(remoteDoc.dirty); assertFalse(store.replaced)
        assertEquals("// local\n", decodeCode(LocalWorkspace(temp.root).load("teste.c").bytes))
        remote.save(); assertFalse(remoteDoc.dirty); assertTrue(store.replaced)
        assertTrue(decodeCode(store.bytes.getValue("/work/code.py")).startsWith("# remote"))
        assertEquals("// local\n", decodeCode(LocalWorkspace(temp.root).load("teste.c").bytes))
        assertFalse(local.listing!!.entries.any { it.name == "code.py" })
    }
    @Test fun disconnectAndRemoteIdentityChangeDoNotAffectLocalDirtyBufferOrEnableWrongSave() {
        val local = local(); newFile(local); val owner = IdeModel(temp.root, local)
        val doc = local.document!!; doc.content.insert(0, 0, "local")
        owner.attach(null, "other-fedora"); owner.select(IdeEnvironment.REMOTE)
        assertFalse(owner.session.canSave); assertTrue(owner.hasDirtyBuffers)
        owner.select(IdeEnvironment.LOCAL); assertSame(doc, owner.session.document); assertTrue(local.canSave)
        assertEquals("local", doc.content.toString())
        assertEquals(IdeEnvironment.LOCAL, restoredIdeEnvironment(IdeEnvironment.REMOTE, false))
        assertEquals(IdeEnvironment.REMOTE, restoredIdeEnvironment(IdeEnvironment.REMOTE, true))
    }
    @Test fun conflictAndReadFailurePreserveContentDirtyAndUndo() {
        val model = local(); newFile(model); val doc = model.document!!
        doc.content.insert(0, 0, "mine")
        val store = LocalWorkspace(temp.root); store.save(store.load("teste.c"), "external".toByteArray())
        model.save(); assertTrue(model.noticeIsError); assertTrue(doc.dirty); assertSame(doc, model.document)
        model.open("missing.c"); assertTrue(model.noticeIsError); assertSame(doc, model.document)
        assertEquals("mine", doc.content.toString()); assertTrue(doc.content.canUndo())
        assertEquals("external", decodeCode(store.load("teste.c").bytes))
    }
    @Test fun renameOpenFileAndItsFolderKeepsDirtyBufferAndSaveTargetCorrect() {
        val model = local(); model.loadDirectory(); model.create("project", true); model.loadDirectory("project")
        model.create("a.c", false); model.open("project/a.c"); val doc = model.document!!
        doc.content.insert(0, 0, "mine")
        model.rename("project/a.c", "b.c"); assertEquals("project/b.c", doc.path)
        model.loadDirectory(); model.rename("project", "renamed")
        assertSame(doc, model.document); assertTrue(doc.dirty); assertEquals("renamed/b.c", doc.path)
        model.save(); assertFalse(doc.dirty)
        assertEquals("mine", decodeCode(LocalWorkspace(temp.root).load("renamed/b.c").bytes))
    }
    @Test fun cancelledAndDeniedSafPreserveDirtyBufferAndNeverCreateFile() {
        val model = local(); newFile(model); val doc = model.document!!; doc.content.insert(0, 0, "mine")
        model.importDocument(null); model.exportDocument(null)
        assertSame(doc, model.document); assertTrue(doc.dirty)
        model.importDocument({ throw SecurityException() }) { "x.c" }
        assertTrue(model.noticeIsError); assertSame(doc, model.document)
        assertEquals(listOf("teste.c"), LocalWorkspace(temp.root).list().entries.map { it.name })
        assertNull(model.prepareExport())
    }
    @Test fun unavailablePickerPreservesDocumentAndReportsFailure() {
        val model = local(); newFile(model); val doc = model.document!!
        doc.content.insert(0, 0, "mine"); model.pickerFailed()
        assertTrue(model.noticeIsError); assertTrue(doc.dirty); assertSame(doc, model.document)
    }
    @Test fun safImportIsExplicitCopyAndDoesNotReplaceDirtyDocument() {
        val model = local(); newFile(model); val doc = model.document!!; doc.content.insert(0, 0, "mine")
        model.importDocument({ ByteArrayInputStream("external".toByteArray()) }) { "from.c" }
        assertSame(doc, model.document); assertTrue(doc.dirty); assertTrue(model.browse)
        assertEquals("external", decodeCode(LocalWorkspace(temp.root).load("Importados/from.c").bytes))
        model.open("Importados/from.c"); assertTrue(model.imported)
    }
    @Test fun safExportCapturesSavedLocalSourceAndHandlesCancelRevokedGrantAndUnverifiedDestination() {
        val model = local(); newFile(model); model.document!!.content.insert(0, 0, "saved"); model.save()
        assertEquals("teste.c", model.prepareExport()); model.exportDocument(null)
        assertFalse(model.noticeIsError); assertTrue(model.message.contains("cancelada"))
        model.prepareExport(); model.exportDocument({ throw SecurityException() }); assertTrue(model.noticeIsError)
        assertTrue(model.message.contains("parcial")); assertTrue(model.message.contains("permissão"))
        val out = ByteArrayOutputStream(); model.prepareExport()
        model.document!!.content.insert(0, 0, "dirty ")
        model.exportDocument({ out }, { ByteArrayInputStream(out.toByteArray()) })
        assertEquals("saved", out.toString("UTF-8")); assertTrue(model.document!!.dirty)
        assertFalse(model.noticeIsError)
        model.document!!.content.undo(); model.save(); model.prepareExport()
        model.exportDocument({ ByteArrayOutputStream() }); assertTrue(model.noticeIsError)
        assertTrue(model.message.contains("sem confirmação"))
    }
}
