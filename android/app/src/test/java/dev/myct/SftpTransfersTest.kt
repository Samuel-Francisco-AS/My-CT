package dev.myct

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.concurrent.CancellationException

class SftpTransfersTest {
    private class Store : RemoteStore {
        val files = mutableMapOf<String, ByteArray>()
        val directories = mutableSetOf("/home/test")
        var modified = 7L
        var safe = true
        var race = false
        var committedThenFailed = false
        var link = false
        var writes = 0
        var closed = false
        override fun close() { closed = true }
        override fun home() = "/home/test"
        override fun canonical(path: String) = RemotePath.absolute(path)
        override fun list(path: String) = RemoteListing(path, emptyList(), false)
        override fun stat(path: String) = files[path]?.let { RemoteEntry(path.substringAfterLast('/'), if (link) RemoteKind.LINK else RemoteKind.FILE, it.size.toLong(), modified) }
        override fun read(path: String) = ByteArrayInputStream(files.getValue(path))
        override fun writeNewTemporary(path: String): OutputStream {
            writes++
            return object : ByteArrayOutputStream() {
                override fun close() { files[path] = toByteArray(); super.close() }
            }
        }
        override fun supportsSafePublish() = safe
        override fun mkdir(path: String) { check(directories.add(path)) }
        override fun publishNoReplace(temporary: String, target: String) {
            if (race) files[target] = byteArrayOf(99)
            if (files.containsKey(target)) throw NameConflict()
            files[target] = files.getValue(temporary)
            if (committedThenFailed) throw IOException("Disconnected before response")
        }
        override fun removeTemporary(path: String) { files.remove(path) }
        override fun removeTemporaryDirectory(path: String) { directories.remove(path) }
    }
    private fun <T : Throwable> expect(type: Class<T>, action: () -> Unit) {
        try { action(); fail("Expected ${type.simpleName}") } catch (e: Throwable) { if (!type.isInstance(e)) throw e }
    }
    private fun upload(store: Store, token: TransferControl = TransferControl(), size: Long? = 3,
                       progress: (Long) -> Unit = {}): CopyResult = SftpTransfers.upload(store, store.home(), "ação ?*.txt",
        ByteArrayInputStream(byteArrayOf(1, 2, 3)), size, token, progress)

    @Test fun namesPathsAndLiteralGlobEscaping() {
        for (bad in listOf("", ".", "..", "a/b", "a\u0000b", "a\nb", "x".repeat(256)))
            expect(IllegalArgumentException::class.java) { RemotePath.name(bad) }
        assertEquals("/ação/um arquivo 🐱.txt", RemotePath.child("/ação/", "um arquivo 🐱.txt"))
        assertEquals("/file", RemotePath.child("/", "file"))
        assertEquals("/", RemotePath.parent("/"))
        assertEquals("/ação", RemotePath.parent("/ação/file"))
        for (bad in listOf("relative", "/a/../b", "/a/./b", "/a\u0000"))
            expect(IllegalArgumentException::class.java) { RemotePath.absolute(bad) }
        assertEquals("/a\\*\\?/b\\\\c", RemotePath.literal("/a*?/b\\c"))
        // Verify that JSch's own unquote recovers the exact path, not a sibling glob.
        val util = Class.forName("com.jcraft.jsch.Util")
        val unquote = util.getDeclaredMethod("unquote", String::class.java).apply { isAccessible = true }
        val path = "/espaço */a?\\[ç].txt"
        assertEquals(path, unquote.invoke(null, RemotePath.literal(path)))
    }
    @Test fun uploadPublishesOnlyCompletedFileAndCleansOwnTemporary() {
        val store = Store()
        val result = upload(store)
        assertEquals(3L, result.bytes)
        assertEquals(64, result.sha256.length)
        assertArrayEquals(byteArrayOf(1, 2, 3), store.files.getValue("/home/test/ação ?*.txt"))
        assertEquals(1, store.files.size)
        assertEquals(setOf("/home/test"), store.directories)
    }
    @Test fun conflictPreventsAnyUploadAndPreservesExistingItem() {
        val store = Store()
        store.files["/home/test/ação ?*.txt"] = byteArrayOf(9)
        expect(NameConflict::class.java) { upload(store) }
        assertEquals(0, store.writes)
        assertArrayEquals(byteArrayOf(9), store.files.values.single())
    }
    @Test fun conflictAtCommitNeverOverwritesRacingTarget() {
        val store = Store().apply { race = true }
        expect(NameConflict::class.java) { upload(store) }
        assertArrayEquals(byteArrayOf(99), store.files.values.single())
        assertEquals(setOf("/home/test"), store.directories)
    }
    @Test fun noSafeExtensionBlocksUploadBeforeCreatingTemporary() {
        val store = Store().apply { safe = false }
        expect(SafePublishUnavailable::class.java) { upload(store) }
        assertEquals(0, store.writes)
        assertEquals(setOf("/home/test"), store.directories)
    }
    @Test fun cancelBeforeStartAndDuringCopyDoNotPublish() {
        val store = Store()
        val before = TransferControl().apply { cancel() }
        expect(CancellationException::class.java) { upload(store, before) }
        assertEquals(0, store.writes)
        val during = TransferControl()
        expect(CancellationException::class.java) { upload(store, during) { during.cancel() } }
        assertTrue(store.files.isEmpty())
        assertEquals(setOf("/home/test"), store.directories)
    }
    @Test fun sizeMismatchAndReadFailureDoNotPublish() {
        val store = Store()
        expect(IntegrityFailure::class.java) { upload(store, size = 4) }
        val broken = object : InputStream() { override fun read(): Int = throw IOException() }
        expect(IOException::class.java) { SftpTransfers.upload(store, store.home(), "broken", broken, null, TransferControl()) {} }
        assertTrue(store.files.isEmpty())
        assertEquals(setOf("/home/test"), store.directories)
    }
    @Test fun uncertainCommitNeverDeletesFinalFile() {
        val store = Store().apply { committedThenFailed = true }
        expect(IOException::class.java) { upload(store) }
        assertArrayEquals(byteArrayOf(1, 2, 3), store.files.values.single())
    }
    @Test fun downloadChecksSizeAndModificationAndRejectsLink() {
        val store = Store().apply { files["/home/test/file"] = byteArrayOf(1, 2, 3) }
        val entry = store.stat("/home/test/file")!!
        val output = ByteArrayOutputStream()
        val result = SftpTransfers.download(store, "/home/test/file", entry, output, TransferControl()) {}
        assertEquals(3L, result.bytes)
        assertArrayEquals(byteArrayOf(1, 2, 3), output.toByteArray())
        expect(IntegrityFailure::class.java) {
            SftpTransfers.download(store, "/home/test/file", entry, ByteArrayOutputStream(), TransferControl()) { store.modified++ }
        }
        store.link = true
        expect(IllegalArgumentException::class.java) {
            SftpTransfers.download(store, "/home/test/file", entry, ByteArrayOutputStream(), TransferControl()) {}
        }
    }
    @Test fun boundedStreamingAndCancellationCloseResources() {
        var left = 5 * 1024 * 1024
        var largest = 0
        val source = object : InputStream() {
            override fun read(): Int = error("Use streaming buffer")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                largest = maxOf(largest, len)
                if (left == 0) return -1
                val n = minOf(left, len)
                b.fill(0, off, off + n); left -= n; return n
            }
        }
        val result = SftpTransfers.copy(source, OutputStream.nullOutputStream(), TransferControl()) {}
        assertEquals(5L * 1024 * 1024, result.bytes)
        assertTrue(largest <= 65536)
        val token = TransferControl()
        val closed = mutableListOf<Int>()
        token.track(Closeable { closed.add(1) }) // transport first
        token.track(Closeable { closed.add(2) })
        token.cancel()
        assertEquals(listOf(1, 2), closed)
        expect(CancellationException::class.java) { token.track(Closeable { closed.add(3) }) }
        assertEquals(listOf(1, 2, 3), closed)
    }
    @Test fun transferStatesKeepCancellationBusyUntilWorkerFinishes() {
        for (phase in FilePhase.entries) {
            assertEquals(phase in setOf(FilePhase.LISTING, FilePhase.TRANSFERRING, FilePhase.CANCELLING), FileStatus(phase).busy)
        }
        val transferring = FileStatus(FilePhase.TRANSFERRING, "example", 12, 100)
        assertTrue(transferring.copy(phase = FilePhase.CANCELLING).busy)
        assertFalse(transferring.copy(phase = FilePhase.CANCELLED).busy)
        assertFalse(transferring.copy(phase = FilePhase.COMPLETE, bytes = 100).busy)
    }
}
