package dev.myct

import com.jcraft.jsch.LocalSftpSubsystem
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class IdeFedoraSftpTest {
    @Test(timeout = 20000) fun disposableFedoraFileThroughActualJschSftpRoundTripAndConflict() {
        assumeTrue(System.getenv("MYCT_TEST_LOCAL_SFTP") == "1")
        val directory = Files.createTempDirectory("myct-r4-sftp-")
        try {
            val path = directory.resolve("ação '[*?] código.py")
            val initial = "\uFEFFprint('ação 😀')\r\n".toByteArray()
            Files.write(path, initial)
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r-----"))
            LocalSftpSubsystem(directory.toString()).use { fixture ->
                val token = TransferControl(); val store = fixture.store
                assertTrue(store.supportsAtomicEdit())
                assertEquals(1, store.list(directory.toString()).entries.size)
                val snapshot = RemoteEditor.load(store, path.toString(), token)
                assertArrayEquals(initial, snapshot.bytes)
                val replacement = "\uFEFFprint('alterado 😀')\r\n".toByteArray()
                val saved = RemoteEditor.save(store, snapshot, replacement, token)
                assertArrayEquals(replacement, Files.readAllBytes(path))
                assertNull(saved.cleanupPath)
                assertEquals(1, store.list(directory.toString()).entries.size) // no .myct-edit-* left
                assertEquals(PosixFilePermissions.fromString("rw-r-----"), Files.getPosixFilePermissions(path))
                Files.write(path, "# editado externamente\n".toByteArray())
                assertThrows(EditConflict::class.java) { RemoteEditor.save(store, saved.snapshot, replacement, token) }
                assertEquals("# editado externamente\n", String(Files.readAllBytes(path), Charsets.UTF_8))
                val cancelled = TransferControl(); cancelled.cancel()
                assertThrows(java.util.concurrent.CancellationException::class.java) { RemoteEditor.load(store, path.toString(), cancelled) }
            }
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        }
    }
}
