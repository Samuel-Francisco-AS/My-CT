package dev.myct

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.attribute.FileTime

class LocalWorkspaceTest {
    @get:Rule val temp = TemporaryFolder()
    private fun workspace() = LocalWorkspace(temp.root.resolve("ide-local"))

    @Test fun createsWorkspaceProjectAndEveryRequestedTextExtensionAndNavigates() {
        val store = workspace()
        assertTrue(store.list().entries.isEmpty())
        assertEquals("ProjetoTeste", store.create("", "ProjetoTeste", true))
        for (ext in listOf("c", "cpp", "h", "py", "rs", "js", "kt", "sh")) store.create("ProjetoTeste", "teste.$ext", false)
        assertEquals(RemoteKind.DIRECTORY, store.list().entries.single().kind)
        assertEquals(8, store.list("ProjetoTeste").entries.size)
        assertEquals("", store.parent("ProjetoTeste"))
        assertEquals("ProjetoTeste", store.parent("ProjetoTeste/teste.c"))
    }
    @Test fun savedUtf8BomAndMixedEndingsSurviveNewWorkspaceInstance() {
        val store = workspace(); val path = store.create("", "ação.c", false)
        val bytes = "\uFEFF// ação 😀\r\nint main() {\n return 0;\r}\n".toByteArray()
        store.save(store.load(path), bytes)
        assertArrayEquals(bytes, workspace().load(path).bytes)
        assertTrue(store.list().entries.none { it.name.startsWith(".myct-") })
    }
    @Test fun renamesFileAndAncestorAndRefusesExistingTargetsWithoutReplacing() {
        val store = workspace(); store.create("", "projeto", true); store.create("projeto", "a.c", false)
        store.save(store.load("projeto/a.c"), "abc".toByteArray())
        assertEquals("projeto/b.c", store.rename("projeto/a.c", "b.c"))
        assertEquals("novo", store.rename("projeto", "novo"))
        assertEquals("abc", decodeCode(store.load("novo/b.c").bytes))
        store.create("novo", "existente.c", false)
        assertThrows(EditRejected::class.java) { store.rename("novo/b.c", "existente.c") }
        assertEquals("abc", decodeCode(store.load("novo/b.c").bytes))
        assertEquals(0, store.load("novo/existente.c").bytes.size)
    }
    @Test fun rejectsNamesAndEscapesIncludingWindowsAndAbsolutePaths() {
        val store = workspace()
        for (name in listOf("", ".", "..", "../x", "/tmp/x", "a/b", "a\\b", "a\u0000", "a\n", " ", "é".repeat(121), ".myct-save-evil"))
            assertThrows(name, EditRejected::class.java) { store.create("", name, false) }
        for (path in listOf("../x", "/tmp/x", "a/../x", "a//x", "a/./x", "a\\x"))
            assertThrows(path, EditRejected::class.java) { store.list(path) }
    }
    @Test fun rejectsSymlinksToFilesDirectoriesAndWorkspaceRoot() {
        val store = workspace(); store.list()
        val outside = temp.newFolder("outside").toPath(); Files.write(outside.resolve("file.c"), "safe".toByteArray())
        val root = temp.root.resolve("ide-local/projetos").toPath()
        Files.createSymbolicLink(root.resolve("link"), outside)
        assertEquals(RemoteKind.LINK, store.list().entries.single().kind)
        assertThrows(EditRejected::class.java) { store.load("link/file.c") }
        assertThrows(EditRejected::class.java) { store.create("link", "evil.c", false) }
        val other = temp.newFolder("other"); Files.createSymbolicLink(other.toPath().resolve("projetos"), outside)
        assertThrows(EditRejected::class.java) { LocalWorkspace(other).list() }
        val linkedBase = temp.root.toPath().resolve("base-link")
        Files.createSymbolicLink(linkedBase, outside)
        assertThrows(EditRejected::class.java) { LocalWorkspace(linkedBase.toFile()).list() }
        assertFalse(Files.exists(outside.resolve("projetos")))
        assertFalse(Files.exists(outside.resolve("evil.c")))
    }
    @Test fun sameSizeSameMtimeExternalChangeRefusesOverwrite() {
        val store = workspace(); val path = store.create("", "a.c", false)
        val initial = store.save(store.load(path), "abc".toByteArray())
        val physical = temp.root.resolve("ide-local/projetos/a.c").toPath()
        Files.write(physical, "xyz".toByteArray()); Files.setLastModifiedTime(physical, FileTime.fromMillis(initial.metadata.modified))
        assertThrows(EditConflict::class.java) { store.save(initial, "new".toByteArray()) }
        assertEquals("xyz", decodeCode(store.load(path).bytes))
    }
    @Test fun missingOriginalNeverCreatesReplacementAndInvalidBufferNeverTruncates() {
        val store = workspace(); val path = store.create("", "a.c", false)
        val initial = store.save(store.load(path), "keep".toByteArray())
        assertThrows(EditRejected::class.java) { store.save(initial, byteArrayOf(0)) }
        assertEquals("keep", decodeCode(store.load(path).bytes))
        Files.delete(temp.root.resolve("ide-local/projetos/a.c").toPath())
        assertThrows(java.nio.file.NoSuchFileException::class.java) { store.save(initial, "new".toByteArray()) }
        assertTrue(store.list().entries.isEmpty())
    }
    @Test fun importsNeverOverwriteAndProvenancePersistsAcrossRenamesAndReopening() {
        val store = workspace()
        val a = store.importCopy("code.c", "ação".toByteArray())
        val b = store.importCopy("code.c", "other".toByteArray())
        assertNotEquals(a, b); assertTrue(store.isImported(a)); assertTrue(store.isImported(b))
        store.rename("Importados", "Copias")
        val renamed = store.rename("Copias/code.c", "novo.c")
        assertTrue(workspace().isImported(renamed))
        assertEquals("ação", decodeCode(workspace().load(renamed).bytes))
    }
    @Test fun importRejectsBinaryMalformedOversizeAndTraversalWithoutPublishing() {
        val store = workspace()
        assertThrows(EditRejected::class.java) { store.importCopy("../evil", byteArrayOf(65)) }
        for (bytes in listOf(byteArrayOf(0), byteArrayOf(0xc3.toByte()), ByteArray(IDE_MAX_BYTES + 1)))
            assertThrows(EditRejected::class.java) { store.importCopy("bad.c", bytes) }
        assertTrue(store.list().entries.isEmpty())
    }
}
