package dev.myct

import java.util.ArrayDeque

/** One dedicated owner writes transport bytes and applies only the latest pending PTY size. */
internal class TerminalActionQueue(
    private val diagnostics: TerminalDiagnostics,
    private val quietNanos: Long = 120_000_000,
    private val maxResizeNanos: Long = 400_000_000,
    private val maxBytes: Int = 128 * 1024,
    private val maxWrites: Int = 4096
) {
    private data class Write(val bytes: ByteArray, val at: Long, val idle: Boolean, var offset: Int = 0)
    private data class Size(val cols: Int, val rows: Int, val width: Int, val height: Int)
    private val lock = Object()
    private val writes = ArrayDeque<Write>()
    private var bytesPending = 0
    private var closed = false
    private var busy = false
    private var pendingSize: Size? = null
    private var inFlightSize: Size? = null
    private var appliedSize: Size? = Size(80, 24, 0, 0) // Initial PTY in TerminalModel.connect.
    private var resizeFirstAt = 0L
    private var resizeLastAt = 0L

    /** Takes ownership of bytes. Nonblocking main admission; failure closes rather than loses input. */
    fun write(bytes: ByteArray): Boolean {
        val at = System.nanoTime()
        val depth: Int
        synchronized(lock) {
            if (closed) return false
            if (bytes.isEmpty()) return true
            if (bytes.size > maxBytes - bytesPending || writes.size >= maxWrites) return false
            writes.addLast(Write(bytes, at, !busy && writes.isEmpty()))
            bytesPending += bytes.size
            depth = writes.size
            lock.notifyAll() // Wakes even when writer is waiting for a resize deadline.
        }
        diagnostics.admitted(at, depth)
        return true
    }
    fun resize(cols: Int, rows: Int, width: Int, height: Int): Boolean = synchronized(lock) {
        if (closed) return false
        val size = Size(cols, rows, width, height)
        if (size == pendingSize) return true // Repeats never restart the stabilization deadline.
        if (size == (inFlightSize ?: appliedSize)) {
            pendingSize = null
        } else {
            val now = System.nanoTime()
            if (pendingSize == null) resizeFirstAt = now
            resizeLastAt = now
            pendingSize = size // Replace obsolete dimensions, never enqueue another action.
        }
        lock.notifyAll()
        true
    }
    fun close() = synchronized(lock) {
        closed = true
        writes.clear(); bytesPending = 0; pendingSize = null
        lock.notifyAll()
    }

    /** Blocking loop belongs only to the dedicated writer, never main or a shared IO pool. */
    fun run(write: (ByteArray, Int) -> Unit, resize: (Int, Int, Int, Int) -> Unit) {
        val buffer = ByteArray(4096)
        while (true) {
            var count = 0
            var queuedAt = 0L
            var idle = false
            var size: Size? = null
            synchronized(lock) {
                while (!closed) {
                    if (writes.isNotEmpty()) {
                        queuedAt = writes.first.at
                        idle = writes.first.idle
                        // Drain only bytes already available; no typing timer or wait for more.
                        while (writes.isNotEmpty() && count < buffer.size) {
                            val next = writes.first
                            val length = minOf(buffer.size - count, next.bytes.size - next.offset)
                            next.bytes.copyInto(buffer, count, next.offset, next.offset + length)
                            next.offset += length; count += length; bytesPending -= length
                            if (next.offset == next.bytes.size) writes.removeFirst()
                        }
                        break
                    }
                    val target = pendingSize
                    if (target != null) {
                        val deadline = minOf(resizeLastAt + quietNanos, resizeFirstAt + maxResizeNanos)
                        val remaining = deadline - System.nanoTime()
                        if (remaining <= 0) {
                            size = target; queuedAt = resizeFirstAt
                            inFlightSize = target; pendingSize = null
                            break
                        }
                        lock.wait(remaining / 1_000_000, (remaining % 1_000_000).toInt())
                    } else lock.wait()
                }
                if (closed) return
                busy = true
            }
            val started = System.nanoTime()
            diagnostics.record(if (size == null) TerminalDiagnostics.Stage.QUEUE else TerminalDiagnostics.Stage.RESIZE_QUEUE,
                queuedAt, started - queuedAt)
            if (idle) diagnostics.record(TerminalDiagnostics.Stage.IDLE_QUEUE, queuedAt, started - queuedAt)
            try {
                val target = size
                if (target == null) write(buffer, count)
                else resize(target.cols, target.rows, target.width, target.height)
            } finally {
                diagnostics.record(if (size == null) TerminalDiagnostics.Stage.FLUSH else TerminalDiagnostics.Stage.RESIZE,
                    started, System.nanoTime() - started)
                synchronized(lock) {
                    busy = false
                    if (size != null) { appliedSize = size; inFlightSize = null }
                }
            }
        }
    }
}
