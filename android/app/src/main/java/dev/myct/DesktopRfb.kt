package dev.myct

import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** Deliberately small RFB 3.3/3.7/3.8 profile. Only ever used over the pinned SSH tunnel. */
internal class RfbException(message: String, val kind: DesktopFailure? = null) : IOException(message)

internal class RfbFramebuffer(width: Int, height: Int) {
    var width = width; private set
    var height = height; private set
    var pixels = allocate(width, height); private set
    @Synchronized fun resize(w: Int, h: Int) { pixels = allocate(w, h); width = w; height = h }
    @Synchronized fun row(x: Int, y: Int, values: IntArray, count: Int) {
        bounds(x, y, count, 1); values.copyInto(pixels, y * width + x, 0, count)
    }
    @Synchronized fun fill(x: Int, y: Int, w: Int, h: Int, color: Int) {
        bounds(x, y, w, h)
        for (r in y until y + h) java.util.Arrays.fill(pixels, r * width + x, r * width + x + w, color)
    }
    @Synchronized fun copy(x: Int, y: Int, w: Int, h: Int, sx: Int, sy: Int) {
        bounds(x, y, w, h); bounds(sx, sy, w, h)
        val rows = if (y > sy) (h - 1 downTo 0) else (0 until h)
        for (r in rows) System.arraycopy(pixels, (sy + r) * width + sx, pixels, (y + r) * width + x, w)
    }
    fun bounds(x: Int, y: Int, w: Int, h: Int) {
        if (x < 0 || y < 0 || w <= 0 || h <= 0 || x.toLong() + w > width || y.toLong() + h > height)
            throw RfbException("Retângulo RFB inválido")
    }
    companion object {
        // 16 MiB framebuffer at most; reject hostile/accidental huge dimensions before allocation.
        fun allocate(w: Int, h: Int): IntArray {
            if (w !in 1..4096 || h !in 1..4096 || w.toLong() * h > 4_194_304)
                throw RfbException("Resolução RFB excede o limite de 4 megapixels")
            return IntArray(w * h) { 0xff000000.toInt() }
        }
    }
}

internal object VncAuth {
    /** VNC's historical DES challenge, using the platform implementation, never custom crypto. */
    fun response(password: CharArray, challenge: ByteArray): ByteArray {
        require(challenge.size == 16)
        val key = ByteArray(8)
        try {
            require(password.isNotEmpty() && password.size <= 8 && password.all { it.code in 1..255 }) {
                "VncAuth usa até 8 caracteres Latin-1; use a senha já configurada"
            }
            password.forEachIndexed { i, c -> key[i] = (Integer.reverse(c.code) ushr 24).toByte() }
            val cipher = Cipher.getInstance("DES/ECB/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
            return cipher.doFinal(challenge)
        } finally { key.fill(0); password.fill('\u0000') }
    }
}

internal class RfbClient(private val socket: Socket = Socket()) : Closeable {
    var progress: (DesktopStep, Boolean) -> Unit = { _, _ -> }
    private var firstUpdate = true
    private lateinit var input: DataInputStream
    private lateinit var output: DataOutputStream
    private val writeLock = Any()
    private val inflater = Inflater()
    private val scanline = ByteArray(4096 * 4)
    private val colors = IntArray(4096)
    private var compressed = ByteArray(65536)
    private val overflow = ByteArray(1)
    @Volatile private var closed = false
    lateinit var frame: RfbFramebuffer; private set
    var receivedBytes = 0L; private set
    var updates = 0L; private set

    fun connect(port: Int, password: CharArray) {
        try {
            require(port in 1..65535)
            check(!closed)
            progress(DesktopStep.TCP, false)
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress("127.0.0.1", port), 8000)
            progress(DesktopStep.TCP, true)
            socket.soTimeout = 12_000
            input = DataInputStream(BufferedInputStream(socket.getInputStream(), 65536))
            output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 8192))
            negotiate(password)
            // Keep a timeout until an actual first framebuffer; idle connected desktops can then wait.
        } catch (e: Exception) { close(); throw e }
        finally { password.fill('\u0000') }
    }

    private fun negotiate(password: CharArray) {
        progress(DesktopStep.VERSION, false)
        val banner = ByteArray(12).also { input.readFully(it) }.toString(Charsets.US_ASCII)
        if (!Regex("RFB 003\\.00[3-9]\\n").matches(banner)) throw RfbException("Versão RFB não suportada", DesktopFailure.VERSION)
        val minor = banner.substring(8, 11).toInt().let { if (it >= 8) 8 else if (it >= 7) 7 else 3 }
        output.writeBytes("RFB 003.00$minor\n"); output.flush()
        progress(DesktopStep.VERSION, true)
        progress(DesktopStep.SECURITY, false)
        if (minor == 3) {
            if (input.readInt() != 2) throw RfbException("Servidor deve exigir VncAuth", DesktopFailure.SECURITY)
        } else {
            val count = input.readUnsignedByte()
            if (count == 0) throw RfbException("Servidor recusou negociação RFB", DesktopFailure.SECURITY)
            val types = ByteArray(count).also { input.readFully(it) }
            if (types.none { it.toInt() == 2 }) throw RfbException("Servidor não oferece VncAuth", DesktopFailure.SECURITY)
            output.writeByte(2); output.flush()
        }
        progress(DesktopStep.SECURITY, true)
        progress(DesktopStep.AUTH, false)
        val challenge = ByteArray(16).also { input.readFully(it) }
        val response = VncAuth.response(password, challenge)
        try { output.write(response); output.flush() } finally { response.fill(0); challenge.fill(0) }
        if (input.readInt() != 0) throw RfbException("Autenticação VNC recusada; confira a senha", DesktopFailure.AUTH)
        progress(DesktopStep.AUTH, true)
        progress(DesktopStep.FRAMEBUFFER, false)
        output.writeByte(1); output.flush() // Shared: disconnect must leave Fedora apps running.
        val w = input.readUnsignedShort(); val h = input.readUnsignedShort()
        frame = RfbFramebuffer(w, h)
        input.skipFully(16) // Server pixel format. We explicitly request our own below.
        val nameSize = input.readInt()
        if (nameSize !in 0..65536) throw RfbException("Nome RFB inválido")
        input.skipFully(nameSize)
        output.writeByte(0); output.write(byteArrayOf(0, 0, 0))
        // 32 bpp / 24 depth / little endian / true color; byte order B,G,R,pad.
        output.write(byteArrayOf(32, 24, 0, 1)); repeat(3) { output.writeShort(255) }
        output.write(byteArrayOf(16, 8, 0, 0, 0, 0))
        output.writeByte(2); output.writeByte(0); output.writeShort(5)
        listOf(6, 5, 1, 0, -223).forEach(output::writeInt) // Zlib, Hextile, CopyRect, Raw, DesktopSize.
        output.flush()
        request(false)
        progress(DesktopStep.FRAMEBUFFER, true)
        progress(DesktopStep.UPDATE, false)
    }

    private fun request(incremental: Boolean) = synchronized(writeLock) {
        output.writeByte(3); output.writeBoolean(incremental)
        output.writeShort(0); output.writeShort(0); output.writeShort(frame.width); output.writeShort(frame.height)
        output.flush()
    }

    /** One reader worker; no main-thread decoding, no queued full frame snapshots. */
    fun readUpdate(onFrame: (RfbFramebuffer) -> Unit) {
        when (input.readUnsignedByte()) {
            0 -> {
                input.readUnsignedByte()
                val count = input.readUnsignedShort()
                if (count > 8192) throw RfbException("Atualização RFB excessiva")
                if (firstUpdate && count > 0) { progress(DesktopStep.UPDATE, true); progress(DesktopStep.DECODE, false) }
                var resized = false
                var painted = false
                repeat(count) {
                    checkOpen()
                    val x = input.readUnsignedShort(); val y = input.readUnsignedShort()
                    val w = input.readUnsignedShort(); val h = input.readUnsignedShort()
                    val encoding = input.readInt()
                    if (encoding == -223) {
                        if (x != 0 || y != 0) throw RfbException("Redimensionamento RFB inválido")
                        frame.resize(w, h); resized = true
                    } else {
                        frame.bounds(x, y, w, h)
                        painted = true
                        when (encoding) {
                            0 -> raw(input, x, y, w, h)
                            1 -> frame.copy(x, y, w, h, input.readUnsignedShort(), input.readUnsignedShort())
                            5 -> hextile(x, y, w, h)
                            6 -> zlib(x, y, w, h)
                            else -> throw RfbException("Codificação RFB não negociada: $encoding")
                        }
                    }
                }
                if (painted) {
                    if (firstUpdate) {
                        progress(DesktopStep.DECODE, true)
                        progress(DesktopStep.FRAME_READY, true)
                        firstUpdate = false; socket.soTimeout = 0
                    }
                    updates++; onFrame(frame)
                }
                // Pull-driven cap: bound decode/render work and network demand to ~30 updates/s.
                Thread.sleep(33)
                if (!closed) request(!resized)
            }
            2 -> Unit // Bell; deliberately no unsolicited sound.
            3 -> {
                input.skipFully(3)
                val count = input.readInt()
                if (count !in 0..1_048_576) throw RfbException("Clipboard RFB excessivo")
                input.skipFully(count) // Clipboard sharing is not enabled.
            }
            else -> throw RfbException("Mensagem RFB não suportada")
        }
    }

    private fun pixel(source: DataInputStream): Int {
        val b = source.readUnsignedByte(); val g = source.readUnsignedByte(); val r = source.readUnsignedByte()
        source.readUnsignedByte(); receivedBytes += 4
        return (0xff000000L or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()).toInt()
    }
    private fun raw(source: DataInputStream, x: Int, y: Int, w: Int, h: Int) {
        for (r in 0 until h) {
            checkOpen()
            source.readFully(scanline, 0, w * 4)
            decodeRow(x, y + r, w)
        }
    }
    private fun decodeRow(x: Int, y: Int, w: Int) {
        receivedBytes += w * 4
        for (c in 0 until w) {
            val p = c * 4
            colors[c] = (0xff000000L or ((scanline[p + 2].toLong() and 255) shl 16) or
                ((scanline[p + 1].toLong() and 255) shl 8) or (scanline[p].toLong() and 255)).toInt()
        }
        frame.row(x, y, colors, w)
    }
    private fun checkOpen() { if (closed) throw IOException("Desktop desconectado") }
    private fun hextile(x: Int, y: Int, w: Int, h: Int) {
        var background = 0xff000000.toInt(); var foreground = background
        for (ty in y until y + h step 16) for (tx in x until x + w step 16) {
            checkOpen()
            val tw = minOf(16, x + w - tx); val th = minOf(16, y + h - ty)
            val flags = input.readUnsignedByte()
            if (flags and 1 != 0) { raw(input, tx, ty, tw, th); continue }
            if (flags and 0xe0 != 0) throw RfbException("Hextile inválido")
            if (flags and 2 != 0) background = pixel(input)
            if (flags and 4 != 0) foreground = pixel(input)
            frame.fill(tx, ty, tw, th, background)
            if (flags and 8 != 0) repeat(input.readUnsignedByte()) {
                val color = if (flags and 16 != 0) pixel(input) else foreground
                val xy = input.readUnsignedByte(); val wh = input.readUnsignedByte()
                val sx = xy ushr 4; val sy = xy and 15; val sw = (wh ushr 4) + 1; val sh = (wh and 15) + 1
                if (sx + sw > tw || sy + sh > th) throw RfbException("Subretângulo Hextile inválido")
                frame.fill(tx + sx, ty + sy, sw, sh, color)
            }
        }
    }
    private fun zlib(x: Int, y: Int, w: Int, h: Int) {
        val length = input.readInt()
        if (length !in 1..(w * h * 4 + 65536)) throw RfbException("Bloco Zlib excessivo")
        if (compressed.size < length) compressed = ByteArray(length)
        input.readFully(compressed, 0, length)
        if (!inflater.needsInput()) throw RfbException("Fluxo Zlib inválido")
        inflater.setInput(compressed, 0, length)
        // Inflate one scanline at a time, keeping the dictionary across rectangles.
        for (row in 0 until h) {
            checkOpen()
            var offset = 0
            while (offset < w * 4) {
                val n = inflater.inflate(scanline, offset, w * 4 - offset)
                if (n <= 0) throw RfbException("Quadro Zlib incompleto")
                offset += n
            }
            decodeRow(x, y + row, w)
        }
        // Consume SYNC_FLUSH trailer, rejecting decompression beyond advertised dimensions.
        if (inflater.inflate(overflow) != 0 || !inflater.needsInput()) throw RfbException("Quadro Zlib excedente")
    }
    fun pointer(x: Int, y: Int, mask: Int) = synchronized(writeLock) {
        check(!closed)
        output.writeByte(5); output.writeByte(mask and 255)
        output.writeShort(x.coerceIn(0, frame.width - 1)); output.writeShort(y.coerceIn(0, frame.height - 1))
        output.flush()
    }
    fun key(keysym: Int, down: Boolean) = synchronized(writeLock) {
        check(!closed)
        output.writeByte(4); output.writeBoolean(down); output.writeShort(0); output.writeInt(keysym); output.flush()
    }
    override fun close() {
        closed = true
        runCatching { socket.close() } // Interrupt reads AND blocked writes, no waiting for writeLock.
    }
    /** Reader owns the inflater; call only after reader completion. */
    fun disposeDecoder() { inflater.end() }
}

private fun DataInputStream.skipFully(count: Int) {
    var remaining = count
    while (remaining > 0) {
        val n = skipBytes(remaining)
        if (n == 0) { readByte(); remaining-- } else remaining -= n
    }
}
