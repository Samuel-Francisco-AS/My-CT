package dev.myct

/** Two per-connection workers. Session/emulator ownership stays on main; no subprocess/JNI. */
internal class TerminalIo(
    private val outgoing: TerminalActionQueue,
    private val incoming: TerminalOutputQueue,
    private val read: (ByteArray) -> Int,
    write: (ByteArray, Int) -> Unit,
    resize: (Int, Int, Int, Int) -> Unit,
    private val failed: (Exception) -> Unit
) {
    @Volatile private var stopped = false
    private val writer = worker("myct-terminal-writer") { outgoing.run(write, resize) }
    private val reader = worker("myct-terminal-reader") {
        val bytes = ByteArray(8192)
        try {
            while (!stopped) {
                val count = read(bytes)
                if (count < 0) { incoming.finish(); break }
                if (count > 0 && !incoming.put(bytes, count)) break
            }
        } catch (error: Exception) {
            // Preserve bytes already received before reporting a network/read failure to main.
            if (!stopped) incoming.finish(error)
        }
    }
    private fun worker(name: String, block: () -> Unit) = Thread({
        try { block() }
        catch (error: Exception) { if (!stopped) failed(error) }
    }, name).apply { isDaemon = true }
    fun start() { writer.start(); reader.start() }
    fun close() {
        stopped = true
        outgoing.close(); incoming.close()
        writer.interrupt(); reader.interrupt()
    }
    /** Call only after closing the underlying SSH connection, and never on main. */
    fun awaitStopped(): Boolean {
        writer.join(1000); reader.join(1000)
        return !writer.isAlive && !reader.isAlive
    }
}
