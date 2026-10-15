package dev.myct

import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.Locale

/** Exact Core contract. No shell commands, database or credentials. */
data class HealthSample(val cpuPercent: Double, val ramTotal: Long, val ramAvailable: Long,
                        val diskTotal: Long, val diskAvailable: Long, val uptimeSeconds: Long) {
    val ramUsed get() = ramTotal - ramAvailable
    val diskUsed get() = diskTotal - diskAvailable
    companion object {
        fun parse(body: String): HealthSample {
            val tokens = JSONTokener(body)
            val json = tokens.nextValue() as? JSONObject ?: error("Expected object")
            require(tokens.nextClean() == '\u0000')
            require(json.get("status") == "ok")
            fun bytes(key: String): Long {
                val value = json.get(key)
                require(value is Int || value is Long)
                return (value as Number).toLong().also { require(it >= 0) }
            }
            val cpu = json.get("cpu_percent") as? Number ?: error("Missing CPU")
            val sample = HealthSample(cpu.toDouble(), bytes("ram_total_bytes"), bytes("ram_available_bytes"),
                bytes("disk_total_bytes"), bytes("disk_available_bytes"), bytes("uptime_seconds"))
            require(sample.cpuPercent.isFinite() && sample.cpuPercent in 0.0..100.0)
            require(sample.ramTotal > 0 && sample.ramAvailable <= sample.ramTotal)
            require(sample.diskTotal > 0 && sample.diskAvailable <= sample.diskTotal)
            return sample
        }
    }
}

fun healthBytes(bytes: Long): String {
    var value = bytes.toDouble()
    val units = listOf("B", "KiB", "MiB", "GiB", "TiB", "PiB", "EiB")
    var index = 0
    while (value >= 1024 && index < units.lastIndex) { value /= 1024; index++ }
    return String.format(Locale.getDefault(), "%.1f %s", value, units[index])
}
fun healthUptime(seconds: Long) = "${seconds / 86400}d ${(seconds / 3600) % 24}h ${(seconds / 60) % 60}min ${seconds % 60}s"

/** One forwarding per SSH owner; creation and close are serialized against disconnect. */
internal class HealthForwarding(private val open: () -> Int, private val remove: (Int) -> Unit) {
    private var port: Int? = null
    private var closed = false
    @Synchronized fun port(): Int {
        check(!closed) { "SSH disconnected" }
        return port ?: open().also { require(it in 1..65535); port = it }
    }
    @Synchronized fun close() {
        closed = true
        val old = port
        port = null
        if (old != null) runCatching { remove(old) }
    }
}

internal fun fetchHealth(port: Int): HealthSample {
    require(port in 1..65535)
    val http = URL("http://127.0.0.1:$port/v1/health").openConnection(Proxy.NO_PROXY) as HttpURLConnection
    try {
        http.connectTimeout = 4000
        http.readTimeout = 4000
        http.instanceFollowRedirects = false
        http.useCaches = false
        require(http.responseCode == 200) { "Core HTTP unavailable" }
        val body = http.inputStream.use { input ->
            val buffer = ByteArray(8193)
            var count = 0
            while (count < buffer.size) {
                val read = input.read(buffer, count, buffer.size - count)
                if (read < 0) break
                count += read
            }
            buffer.copyOf(count)
        }
        require(body.size <= 8192) { "Core response too large" }
        return HealthSample.parse(body.toString(Charsets.UTF_8))
    } finally { http.disconnect() }
}

enum class HealthPhase { NO_SSH, IDLE, LOADING, READY, ERROR }
data class HealthState(val phase: HealthPhase = HealthPhase.NO_SSH, val sample: HealthSample? = null,
                       val fetchedAt: Long? = null) {
    val message get() = when (phase) {
        HealthPhase.NO_SSH -> "Sem conexão SSH. Conecte pelo Terminal."
        HealthPhase.IDLE -> "SSH conectado. Consulte o Core."
        HealthPhase.LOADING -> "Consultando Core…"
        HealthPhase.READY -> "Core conectado na última consulta."
        HealthPhase.ERROR -> "Core indisponível ou resposta inválida. Confira se está iniciado em 127.0.0.1:8765 e se o SSH permite encaminhamento."
    }
    fun attached(connected: Boolean) = copy(phase = if (connected) HealthPhase.IDLE else HealthPhase.NO_SSH)
}
