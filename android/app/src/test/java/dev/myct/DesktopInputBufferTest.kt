package dev.myct

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.*

class DesktopInputBufferTest {
    @Test fun motionCoalescesOnlyAdjacentSameMaskAndKeepsKeyAndButtonBoundaries() = runBlocking {
        val q = DesktopInputBuffer(8); val events = CopyOnWriteArrayList<String>(); val end = CountDownLatch(1)
        val peer = object : DesktopPeer {
            override fun connect(port: Int, password: CharArray) {}
            override fun read(onFrame: (RfbFramebuffer) -> Unit) {}
            override fun pointer(x: Int, y: Int, mask: Int) { events.add("p:$x:$mask") }
            override fun key(sym: Int, down: Boolean) { events.add("k:$sym:$down") }
            override fun close() {}
            override fun dispose() {}
        }
        repeat(5000) { assertTrue(q.motion(it, 0, 0)) }
        q.action { it.pointer(4999, 0, 1) }; repeat(5000) { q.motion(it, 0, 1) }
        q.action { it.key(97, true); it.key(97, false) }
        q.motion(6000, 0, 1); q.action { it.pointer(6000, 0, 0); end.countDown() }
        val worker = launch(Dispatchers.Default) { q.consume(peer) }
        try {
            assertTrue(end.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("p:4999:0", "p:4999:1", "p:4999:1", "k:97:true", "k:97:false", "p:6000:1", "p:6000:0"), events)
            assertEquals(9998L, q.stats().second); assertTrue(q.stats().first <= 8)
        } finally { q.close(); worker.cancelAndJoin() }
    }
    @Test fun reliableEventsAreBoundedAndCloseDropsOldEvents() {
        val q = DesktopInputBuffer(2)
        assertTrue(q.action {}); assertTrue(q.action {}); assertFalse(q.action {})
        q.close(); assertFalse(q.action {}); assertFalse(q.motion(0, 0, 0))
    }
    @Test fun classificationLocksUntilFingersLift() {
        val scrolling = DesktopTwoFinger(100f, 100f, 5f)
        assertEquals(DesktopTwoFinger.Mode.SCROLL, scrolling.update(101f, 120f))
        assertEquals(DesktopTwoFinger.Mode.SCROLL, scrolling.update(200f, 120f))
        val pan = DesktopTwoFinger(100f, 100f, 5f, true, 100f)
        assertEquals(DesktopTwoFinger.Mode.PAN, pan.update(101f, 100f, 120f))
        val pinch = DesktopTwoFinger(100f, 100f, 5f, true, 100f)
        assertEquals(DesktopTwoFinger.Mode.ZOOM, pinch.update(130f, 110f, 110f))
    }
}
