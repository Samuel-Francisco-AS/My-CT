package dev.myct

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class TerminalActionQueueTest {
    private fun waitFor(latch: CountDownLatch) { assertTrue(latch.await(2, TimeUnit.SECONDS)) }
    private fun worker(queue: TerminalActionQueue, write: (ByteArray, Int) -> Unit,
                       resize: (Int, Int, Int, Int) -> Unit): Pair<Thread, AtomicReference<Throwable?>> {
        val failure = AtomicReference<Throwable?>()
        val thread = Thread { try { queue.run(write, resize) } catch (e: Throwable) { failure.set(e) } }
        thread.start()
        return thread to failure
    }
    private fun stopped(pair: Pair<Thread, AtomicReference<Throwable?>>) {
        pair.first.join(2000); assertFalse(pair.first.isAlive); assertNull(pair.second.get())
    }
    @Test fun textPrecedesLatestResizeAndNoObsoleteResizeAccumulates() {
        val queue = TerminalActionQueue(TerminalDiagnostics(), quietNanos = 0)
        val events = mutableListOf<String>()
        for (rows in 4..49) assertTrue(queue.resize(55, rows, 440, rows * 16))
        repeat(100) { assertTrue(queue.resize(55, 49, 440, 784)) }
        val text = "ação e\u0301 😀 " + "\u007f".repeat(128) + " \r\u0003\u001b"
        text.toByteArray().forEach { assertTrue(queue.write(byteArrayOf(it))) }
        val sent = ByteArrayOutputStream()
        val thread = worker(queue, { b, n -> sent.write(b, 0, n); events.add("text") }, { c, r, w, h ->
            assertEquals(listOf(55, 49, 440, 784), listOf(c, r, w, h))
            events.add("resize"); queue.close()
        })
        stopped(thread)
        assertArrayEquals(text.toByteArray(), sent.toByteArray())
        assertEquals(listOf("text", "resize"), events)
    }
    @Test fun inputWakesWriterDuringResizeStabilizationWithoutWaitingForDeadline() {
        val queue = TerminalActionQueue(TerminalDiagnostics(), quietNanos = 5_000_000_000, maxResizeNanos = 5_000_000_000)
        val delivered = CountDownLatch(1)
        queue.resize(55, 49, 440, 784)
        val thread = worker(queue, { b, n -> assertArrayEquals(byteArrayOf(127, 13, 3, 27), b.copyOf(n)); delivered.countDown() },
            { _, _, _, _ -> fail("Resize must remain deferred") })
        try {
            assertTrue(queue.write(byteArrayOf(127, 13, 3, 27)))
            waitFor(delivered)
        } finally { queue.close() }
        stopped(thread)
    }
    @Test fun currentResizeSerializedButPendingResizesCannotDelayQueuedKeys() {
        val queue = TerminalActionQueue(TerminalDiagnostics(), quietNanos = 0)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val events = mutableListOf<String>()
        queue.resize(55, 20, 0, 0)
        val thread = worker(queue, { b, n -> assertArrayEquals(byteArrayOf(127, 13), b.copyOf(n)); events.add("keys") },
            { _, rows, _, _ ->
                events.add("resize$rows")
                if (rows == 20) { entered.countDown(); check(release.await(2, TimeUnit.SECONDS)) }
                else queue.close()
            })
        try {
            waitFor(entered)
            queue.resize(55, 30, 0, 0); queue.resize(55, 40, 0, 0); queue.resize(55, 49, 0, 0)
            assertTrue(queue.write(byteArrayOf(127, 13)))
        } finally { release.countDown() }
        stopped(thread)
        assertEquals(listOf("resize20", "keys", "resize49"), events)
    }
    @Test fun sameSizeAfterApplyIsSkippedButFontPixelChangeIsPreserved() {
        val queue = TerminalActionQueue(TerminalDiagnostics(), quietNanos = 0)
        val sizes = mutableListOf<List<Int>>()
        queue.resize(55, 49, 440, 784)
        val thread = worker(queue, { _, _ -> fail() }, { c, r, w, h ->
            sizes.add(listOf(c, r, w, h))
            if (sizes.size == 1) {
                repeat(50) { queue.resize(55, 49, 440, 784) }
                queue.resize(55, 49, 495, 882)
            } else queue.close()
        })
        stopped(thread)
        assertEquals(listOf(listOf(55, 49, 440, 784), listOf(55, 49, 495, 882)), sizes)
    }
    @Test fun byteAndActionBoundsRejectWholeInputAndCloseDiscardsOldSession() {
        val queue = TerminalActionQueue(TerminalDiagnostics(), maxBytes = 16, maxWrites = 2)
        assertTrue(queue.write(ByteArray(8) { 97 })); assertTrue(queue.write(ByteArray(8) { 98 }))
        assertFalse(queue.write(byteArrayOf(99)))
        queue.close()
        assertFalse(queue.write(byteArrayOf(99))); assertFalse(queue.resize(55, 49, 0, 0))
        queue.run({ _, _ -> fail("No replay after close") }, { _, _, _, _ -> fail() })
        val fresh = TerminalActionQueue(TerminalDiagnostics(), quietNanos = 0)
        assertTrue(fresh.resize(80, 24, 0, 0)) // Initial size already applied by channel.connect.
        assertTrue(fresh.write("new\r".toByteArray()))
        fresh.run({ b, n -> assertArrayEquals("new\r".toByteArray(), b.copyOf(n)); fresh.close() }, { _, _, _, _ -> fail() })
    }
    @Test fun pasteLargerThanWorkerBufferAndRapidBurstsKeepEveryByteInOrder() {
        val queue = TerminalActionQueue(TerminalDiagnostics())
        val text = ("é😀 x\u007f \r\u0003\u001b".repeat(5000)).toByteArray()
        assertTrue(text.size < 128 * 1024)
        assertTrue(queue.write(text))
        val sent = ByteArrayOutputStream(); val done = CountDownLatch(1)
        val thread = worker(queue, { b, n ->
            assertTrue(n <= 4096); sent.write(b, 0, n)
            if (sent.size() == text.size) { done.countDown(); queue.close() }
        }, { _, _, _, _ -> fail() })
        waitFor(done); stopped(thread)
        assertArrayEquals(text, sent.toByteArray())
    }
}
