package dev.myct

import com.termux.terminal.TerminalSession
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.FutureTask

class TerminalOutputQueueTest {
    @Test fun readerAdvancesWithoutMainAndOnlyOneDeliveryIsPending() {
        val tasks = LinkedBlockingQueue<Runnable>(); val output = ByteArrayOutputStream()
        var ended = 0
        val queue = TerminalOutputQueue(TerminalDiagnostics(), { tasks.add(it) }, { b, n -> output.write(b, 0, n) },
            { ended++ }, { throw AssertionError(it) }, capacity = 16 * 1024)
        val bytes = (0 until 12000).map { (it % 251).toByte() }.toByteArray()
        for (offset in bytes.indices step 1000) {
            val input = bytes.copyOfRange(offset, offset + 1000)
            assertTrue(queue.put(input, input.size)); input.fill(0) // Reader reuses its buffer immediately.
        }
        queue.finish()
        assertEquals(1, tasks.size); assertEquals(0, output.size())
        while (tasks.isNotEmpty()) { assertEquals(1, tasks.size); tasks.remove().run() }
        assertEquals(1, ended)
        assertArrayEquals(bytes, output.toByteArray())
    }
    @Test fun fullRingBackpressuresOnlyReaderWrapsAndReleasesOnClose() {
        val tasks = LinkedBlockingQueue<Runnable>(); val output = ByteArrayOutputStream()
        val queue = TerminalOutputQueue(TerminalDiagnostics(), { tasks.add(it) }, { b, n -> output.write(b, 0, n) },
            {}, { throw AssertionError(it) }, capacity = 8)
        assertTrue(queue.put("abcdefgh".toByteArray(), 8))
        val attempted = CountDownLatch(1); val stored = CountDownLatch(1)
        val readResult = FutureTask { attempted.countDown(); queue.put("ijklmn".toByteArray(), 6).also { stored.countDown() } }
        val reader = Thread(readResult)
        reader.start(); assertTrue(attempted.await(2, TimeUnit.SECONDS))
        assertEquals(1L, stored.count) // No main delivery has freed space.
        tasks.remove().run()
        assertTrue(stored.await(2, TimeUnit.SECONDS)); assertTrue(readResult.get(2, TimeUnit.SECONDS)); reader.join(2000)
        while (tasks.isNotEmpty()) tasks.remove().run()
        assertEquals("abcdefghijklmn", output.toString("UTF-8"))
        assertTrue(queue.put("opqrstuv".toByteArray(), 8)) // Tail starts at 6, crossing ring end.
        tasks.remove().run()
        assertEquals("abcdefghijklmnopqrstuv", output.toString("UTF-8"))
        assertTrue(queue.put(ByteArray(8), 8))
        val blockedResult = FutureTask { queue.put(byteArrayOf(1), 1) }
        val blocked = Thread(blockedResult)
        blocked.start(); queue.close(); assertFalse(blockedResult.get(2, TimeUnit.SECONDS)); blocked.join(2000); assertFalse(blocked.isAlive)
        while (tasks.isNotEmpty()) tasks.remove().run() // Stale callbacks do nothing after disconnect.
        assertEquals("abcdefghijklmnopqrstuv", output.toString("UTF-8"))
    }
    @Test fun closeAndReconnectCannotDeliverOldBytesOrFinishNewSession() {
        val tasks = LinkedBlockingQueue<Runnable>(); val output = ByteArrayOutputStream(); var ended = 0
        fun newQueue() = TerminalOutputQueue(TerminalDiagnostics(), { tasks.add(it) }, { b, n -> output.write(b, 0, n) },
            { ended++ }, { throw AssertionError(it) })
        val old = newQueue(); old.put("old".toByteArray(), 3); old.finish(); old.close()
        val fresh = newQueue(); fresh.put("new".toByteArray(), 3); fresh.finish()
        while (tasks.isNotEmpty()) tasks.remove().run()
        assertEquals("new", output.toString("UTF-8")); assertEquals(1, ended)
    }
    @Test fun fragmentedUnicodeAnsiAndAlternateScreenAreParsedOnlyOnOwnerThread() {
        val owner = Thread.currentThread(); val tasks = LinkedBlockingQueue<Runnable>()
        val session = TerminalSession(object : TerminalSession.Transport {
            override fun send(data: ByteArray) = Unit
            override fun resize(columns: Int, rows: Int, widthPixels: Int, heightPixels: Int) = Unit
        }, SessionCallbacks())
        val queue = TerminalOutputQueue(TerminalDiagnostics(), { tasks.add(it) }, { b, n ->
            assertSame(owner, Thread.currentThread()); session.append(b, n)
        }, {}, { throw AssertionError(it) }, capacity = 64)
        val bytes = "\u001b[?1049h\u001b[2J\u001b[Há😀".toByteArray()
        for (b in bytes) { assertTrue(queue.put(byteArrayOf(b), 1)); tasks.remove().run() }
        assertTrue(session.emulator.isAlternateBufferActive)
        assertEquals("á😀", session.emulator.getSelectedText(0, 0, 2, 0))
    }
    @Test fun parserFailureIsReportedAndAbortsInsteadOfEscapingMainCallback() {
        val tasks = LinkedBlockingQueue<Runnable>(); var failures = 0
        val queue = TerminalOutputQueue(TerminalDiagnostics(), { tasks.add(it) }, { _, _ -> error("synthetic") },
            { fail() }, { failures++ })
        queue.put(byteArrayOf(1), 1); tasks.remove().run()
        assertEquals(1, failures); assertFalse(queue.put(byteArrayOf(2), 1))
    }
}
