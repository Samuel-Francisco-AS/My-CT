package dev.myct

import org.junit.Assert.*
import org.junit.Test
import java.io.*

class IdeDocumentsTest {
    @Test fun importsValidatesAndClosesRealStream() {
        val bytes = "// ação 😀\n".toByteArray()
        var closed = false
        assertArrayEquals(bytes, IdeDocuments.importBytes { object : ByteArrayInputStream(bytes) {
            override fun close() { closed = true; super.close() }
        } })
        assertTrue(closed)
    }
    @Test fun missingOrRevokedGrantAndReadErrorsAreTreatable() {
        assertThrows(EditRejected::class.java) { IdeDocuments.importBytes { null } }
        assertThrows(SecurityException::class.java) { IdeDocuments.importBytes { throw SecurityException() } }
        assertThrows(IOException::class.java) { IdeDocuments.importBytes { object : InputStream() { override fun read(): Int = throw IOException() } } }
    }
    @Test fun providerSizeAndMimeCannotBypassUtf8OrSizeLimit() {
        for (bytes in listOf(byteArrayOf(0), byteArrayOf(0xc3.toByte()), ByteArray(IDE_MAX_BYTES + 1) { 65 }))
            assertThrows(EditRejected::class.java) { IdeDocuments.importBytes { ByteArrayInputStream(bytes) } }
    }
    @Test fun exportsClosesDestinationAndVerifiesActualBytes() {
        val bytes = "int ação;\r\n".toByteArray(); val output = ByteArrayOutputStream()
        var closed = false
        val sink = object : FilterOutputStream(output) { override fun close() { closed = true; super.close() } }
        assertTrue(IdeDocuments.exportCopy(bytes, { sink }, { assertTrue(closed); ByteArrayInputStream(output.toByteArray()) }))
        assertArrayEquals(bytes, output.toByteArray())
    }
    @Test fun exportReportsUnavailableReadbackAsUncertainAndRejectsMismatch() {
        assertFalse(IdeDocuments.exportCopy("ok".toByteArray(), { ByteArrayOutputStream() }, { null }))
        assertFalse(IdeDocuments.exportCopy("ok".toByteArray(), { ByteArrayOutputStream() }, { throw SecurityException() }))
        assertThrows(IntegrityFailure::class.java) { IdeDocuments.exportCopy("ok".toByteArray(), { ByteArrayOutputStream() }, { ByteArrayInputStream("bad".toByteArray()) }) }
    }
    @Test fun exportHandlesMissingWriteGrantAndPartialAndCloseFailure() {
        assertThrows(EditRejected::class.java) { IdeDocuments.exportCopy(byteArrayOf(65), { null }, { null }) }
        assertThrows(SecurityException::class.java) { IdeDocuments.exportCopy(byteArrayOf(65), { throw SecurityException() }, { null }) }
        assertThrows(IOException::class.java) { IdeDocuments.exportCopy(byteArrayOf(65), { object : OutputStream() { override fun write(b: Int) { throw IOException() } } }, { null }) }
        assertThrows(IOException::class.java) { IdeDocuments.exportCopy(byteArrayOf(65), { object : ByteArrayOutputStream() { override fun close() { throw IOException() } } }, { null }) }
    }
}
