package dev.myct

/** A real terminal, not ProcessBuilder pipes. Blocking operations belong on workers. */
internal interface PtyProcess : AutoCloseable {
    fun read(bytes: ByteArray): Int
    fun write(bytes: ByteArray, count: Int)
    fun resize(columns: Int, rows: Int, width: Int, height: Int)
    fun waitForExit(): Int
    fun stop()
}

internal object NativePty {
    init { System.loadLibrary("myct-pty") }
    external fun open(directory: String): Long
    external fun read(handle: Long, bytes: ByteArray): Int
    external fun write(handle: Long, bytes: ByteArray, count: Int)
    external fun resize(handle: Long, columns: Int, rows: Int, width: Int, height: Int)
    external fun waitForExit(handle: Long): Int
    external fun stop(handle: Long)
    external fun release(handle: Long)
}

internal class AndroidPty(directory: String) : PtyProcess {
    private val handle = NativePty.open(directory)
    override fun read(bytes: ByteArray) = NativePty.read(handle, bytes)
    override fun write(bytes: ByteArray, count: Int) = NativePty.write(handle, bytes, count)
    override fun resize(columns: Int, rows: Int, width: Int, height: Int) = NativePty.resize(handle, columns, rows, width, height)
    override fun waitForExit() = NativePty.waitForExit(handle)
    override fun stop() = NativePty.stop(handle)
    override fun close() = NativePty.release(handle)
}
