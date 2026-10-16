package dev.myct

import kotlinx.coroutines.channels.Channel

/** Existing single-writer FIFO; bookkeeping never holds a lock across SSH/resize work. */
internal class TerminalActionQueue(private val diagnostics: TerminalDiagnostics) {
    private sealed class Action(val queuedAt: Long, val idle: Boolean, val depth: Int) {
        class Write(val bytes: ByteArray, at: Long, idle: Boolean, depth: Int) : Action(at, idle, depth)
        class Resize(val cols: Int, val rows: Int, val width: Int, val height: Int,
                     at: Long, idle: Boolean, depth: Int) : Action(at, idle, depth)
    }
    private val channel = Channel<Action>(64)
    private val lock = Any()
    private var pending = 0
    private var busy = false
    private inline fun enqueue(create: (Long, Boolean, Int) -> Action): Boolean {
        val action = synchronized(lock) {
            val next = create(System.nanoTime(), !busy && pending == 0, pending + 1)
            // Dequeue bookkeeping uses the same lock, even if trySend wakes the writer.
            if (channel.trySend(next).isFailure) return false
            pending++
            next
        }
        // Observe backlog even before a stalled writer can dequeue it; no queue lock here.
        diagnostics.admitted(action.queuedAt, action.depth)
        return true
    }
    fun write(bytes: ByteArray) = enqueue { at, idle, depth -> Action.Write(bytes, at, idle, depth) }
    fun resize(cols: Int, rows: Int, width: Int, height: Int) = enqueue { at, idle, depth ->
        Action.Resize(cols, rows, width, height, at, idle, depth)
    }
    fun close() { channel.close() }
    suspend fun run(write: (ByteArray) -> Unit, resize: (Int, Int, Int, Int) -> Unit) {
        for (action in channel) {
            synchronized(lock) { pending--; busy = true }
            val started = System.nanoTime()
            val stage = if (action is Action.Write) TerminalDiagnostics.Stage.QUEUE else TerminalDiagnostics.Stage.RESIZE_QUEUE
            diagnostics.record(stage, action.queuedAt, started - action.queuedAt)
            if (action is Action.Write && action.idle)
                diagnostics.record(TerminalDiagnostics.Stage.IDLE_QUEUE, action.queuedAt, started - action.queuedAt)
            try {
                when (action) {
                    is Action.Write -> write(action.bytes)
                    is Action.Resize -> resize(action.cols, action.rows, action.width, action.height)
                }
            } finally {
                diagnostics.record(if (action is Action.Write) TerminalDiagnostics.Stage.FLUSH else TerminalDiagnostics.Stage.RESIZE,
                    started, System.nanoTime() - started)
                synchronized(lock) { busy = false }
            }
        }
    }
}
