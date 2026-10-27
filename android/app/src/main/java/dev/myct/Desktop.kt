package dev.myct

import java.io.Closeable
import kotlin.math.min

/** Each lease belongs to Desktop only, never Health or OAuth. Reopen after disconnect is valid. */
internal class DesktopForwarding(private val open: () -> Int, private val remove: (Int) -> Unit) : Closeable {
    private var lease: DesktopTunnel? = null
    private var closed = false
    @Synchronized fun acquire(): DesktopTunnel {
        check(!closed && lease == null) { "Túnel Desktop indisponível ou já conectado" }
        val port = open()
        require(port in 1..65535)
        return DesktopTunnel(port) { current -> release(current) }.also { lease = it }
    }
    @Synchronized private fun release(current: DesktopTunnel) {
        if (lease !== current) return
        lease = null
        runCatching { remove(current.port) }
    }
    @Synchronized override fun close() {
        closed = true
        lease?.close()
    }
}
internal class DesktopTunnel(val port: Int, private val release: (DesktopTunnel) -> Unit) : Closeable {
    override fun close() = release(this)
}

internal enum class DesktopOperation { STATUS, START, STOP }
internal fun desktopCommand(operation: DesktopOperation): String = when (operation) {
    DesktopOperation.STATUS -> "/usr/bin/systemctl show --value --property=ActiveState vncserver@:1.service"
    DesktopOperation.START -> "/usr/bin/sudo -n -N -u root -- /usr/local/libexec/myct-desktop-control start"
    DesktopOperation.STOP -> "/usr/bin/sudo -n -N -u root -- /usr/local/libexec/myct-desktop-control stop"
}
internal enum class DesktopService { UNKNOWN, OFF, STARTING, AVAILABLE, STOPPING, ERROR }
internal fun parseDesktopService(result: SshExecResult): DesktopService {
    if (result.status != 0) return DesktopService.ERROR
    return when (result.output.trim()) {
        "active" -> DesktopService.AVAILABLE
        "inactive", "failed" -> DesktopService.OFF
        "activating", "reloading" -> DesktopService.STARTING
        "deactivating" -> DesktopService.STOPPING
        else -> DesktopService.ERROR
    }
}
internal enum class DesktopLink { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

/** Invert exactly the fit + zoom + pan used by the native renderer; reject letterbox taps. */
internal data class DesktopViewport(val viewWidth: Float, val viewHeight: Float, val width: Int,
    val height: Int, val zoom: Float = 1f, val panX: Float = 0f, val panY: Float = 0f) {
    val scale: Float get() = min(viewWidth / width, viewHeight / height) * zoom
    val left get() = (viewWidth - width * scale) / 2 + panX
    val top get() = (viewHeight - height * scale) / 2 + panY
    fun remote(x: Float, y: Float, clamp: Boolean = false): Pair<Int, Int>? {
        if (scale <= 0 || !scale.isFinite()) return null
        val rx = (x - left) / scale; val ry = (y - top) / scale
        if (!rx.isFinite() || !ry.isFinite()) return null
        if (!clamp && (rx < 0 || ry < 0 || rx >= width || ry >= height)) return null
        return Pair(rx.toInt().coerceIn(0, width - 1), ry.toInt().coerceIn(0, height - 1))
    }
}

internal object DesktopKeys {
    const val BACKSPACE = 0xff08; const val TAB = 0xff09; const val ENTER = 0xff0d
    const val ESC = 0xff1b; const val DELETE = 0xffff
    const val CTRL = 0xffe3; const val ALT = 0xffe9; const val SHIFT = 0xffe1
    fun unicode(code: Int): Int = when (code) {
        8 -> BACKSPACE; 9 -> TAB; 10, 13 -> ENTER; 27 -> ESC
        in 0x20..0xff -> code
        else -> 0x01000000 or code
    }
    fun text(value: String): List<Int> = value.codePoints().toArray().map(::unicode)
}

/** Pending IME composition stays local; only commit/finish emits text to Fedora. */
internal class DesktopComposition(private val text: (String) -> Unit, private val backspace: () -> Unit) {
    var composing: String = ""; private set
    fun compose(value: String) { composing = value.take(4096) }
    fun commit(value: String) { composing = ""; text(value.take(4096)) }
    fun finish() { val pending = composing; composing = ""; if (pending.isNotEmpty()) text(pending) }
    fun delete(before: Int) {
        val count = before.coerceIn(0, 256)
        if (composing.isNotEmpty()) {
            val points = composing.codePoints().toArray()
            composing = String(points, 0, (points.size - count).coerceAtLeast(0))
        }
        else repeat(count) { backspace() }
    }
    fun clear() { composing = "" }
}

/** Delay two-finger interpretation until movement distinguishes pinch from scrolling. */
internal class DesktopTwoFinger(private val initialSpan: Float, private val initialY: Float, private val slop: Float,
    private val pan: Boolean = false, private val initialX: Float = 0f) {
    enum class Mode { WAITING, ZOOM, SCROLL, PAN }
    var mode = Mode.WAITING; private set
    fun update(span: Float, y: Float, x: Float = initialX): Mode {
        // Decide once per two-finger contact: a scroll can never turn into a pinch mid-flight.
        if (mode != Mode.WAITING) return mode
        if (kotlin.math.abs(span - initialSpan) > 2 * slop) mode = Mode.ZOOM
        else if (pan && kotlin.math.hypot(x - initialX, y - initialY) > 2 * slop) mode = Mode.PAN
        else if (!pan && kotlin.math.abs(y - initialY) > 2 * slop) mode = Mode.SCROLL
        return mode
    }
}
