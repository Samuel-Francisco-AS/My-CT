package dev.myct

import org.junit.Assert.*
import org.junit.Test
import java.io.*

class RemoteEditorTest {
    internal class Store : RemoteEditorStore {
        val bytes = mutableMapOf("/work/code.py" to "print('ação')\r\n".toByteArray())
        val metadata = mutableMapOf("/work/code.py" to EditMetadata(bytes.getValue("/work/code.py").size.toLong(), 7, 0x1A0, 1000, 1000))
        var replaced = false
        var failWrite = false
        var failVerify = false
        var closed = false
        var beforeReplace: (() -> Unit)? = null
        var afterBackup: (() -> Unit)? = null
        override fun close() { closed = true }
        override fun home() = "/work"
        override fun canonical(path: String) = path
        override fun list(path: String) = RemoteListing(path, emptyList(), false)
        override fun stat(path: String) = bytes[path]?.let { RemoteEntry(path.substringAfterLast('/'), RemoteKind.FILE, it.size.toLong(), metadata.getValue(path).modified) }
        override fun editMetadata(path: String) = metadata.getValue(path).copy(size = bytes.getValue(path).size.toLong())
        override fun read(path: String): InputStream {
            if (closed || (replaced && failVerify && path == "/work/code.py")) throw IOException()
            return ByteArrayInputStream(bytes.getValue(path))
        }
        override fun writeNewTemporary(path: String): OutputStream = object : ByteArrayOutputStream() {
            override fun close() {
                if (failWrite || closed) throw IOException()
                bytes[path] = toByteArray(); metadata[path] = EditMetadata(size().toLong(), 8, 0x180, 1000, 1000)
            }
        }
        override fun prepareMode(path: String, original: EditMetadata) { metadata[path] = original.copy(size = bytes.getValue(path).size.toLong(), modified = 8) }
        override fun supportsSafePublish() = true
        override fun supportsAtomicEdit() = true
        override fun mkdir(path: String) = Unit
        override fun privateDirectory(path: String) = Unit
        override fun backup(source: String, backup: String) {
            bytes[backup] = bytes.getValue(source); metadata[backup] = metadata.getValue(source)
            afterBackup?.invoke()
        }
        override fun replace(temporary: String, target: String) {
            beforeReplace?.invoke()
            if (closed) throw IOException()
            bytes[target] = bytes.remove(temporary)!!; metadata[target] = metadata.remove(temporary)!!; replaced = true
        }
        override fun publishNoReplace(temporary: String, target: String) = error("unused")
        override fun removeTemporary(path: String) { if (closed) throw IOException(); bytes.remove(path); metadata.remove(path) }
        override fun removeTemporaryDirectory(path: String) = Unit
    }
    private val path = "/work/code.py"
    @Test fun utf8BomAndMixedLineEndingsRoundTripExactly() {
        val bytes = "\uFEFFaç😀\r\nb\nc\r".toByteArray()
        assertArrayEquals(bytes, encodeCode(decodeCode(bytes)))
    }
    @Test fun rejectsBinaryMalformedUtf8LargeAndPathologicalLines() {
        for (bytes in listOf(byteArrayOf(0), byteArrayOf(0xC3.toByte()), ByteArray(IDE_MAX_BYTES + 1) { 65 }, "a".repeat(16385).toByteArray())) {
            assertThrows(EditRejected::class.java) { decodeCode(bytes) }
        }
    }
    @Test fun explicitSaveVerifiesContentPreservesModeAndRetainsOriginalBackup() {
        val store = Store(); val control = TransferControl()
        val initial = RemoteEditor.load(store, path, control)
        val replacement = "print('novo 😀')\r\n".toByteArray()
        val saved = RemoteEditor.save(store, initial, replacement, control)
        assertArrayEquals(replacement, store.bytes[path])
        assertArrayEquals(initial.bytes, store.bytes[saved.backupPath])
        assertEquals(initial.metadata.mode, saved.snapshot.metadata.mode)
        assertEquals(initial.metadata.uid, saved.snapshot.metadata.uid)
        assertEquals(initial.metadata.gid, saved.snapshot.metadata.gid)
    }
    @Test fun sameSizeSameMtimeExternalEditIsConflictAndNeverReplaced() {
        val store = Store(); val token = TransferControl(); val initial = RemoteEditor.load(store, path, token)
        store.bytes[path] = initial.bytes.clone().also { it[0] = 65 }
        assertThrows(EditConflict::class.java) { RemoteEditor.save(store, initial, "new".toByteArray(), token) }
        assertFalse(store.replaced); assertEquals(65, store.bytes.getValue(path)[0].toInt())
    }
    @Test fun secondCheckCatchesChangeDuringPreparation() {
        val store = Store(); val token = TransferControl(); val initial = RemoteEditor.load(store, path, token)
        store.afterBackup = { store.bytes[path] = "external".toByteArray() }
        assertThrows(EditConflict::class.java) { RemoteEditor.save(store, initial, "new".toByteArray(), token) }
        assertFalse(store.replaced); assertEquals("external", store.bytes.getValue(path).toString(Charsets.UTF_8))
    }
    @Test fun deniedWriteKeepsOriginal() {
        val store = Store(); val token = TransferControl(); val initial = RemoteEditor.load(store, path, token)
        store.failWrite = true
        assertThrows(IOException::class.java) { RemoteEditor.save(store, initial, "new".toByteArray(), token) }
        assertArrayEquals(initial.bytes, store.bytes[path]); assertFalse(store.replaced)
    }
    @Test fun readOnlyModeRefusesEvenIfDirectoryWritable() {
        val store = Store(); store.metadata[path] = store.metadata.getValue(path).copy(mode = 0x124)
        val token = TransferControl(); val initial = RemoteEditor.load(store, path, token)
        assertThrows(EditRejected::class.java) { RemoteEditor.save(store, initial, "new".toByteArray(), token) }
        assertFalse(store.replaced)
    }
    @Test fun cancelledBeforeCommitKeepsOriginal() {
        val store = Store(); val token = TransferControl(); val initial = RemoteEditor.load(store, path, token)
        store.afterBackup = { token.cancel() }
        assertThrows(java.util.concurrent.CancellationException::class.java) { RemoteEditor.save(store, initial, "new".toByteArray(), token) }
        assertFalse(store.replaced); assertArrayEquals(initial.bytes, store.bytes[path])
    }
    @Test fun disconnectAtCommitReportsUncertainAndKeepsBackup() {
        val store = Store(); val token = TransferControl(); token.trackTransport(store)
        val initial = RemoteEditor.load(store, path, token)
        store.beforeReplace = { token.cancel() }
        val error = assertThrows(EditUncertain::class.java) { RemoteEditor.save(store, initial, "new".toByteArray(), token) }
        assertArrayEquals(initial.bytes, store.bytes[path]); assertArrayEquals(initial.bytes, store.bytes[error.backupPath])
    }
    @Test fun failedFinalVerificationNeverReportsSuccessAndKeepsBackup() {
        val store = Store(); val token = TransferControl(); val initial = RemoteEditor.load(store, path, token)
        store.failVerify = true
        val error = assertThrows(EditUncertain::class.java) { RemoteEditor.save(store, initial, "new".toByteArray(), token) }
        assertArrayEquals(initial.bytes, store.bytes[error.backupPath]); assertTrue(store.replaced)
    }
    @Test fun commonLanguagesHaveExplicitGrammarScopes() {
        for (name in listOf("a.c", "a.cpp", "a.rs", "a.py", "a.kt", "a.sh", "a.js")) assertNotNull(codeScope(name))
        assertNull(codeScope("a.bin"))
    }
}
