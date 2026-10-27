package dev.myct

import kotlinx.coroutines.channels.Channel
import java.util.ArrayDeque

internal enum class DesktopInteraction(val label: String) { DIRECT("Toque direto"), TRACKPAD("Trackpad") }
internal data class DesktopTransform(val zoom: Float = 1f, val centerX: Float = .5f, val centerY: Float = .5f)

/** FIFO with adjacent motion coalescing only. Button edges, clicks and key chords never coalesce. */
internal class DesktopInputBuffer(private val capacity: Int = 128) {
    private sealed interface Input {
        fun apply(peer: DesktopPeer)
        data class Motion(val x: Int, val y: Int, val mask: Int) : Input {
            override fun apply(peer: DesktopPeer) = peer.pointer(x, y, mask)
        }
        class Action(val block: (DesktopPeer) -> Unit) : Input {
            override fun apply(peer: DesktopPeer) = block(peer)
        }
    }
    private val lock = Any()
    private val pending = ArrayDeque<Input>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var closed = false
    var coalesced = 0L; private set
    var peak = 0; private set
    fun action(block: (DesktopPeer) -> Unit): Boolean = offer(Input.Action(block))
    fun motion(x: Int, y: Int, mask: Int): Boolean = offer(Input.Motion(x, y, mask))
    private fun offer(input: Input): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        val last = pending.peekLast()
        if (input is Input.Motion && last is Input.Motion && input.mask == last.mask) {
            pending.removeLast(); coalesced++
        } else if (pending.size >= capacity) return@synchronized false
        pending.addLast(input); peak = maxOf(peak, pending.size); wake.trySend(Unit); true
    }
    suspend fun consume(peer: DesktopPeer) {
        for (signal in wake) while (true) {
            val input = synchronized(lock) { pending.pollFirst() } ?: break
            input.apply(peer)
        }
    }
    fun stats(): Pair<Int, Long> = synchronized(lock) { peak to coalesced }
    fun close() = synchronized(lock) { closed = true; pending.clear(); wake.close() }
}

/** Unicode-safe replacement of the known typed tail, never a read of remote application text. */
internal data class DesktopTextDelta(val backspaces: Int, val suffix: String) {
    companion object {
        fun between(previous: String, next: String): DesktopTextDelta {
            val a = previous.codePoints().toArray(); val b = next.codePoints().toArray()
            var common = 0
            while (common < minOf(a.size, b.size) && a[common] == b[common]) common++
            return DesktopTextDelta(a.size - common, String(b, common, b.size - common))
        }
    }
}
