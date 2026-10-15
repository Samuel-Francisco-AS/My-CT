package dev.myct

import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class SftpCorrectionTest {
    @Test fun decimalSizesDoNotChangeExactCounters() {
        val examples = mapOf(0L to "0 bytes", 999L to "999 bytes", 1000L to "1,0 KB",
            1500L to "1,5 KB", 12_500_000L to "12,5 MB", 987_575_524L to "987,6 MB",
            2_500_000_000L to "2,5 GB", 1_000_000_000L to "1,0 GB")
        examples.forEach { (bytes, expected) -> assertEquals(expected, formatFileSize(bytes)) }
    }
    @Test fun optionalSafSizeDiffersFromMismatch() {
        assertTrue(completedDocumentSize(3) { 3 }.contains("conferido"))
        assertTrue(completedDocumentSize(3) { null }.contains("não permitiu"))
        assertTrue(completedDocumentSize(3) { throw SecurityException() }.contains("não permitiu"))
        try { completedDocumentSize(3) { 2 }; fail("Must reject truncated destination") } catch (_: IntegrityFailure) { }
    }
    @Test fun concurrentCancellationAbortsTransportExactlyOnce() {
        val control = TransferControl()
        val closes = AtomicInteger()
        val closed = CountDownLatch(1)
        control.trackTransport(Closeable { closes.incrementAndGet(); closed.countDown() })
        val workers = List(8) { thread { control.cancel() } }
        workers.forEach { it.join(2000); assertFalse(it.isAlive) }
        assertTrue(closed.await(1, TimeUnit.SECONDS))
        assertEquals(1, closes.get())
        assertTrue(control.cancelled)
    }
    @Test fun providerFlushFailureCannotProduceCopyResult() {
        val target = object : java.io.ByteArrayOutputStream() { override fun flush() { throw IOException() } }
        try { SftpTransfers.copy(byteArrayOf(1,2,3).inputStream(), target, TransferControl()) {}; fail("flush failure") }
        catch (_: IOException) { }
    }
}
