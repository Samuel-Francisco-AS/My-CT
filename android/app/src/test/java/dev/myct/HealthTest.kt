package dev.myct

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import com.jcraft.jsch.JSch
import kotlin.concurrent.thread

class HealthTest {
    private val json = """{"status":"ok","cpu_percent":12.5,"ram_total_bytes":8192,"ram_available_bytes":2048,"disk_total_bytes":999999999999,"disk_available_bytes":555555555555,"uptime_seconds":90061}"""
    @Test fun parsesRealContractAndUnits() {
        val sample = HealthSample.parse(json)
        assertEquals(6144, sample.ramUsed)
        assertEquals(444444444444, sample.diskUsed)
        assertEquals(12.5, sample.cpuPercent, 0.001)
        assertTrue(healthBytes(1024).endsWith("KiB"))
        assertEquals("1d 1h 1min 1s", healthUptime(sample.uptimeSeconds))
    }
    @Test fun rejectsIncompleteInvalidAndNonNumericMetrics() {
        listOf("{}", "[]", json + "garbage", json.replace("12.5", "101"),
            json.replace("8192", "-1"), json.replace("2048", "99999"),
            json.replace("90061", "1.5"), json.replace("90061", "\"90061\""),
            json.replace("\"ok\"", "\"error\""), json.replace("12.5", "null")).forEach {
            assertTrue(it, runCatching { HealthSample.parse(it) }.isFailure)
        }
    }
    @Test fun stateKeepsLastSampleExplicitlyStaleAfterDisconnect() {
        val ready = HealthState(HealthPhase.READY, HealthSample.parse(json), 123L)
        val disconnected = ready.attached(false)
        assertEquals(HealthPhase.NO_SSH, disconnected.phase)
        assertEquals(ready.sample, disconnected.sample)
        assertEquals(123L, disconnected.fetchedAt)
        assertTrue(disconnected.message.contains("Sem conexão SSH"))
        assertEquals(HealthPhase.IDLE, disconnected.attached(true).phase)
        assertTrue(HealthState(HealthPhase.ERROR).message.contains("Core indisponível"))
    }
    @Test fun forwardingIsReusedClosedAndCannotReopenOldSession() {
        var opens = 0
        var removes = 0
        val tunnel = HealthForwarding({ opens++; 32123 }, { assertEquals(32123, it); removes++ })
        assertEquals(32123, tunnel.port()); assertEquals(32123, tunnel.port())
        assertEquals(1, opens)
        tunnel.close(); tunnel.close()
        assertEquals(1, removes)
        assertTrue(runCatching { tunnel.port() }.isFailure)
        val reconnected = HealthForwarding({ 32124 }, {})
        assertEquals(32124, reconnected.port()); reconnected.close()
    }
    @Test fun failedForwardingCanRetryAndCloseBeforeOpenDoesNotAllocate() {
        var attempts = 0
        val tunnel = HealthForwarding({ attempts++; if (attempts == 1) error("forwarding denied"); 32123 }, {})
        assertTrue(runCatching { tunnel.port() }.isFailure)
        assertEquals(32123, tunnel.port()); tunnel.close()
        val unopened = HealthForwarding({ error("must not allocate") }, { error("must not remove") })
        unopened.close()
        assertTrue(runCatching { unopened.port() }.isFailure)
    }
    @Test fun realJschAllocatesOnlyLoopbackAndReleasesListener() {
        // Real JSch listener lifecycle, deliberately no SSH authentication or device claim.
        val session = JSch().getSession("test", "127.0.0.1", 22)
        var allocated = 0
        val tunnel = HealthForwarding({
            session.setPortForwardingL("127.0.0.1", 0, "127.0.0.1", 8765).also { allocated = it }
        }, { session.delPortForwardingL("127.0.0.1", it) })
        try {
            val port = tunnel.port()
            assertTrue(port > 0)
            assertEquals("$port:127.0.0.1:8765", session.portForwardingL.single())
            val tables = listOf("/proc/net/tcp", "/proc/net/tcp6").map { java.io.File(it) }.filter { it.canRead() }
            if (tables.isNotEmpty()) {
                val suffix = ":" + port.toString(16).uppercase().padStart(4, '0')
                val addresses = tables.flatMap { it.readLines().drop(1) }.map { it.trim().split(Regex("\\s+")) }
                    .filter { it.size > 3 && it[1].endsWith(suffix) && it[3] == "0A" }.map { it[1] }
                assertEquals(1, addresses.size)
                assertTrue("Listener must bind loopback: $addresses", addresses.single() in listOf(
                    "0100007F$suffix", "0000000000000000FFFF00000100007F$suffix"))
            }
            assertTrue(runCatching { ServerSocket(port, 1, java.net.InetAddress.getByName("127.0.0.1")).close() }.isFailure)
            tunnel.close()
            assertTrue(session.portForwardingL.isEmpty())
            ServerSocket(allocated, 1, java.net.InetAddress.getByName("127.0.0.1")).close()
        } finally { tunnel.close(); session.disconnect() }
    }
    private fun response(status: String, body: String): Result<HealthSample> = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
        val worker = thread {
            server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) { }
                socket.getOutputStream().write("HTTP/1.1 $status\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body".toByteArray())
            }
        }
        try { runCatching { fetchHealth(server.localPort) } } finally { worker.join(5000) }
    }
    @Test fun httpReadsJsonAndRejectsErrorsRedirectsAndOversizedBodies() {
        assertEquals(12.5, response("200 OK", json).getOrThrow().cpuPercent, 0.001)
        assertTrue(response("503 Service Unavailable", "{}").isFailure)
        assertTrue(response("302 Found", "{}").isFailure)
        assertTrue(response("200 OK", "x".repeat(8193)).isFailure)
    }
}
