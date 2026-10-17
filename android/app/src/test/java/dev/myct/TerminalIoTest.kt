package dev.myct

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class TerminalIoTest {
    @Test fun dedicatedWorkersDeliverEofAfterAllOutputAndStopForReconnect() {
        val owner = Thread.currentThread()
        // Two distinct engines: old callbacks cannot consume a new connection's buffers.
        repeat(2) { generation ->
            val tasks = LinkedBlockingQueue<Runnable>(); val metrics = TerminalDiagnostics()
            val outgoing = TerminalActionQueue(metrics)
            val source = ("á😀\r\n".repeat(3000) + generation).toByteArray()
            val input = ByteArrayInputStream(source); val received = ByteArrayOutputStream()
            val sent = ByteArrayOutputStream(); val written = CountDownLatch(1)
            val failure = AtomicReference<Exception?>(); var finished = 0
            val incoming = TerminalOutputQueue(metrics, { tasks.add(it) }, { b, n ->
                assertSame(owner, Thread.currentThread()); received.write(b, 0, n)
            }, { finished++ }, { failure.set(it) })
            val io = TerminalIo(outgoing, incoming, { b ->
                assertEquals("myct-terminal-reader", Thread.currentThread().name); input.read(b)
            }, { b, n ->
                assertEquals("myct-terminal-writer", Thread.currentThread().name); sent.write(b, 0, n); written.countDown()
            }, { _, _, _, _ -> fail() }, { failure.set(it) })
            outgoing.write("x \u007f\r\u0003\u001b".toByteArray())
            io.start()
            try {
                assertTrue(written.await(2, TimeUnit.SECONDS))
                while (finished == 0) checkNotNull(tasks.poll(2, TimeUnit.SECONDS)).run()
                assertArrayEquals(source, received.toByteArray())
                assertArrayEquals("x \u007f\r\u0003\u001b".toByteArray(), sent.toByteArray())
                assertEquals(1, finished); assertNull(failure.get())
            } finally { io.close(); input.close(); assertTrue(io.awaitStopped()) }
            while (tasks.isNotEmpty()) tasks.remove().run()
            assertEquals(1, finished)
        }
    }
    @Test fun readFailureDrainsReceivedTailBeforeReportingFailure() {
        val tasks = LinkedBlockingQueue<Runnable>(); val metrics = TerminalDiagnostics()
        val outgoing = TerminalActionQueue(metrics); val received = ByteArrayOutputStream()
        val threw = CountDownLatch(1); val failure = AtomicReference<Exception?>()
        val error = IOException("synthetic"); var reads = 0
        val incoming = TerminalOutputQueue(metrics, { tasks.add(it) }, { b, n -> received.write(b, 0, n) },
            { fail("Expected read error") }, { failure.set(it) })
        val io = TerminalIo(outgoing, incoming, { b ->
            if (reads++ == 0) { "tail".toByteArray().copyInto(b); 4 }
            else { threw.countDown(); throw error }
        }, { _, _ -> fail() }, { _, _, _, _ -> fail() }, { failure.set(it) })
        io.start()
        try {
            assertTrue(threw.await(2, TimeUnit.SECONDS))
            assertNull(failure.get()); assertEquals(0, received.size())
            // A finish(error) racing this turn may schedule a final empty delivery.
            while (failure.get() == null) checkNotNull(tasks.poll(2, TimeUnit.SECONDS)).run()
            assertSame(error, failure.get()); assertEquals("tail", received.toString("UTF-8"))
        } finally { io.close(); assertTrue(io.awaitStopped()) }
    }

    @Test fun writeFailureSignalsOwnerAndCloseUnblocksBothWorkers() {
        val failed = CountDownLatch(1); val reading = CountDownLatch(1)
        val metrics = TerminalDiagnostics(); val queue = TerminalActionQueue(metrics)
        val output = TerminalOutputQueue(metrics, { fail("No output expected") }, { _, _ -> fail() }, {}, { fail() })
        val io = TerminalIo(queue, output, {
            reading.countDown(); CountDownLatch(1).await(); -1
        }, { _, _ -> throw IOException("synthetic") }, { _, _, _, _ -> fail() }, { failed.countDown() })
        io.start()
        try {
            assertTrue(reading.await(2, TimeUnit.SECONDS))
            assertTrue(queue.write(byteArrayOf(3)))
            assertTrue(failed.await(2, TimeUnit.SECONDS))
        } finally { io.close(); assertTrue(io.awaitStopped()) }
    }
}
