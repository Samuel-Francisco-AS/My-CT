package dev.myct

/** Bounded reader→main ring with one pending delivery, like Termux ByteQueue + Handler.
 * Only the reader may block (backpressure); append and its reusable buffer belong to main. */
internal class TerminalOutputQueue(
    private val diagnostics: TerminalDiagnostics,
    private val post: (Runnable) -> Unit,
    private val append: (ByteArray, Int) -> Unit,
    private val finished: () -> Unit,
    private val failed: (Exception) -> Unit,
    capacity: Int = 64 * 1024
) {
    private val lock = Object()
    private val ring = ByteArray(capacity)
    private val mainBuffer = ByteArray(minOf(8192, capacity))
    private var head = 0
    private var stored = 0
    private var dispatchAt = 0L
    private var scheduled = false
    private var eof = false
    private var aborted = false
    private var ended = false
    private var readFailure: Exception? = null
    private val delivery = Runnable {
        try { drain() }
        catch (error: Exception) { close(); failed(error) }
    }

    /** Copies before returning; the reader can immediately reuse its read buffer. */
    fun put(bytes: ByteArray, count: Int): Boolean {
        require(count in 1..minOf(bytes.size, ring.size))
        val started = System.nanoTime()
        val schedule: Boolean
        synchronized(lock) {
            while (!aborted && !eof && ring.size - stored < count) lock.wait()
            if (aborted || eof) return false
            val tail = (head + stored) % ring.size
            val first = minOf(count, ring.size - tail)
            bytes.copyInto(ring, tail, 0, first)
            bytes.copyInto(ring, 0, first, count)
            stored += count
            schedule = !scheduled
            if (schedule) dispatchAt = System.nanoTime()
            scheduled = true
        }
        diagnostics.record(TerminalDiagnostics.Stage.READER, started, System.nanoTime() - started)
        if (schedule) post(delivery)
        return true
    }
    fun finish(error: Exception? = null) {
        val schedule: Boolean
        synchronized(lock) {
            if (aborted || eof) return
            eof = true
            readFailure = error
            schedule = !scheduled
            if (schedule) dispatchAt = System.nanoTime()
            scheduled = true
            lock.notifyAll()
        }
        if (schedule) post(delivery)
    }
    fun close() = synchronized(lock) {
        aborted = true
        stored = 0; head = 0
        ring.fill(0)
        lock.notifyAll()
    }
    private fun drain() {
        val count: Int
        val queuedAt: Long
        synchronized(lock) {
            if (aborted || ended) return
            count = minOf(stored, mainBuffer.size)
            queuedAt = dispatchAt
            val first = minOf(count, ring.size - head)
            ring.copyInto(mainBuffer, 0, head, head + first)
            ring.copyInto(mainBuffer, first, 0, count - first)
            head = (head + count) % ring.size
            stored -= count
            lock.notifyAll()
        }
        if (count > 0) {
            diagnostics.record(TerminalDiagnostics.Stage.MAIN, queuedAt, System.nanoTime() - queuedAt)
            val started = System.nanoTime()
            append(mainBuffer, count) // Main only; never under lock, never a reader-thread callback.
            diagnostics.record(TerminalDiagnostics.Stage.APPEND, started, System.nanoTime() - started)
        }
        val again: Boolean
        val done: Boolean
        synchronized(lock) {
            if (aborted) return
            again = stored > 0
            done = !again && eof
            if (done) ended = true
            if (again) dispatchAt = System.nanoTime() else scheduled = false
        }
        // Yield to other main-loop work between bounded parser turns. Never posts one task/read.
        if (again) post(delivery)
        else if (done) {
            val error = readFailure
            if (error == null) finished() else failed(error)
        }
    }
}
