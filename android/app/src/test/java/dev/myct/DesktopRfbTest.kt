package dev.myct

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.*
import java.util.concurrent.*
import java.util.zip.Deflater

class DesktopRfbTest {
    /** Synthetic RFB peer, not a viewer and never connected to the Fedora desktop. */
    private class Fixture(val minor: Int = 8, val auth: Int = 2, val result: Int = 0,
        val width: Int = 4, val height: Int = 3,
        val script: (DataInputStream, DataOutputStream) -> Unit = { _, _ -> }) : Closeable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        private val executor = Executors.newSingleThreadExecutor()
        val finished = executor.submit {
            server.accept().use { socket ->
                socket.soTimeout = 3000
                val input = DataInputStream(socket.getInputStream()); val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                out.writeBytes("RFB 003.00$minor\n"); out.flush()
                assertEquals("RFB 003.00$minor\n", ByteArray(12).also(input::readFully).toString(Charsets.US_ASCII))
                if (minor == 3) { out.writeInt(auth); out.flush() }
                else { out.write(byteArrayOf(1, auth.toByte())); out.flush() }
                if (auth != 2) return@use
                if (minor != 3) assertEquals(2, input.readUnsignedByte())
                out.write(ByteArray(16) { it.toByte() }); out.flush()
                val response = ByteArray(16).also(input::readFully)
                assertEquals("b866924125c8eebb9debc1db61c538e2", response.joinToString("") { "%02x".format(it.toInt() and 255) })
                out.writeInt(result); out.flush()
                if (result != 0) return@use
                assertEquals(1, input.readUnsignedByte()) // Shared session.
                out.writeShort(width); out.writeShort(height); out.write(ByteArray(16)); out.writeInt(0); out.flush()
                if (width.toLong() * height > 4_194_304) return@use
                val format = ByteArray(20).also(input::readFully)
                assertEquals(0, format[0].toInt()); assertEquals(32, format[4].toInt()); assertEquals(1, format[7].toInt())
                assertEquals(2, input.readUnsignedByte()); input.readUnsignedByte()
                val enc = List(input.readUnsignedShort()) { input.readInt() }
                assertEquals(listOf(6, 5, 1, 0, -223), enc)
                request(input, false, width, height)
                script(input, out)
            }
        }
        override fun close() { server.close(); try { finished.get(4, TimeUnit.SECONDS) } finally { executor.shutdownNow() } }
    }
    companion object {
        private fun request(input: DataInputStream, incremental: Boolean, width: Int, height: Int) {
            assertEquals(3, input.readUnsignedByte()); assertEquals(incremental, input.readBoolean())
            assertEquals(0, input.readUnsignedShort()); assertEquals(0, input.readUnsignedShort())
            assertEquals(width, input.readUnsignedShort()); assertEquals(height, input.readUnsignedShort())
        }
        private fun update(out: DataOutputStream, encoding: Int, x: Int = 0, y: Int = 0, w: Int = 4, h: Int = 3,
            payload: DataOutputStream.() -> Unit = {}) {
            out.writeByte(0); out.writeByte(0); out.writeShort(1)
            out.writeShort(x); out.writeShort(y); out.writeShort(w); out.writeShort(h); out.writeInt(encoding)
            out.payload(); out.flush()
        }
        private fun DataOutputStream.pixel(color: Int) {
            writeByte(color and 255); writeByte(color ushr 8 and 255); writeByte(color ushr 16 and 255); writeByte(0)
        }
    }
    private fun connected(fixture: Fixture, progress: (DesktopStep, Boolean) -> Unit = { _, _ -> }, run: (RfbClient) -> Unit) {
        val client = RfbClient().also { it.progress = progress }
        try { client.connect(fixture.port, "password".toCharArray()); run(client) }
        finally { client.close(); client.disposeDecoder(); fixture.close() }
    }
    @Test fun vncAuthKnownOpenSslVectorAndPasswordErasure() {
        val secret = "password".toCharArray()
        val bytes = VncAuth.response(secret, ByteArray(16) { it.toByte() })
        assertEquals("b866924125c8eebb9debc1db61c538e2", bytes.joinToString("") { "%02x".format(it.toInt() and 255) })
        assertTrue(secret.all { it == '\u0000' })
    }
    @Test fun negotiate33And37And38AndSharedSession() {
        listOf(3, 7, 8).forEach { minor -> connected(Fixture(minor)) { assertEquals(4, it.frame.width) } }
    }
    @Test fun rejectsNoAuthAndInvalidPasswordAndOversizedDesktop() {
        listOf(Fixture(auth = 1), Fixture(result = 1), Fixture(width = 4096, height = 4096)).forEach { fixture ->
            val secret = "password".toCharArray(); val client = RfbClient()
            try { assertThrows(RfbException::class.java) { client.connect(fixture.port, secret) }; assertTrue(secret.all { it == '\u0000' }) }
            finally { client.close(); client.disposeDecoder(); fixture.close() }
        }
    }
    @Test fun rawPartialUpdateAndOverlappingCopyRectPreserveOtherPixels() {
        connected(Fixture(script = { input, out ->
            update(out, 0) { repeat(12) { pixel(0x112233) } }; request(input, true, 4, 3)
            update(out, 0, x = 1, y = 1, w = 2, h = 1) { pixel(0xff0000); pixel(0x00ff00) }; request(input, true, 4, 3)
            update(out, 1, x = 1, y = 2, w = 2, h = 1) { writeShort(1); writeShort(1) }; request(input, true, 4, 3)
        })) { client ->
            repeat(3) { client.readUpdate {} }
            val p = client.frame.pixels
            assertEquals(0xff112233.toInt(), p[0]); assertEquals(0xffff0000.toInt(), p[5])
            assertEquals(0xff00ff00.toInt(), p[6]); assertEquals(p[5], p[9]); assertEquals(p[6], p[10])
        }
    }
    @Test fun hextileBackgroundAndColoredSubrectangles() {
        connected(Fixture(script = { input, out ->
            update(out, 5) { writeByte(2 + 8 + 16); pixel(0x123456); writeByte(1); pixel(0xff0000); writeByte(0x11); writeByte(0x10) }
            request(input, true, 4, 3)
        })) { client ->
            client.readUpdate {}
            assertEquals(0xff123456.toInt(), client.frame.pixels[0]); assertEquals(0xffff0000.toInt(), client.frame.pixels[5])
            assertEquals(0xffff0000.toInt(), client.frame.pixels[6]); assertEquals(0xff123456.toInt(), client.frame.pixels[7])
        }
    }
    @Test fun zlibDictionarySurvivesAcrossRectanglesAndSizeChangeRequestsFullFrame() {
        connected(Fixture(script = { input, out ->
            val deflater = Deflater()
            try {
                repeat(2) { index ->
                    val raw = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).apply { repeat(12) { pixel(if (index == 0) 0x123456 else 0xabcdef) } } }.toByteArray()
                    deflater.setInput(raw)
                    val compressed = ByteArray(256); val size = deflater.deflate(compressed, 0, compressed.size, Deflater.SYNC_FLUSH)
                    update(out, 6) { writeInt(size); write(compressed, 0, size) }; request(input, true, 4, 3)
                }
                update(out, -223, w = 2, h = 2); request(input, false, 2, 2)
            } finally { deflater.end() }
        })) { client ->
            client.readUpdate {}; assertEquals(0xff123456.toInt(), client.frame.pixels[0])
            client.readUpdate {}; assertEquals(0xffabcdef.toInt(), client.frame.pixels[0])
            client.readUpdate {}; assertEquals(2, client.frame.width); assertEquals(4, client.frame.pixels.size)
        }
    }
    @Test fun rejectsMalformedRectangleUnsupportedEncodingAndCompressionBomb() {
        listOf<(DataOutputStream) -> Unit>(
            { out -> update(out, 0, x = 3, w = 4) },
            { out -> update(out, 999) },
            { out -> update(out, 6) { writeInt(Int.MAX_VALUE) } },
            { out -> update(out, 5) { writeByte(2 + 8); pixel(0); writeByte(1); writeByte(0xff); writeByte(0xff) } }
        ).forEach { send -> connected(Fixture(script = { _, out -> send(out) })) { client ->
            assertThrows(RfbException::class.java) { client.readUpdate {} }
        } }
    }
    @Test fun pointerClampsAndKeysAreNetworkBigEndian() {
        connected(Fixture(script = { input, _ ->
            assertEquals(5, input.readUnsignedByte()); assertEquals(1, input.readUnsignedByte())
            assertEquals(0, input.readUnsignedShort()); assertEquals(2, input.readUnsignedShort())
            assertEquals(4, input.readUnsignedByte()); assertTrue(input.readBoolean()); input.readShort()
            assertEquals(0x010020ac, input.readInt())
            assertEquals(4, input.readUnsignedByte()); assertFalse(input.readBoolean()); input.readShort()
            assertEquals(0x010020ac, input.readInt())
        })) { client -> client.pointer(-2, 99, 1); client.key(0x010020ac, true); client.key(0x010020ac, false) }
    }
    @Test fun closeUnblocksIdleReaderAndAllowsANewConnection() {
        val reading = CountDownLatch(1); val release = CountDownLatch(1)
        val fixture = Fixture(script = { _, _ -> release.await(3, TimeUnit.SECONDS) })
        val client = RfbClient(); val executor = Executors.newSingleThreadExecutor()
        try {
            client.connect(fixture.port, "password".toCharArray())
            val reader = executor.submit { reading.countDown(); assertThrows(IOException::class.java) { client.readUpdate {} } }
            assertTrue(reading.await(1, TimeUnit.SECONDS)); client.close(); reader.get(1, TimeUnit.SECONDS)
            connected(Fixture()) { assertEquals(4, it.frame.width) }
        } finally { release.countDown(); client.close(); client.disposeDecoder(); fixture.close(); executor.shutdownNow() }
    }
    @Test fun framebufferRejectsBoundsAndCopiesOverlappingRowsInOrder() {
        val frame = RfbFramebuffer(2, 3)
        frame.row(0, 0, intArrayOf(1, 2), 2); frame.row(0, 1, intArrayOf(3, 4), 2)
        frame.copy(0, 1, 2, 2, 0, 0)
        assertArrayEquals(intArrayOf(1, 2, 1, 2, 3, 4), frame.pixels)
        assertThrows(RfbException::class.java) { frame.fill(0, 2, 1, 2, 0) }
    }
    @Test fun negotiationAndFirstUpdateReportRealCompletedStepsButNotRendering() {
        val trace = DesktopTrace()
        connected(Fixture(script = { input, out ->
            update(out, 0) { repeat(12) { pixel(0) } }; request(input, true, 4, 3)
        }), trace::step) { client ->
            assertEquals(DesktopStep.FRAMEBUFFER, trace.state.lastCompleted)
            assertFalse(DesktopStep.UPDATE in trace.state.completed)
            client.readUpdate {}
            listOf(DesktopStep.TCP, DesktopStep.VERSION, DesktopStep.SECURITY, DesktopStep.AUTH,
                DesktopStep.FRAMEBUFFER, DesktopStep.UPDATE, DesktopStep.DECODE, DesktopStep.FRAME_READY)
                .forEach { assertTrue(it in trace.state.completed) }
            assertFalse(DesktopStep.RENDER in trace.state.completed)
        }
    }

}
