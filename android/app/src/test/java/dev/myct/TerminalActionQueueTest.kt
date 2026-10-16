package dev.myct

import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import android.view.KeyEvent
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TerminalActionQueueTest {
    @Test fun boundedFifoKeepsAllBytesAndResizeInPosition() = runBlocking {
        val metrics = TerminalDiagnostics().also { it.startSample() }
        val queue = TerminalActionQueue(metrics)
        val events = mutableListOf<Int>()
        repeat(32) { assertTrue(queue.write(byteArrayOf(it.toByte()))) }
        assertTrue(queue.resize(55, 49, 440, 784))
        repeat(31) { assertTrue(queue.write(byteArrayOf((it + 32).toByte()))) }
        assertFalse(queue.write(byteArrayOf(99))) // Same 64-action limit, no silent eviction.
        assertTrue(metrics.snapshot().contains("Fila máxima observada: 64"))
        assertTrue(metrics.snapshot().contains("Ações admitidas: 64"))
        assertTrue(metrics.snapshot().contains("Fila write: N=0")) // Observe before any dequeue.
        queue.close()
        assertFalse(queue.write(byteArrayOf(99)))
        queue.run(write = { events.add(it.single().toInt()) }, resize = { cols, rows, w, h ->
            assertEquals(listOf(55, 49, 440, 784), listOf(cols, rows, w, h)); events.add(-1)
        })
        assertEquals((0 until 32).toList() + listOf(-1) + (32 until 63).toList(), events)
    }

    @Test fun burstThroughSessionAndWriterPreservesSpacesUnicodeDeletesAndEnter() = runBlocking {
        val metrics = TerminalDiagnostics().also { it.startSample() }
        val queue = TerminalActionQueue(metrics)
        val sent = mutableListOf<Byte>()
        val session = TerminalSession(object : TerminalSession.Transport {
            override fun send(data: ByteArray) { assertTrue(queue.write(data)) }
            override fun resize(columns: Int, rows: Int, widthPixels: Int, heightPixels: Int) = Unit
        }, SessionCallbacks())
        val delete = KeyHandler.getCode(KeyEvent.KEYCODE_DEL, 0, false, false)
        val enter = KeyHandler.getCode(KeyEvent.KEYCODE_ENTER, 0, false, false)
        val phrase = "ação e\u0301 😀 "
        val expected = StringBuilder()
        val delivered = AtomicInteger()
        val writer = launch(Dispatchers.Default) {
            queue.run({ sent.addAll(it.toList()); delivered.incrementAndGet() }, { _, _, _, _ -> error("Unexpected resize") })
        }
        repeat(8) { burst ->
            // Each burst fits the bound even when the writer has not been scheduled yet.
            session.beginInputEvent()
            phrase.codePoints().forEach { session.writeCodePoint(false, it) }
            session.endInputEvent()
            repeat(16) { session.write(delete) }
            session.writeCodePoint(false, 32)
            session.write(enter)
            session.writeCodePoint(false, 3)
            expected.append(phrase).append("\u007f".repeat(16)).append(" \r\u0003")
            withTimeout(3000) { while (delivered.get() < (burst + 1) * 20) delay(1) }
            assertArrayEquals(expected.toString().toByteArray(), sent.toByteArray())
        }
        queue.close()
        withTimeout(3000) { writer.join() }
        assertTrue(metrics.snapshot().contains("SSH write+flush: N=160"))
    }

    @Test fun blockedResizeIsVisibleSeparatelyAndFollowingInputRemainsOrdered() = runBlocking {
        val metrics = TerminalDiagnostics().also { it.startSample() }
        val queue = TerminalActionQueue(metrics)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sent = mutableListOf<Byte>()
        assertTrue(queue.resize(55, 49, 0, 0))
        val writer = launch(Dispatchers.Default) {
            queue.run(write = { sent.addAll(it.toList()) }, resize = { _, _, _, _ ->
                entered.countDown(); check(release.await(2, TimeUnit.SECONDS))
            })
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            repeat(24) { assertTrue(queue.write(byteArrayOf(127))) }
            assertTrue(queue.write(byteArrayOf(13)))
        } finally { release.countDown(); queue.close() }
        withTimeout(3000) { writer.join() }
        assertArrayEquals(ByteArray(24) { 127 } + byteArrayOf(13), sent.toByteArray())
        val snapshot = metrics.snapshot()
        assertTrue(snapshot.contains("SSH resize: N=1"))
        assertTrue(snapshot.contains("Fila write: N=25"))
        assertTrue(snapshot.contains("Fila write sem ação anterior: N=0"))
        assertTrue(snapshot.contains("Fila máxima observada: 25"))
    }
}
