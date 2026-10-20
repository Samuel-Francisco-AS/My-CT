package dev.myct

import android.content.res.AssetManager
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.MessageDigest

internal enum class SetupState(val label: String) {
    missing("Ausente"), existing("Já existente"), running("Em execução"), installed("Instalado"),
    intervention("Requer intervenção"), failure("Falha"), incompatible("Incompatível")
}
internal data class SetupComponent(val id: String, val state: SetupState, val description: String, val privilege: String)
internal data class SetupPlan(val id: String, val release: String, val system: String, val arch: String, val user: String,
    val memory: Long, val disk: Long, val admin: String, val components: List<SetupComponent>,
    val blockers: List<String>, val optional: List<String>, val canApply: Boolean, val complete: Boolean,
    val packages: List<String>, val journal: List<String>)
internal fun parseSetupPlan(text: String): SetupPlan {
    val j = JSONObject(text)
    require(j.getInt("schema") == 1)
    val id = j.getString("plan_id").also { require(Regex("[a-f0-9]{64}").matches(it)) }
    fun strings(key: String) = j.getJSONArray(key).let { a ->
        require(a.length() <= 100); (0 until a.length()).map { a.getString(it).take(2048) }
    }
    val rows = j.getJSONArray("components").let { a ->
        require(a.length() == 5)
        (0 until a.length()).map {
            val row = a.getJSONObject(it)
            SetupComponent(row.getString("id"), SetupState.valueOf(row.getString("state")),
                row.getString("description").take(2048), row.getString("privilege").take(80))
        }
    }
    require(rows.map { it.id } == listOf("packages", "core", "postgres", "services", "verify"))
    val facts = j.getJSONObject("facts")
    val blockers = strings("blockers")
    val complete = j.getBoolean("complete")
    val canApply = j.getBoolean("can_apply")
    require(!canApply || (blockers.isEmpty() && !complete && rows.any { it.state == SetupState.missing }))
    require(!complete || (blockers.isEmpty() && rows.all { it.state == SetupState.existing }))
    val journal = j.getJSONArray("journal").let { a ->
        require(a.length() <= 30)
        (0 until a.length()).map { a.getJSONObject(it).let { row ->
            "${row.getString("component")} · ${row.getString("state")} · ${row.getString("message").take(2048)}"
        } }
    }
    val packages = facts.getJSONArray("missing_packages").let { a -> (0 until a.length()).map { a.getString(it) } }
    return SetupPlan(id, j.getString("release"), facts.getJSONObject("os").optString("PRETTY_NAME", "Desconhecido"),
        facts.getString("arch"), facts.getString("user"), j.getLong("memory_available"), j.getLong("disk_available"),
        j.getString("admin"), rows, blockers, strings("optional"), canApply, complete, packages, journal)
}
internal data class SetupBundle(val diagnostic: ByteArray, val payload: ByteArray, val hash: String) {
    companion object {
        fun load(assets: AssetManager): SetupBundle {
            val integrity = JSONObject(assets.open("myct-setup/integrity.json").bufferedReader().use { it.readText() })
            fun load(name: String, key: String): ByteArray {
                val data = assets.open("myct-setup/$name").use { it.readBytes() }
                require(data.size < 512 * 1024)
                require(sha256(data) == integrity.getString(key))
                return data
            }
            return SetupBundle(load("diagnose.py", "diagnose_sha256"), load("payload.pyz", "payload_sha256"),
                integrity.getString("payload_sha256"))
        }
    }
}
internal fun sha256(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }
internal fun setupDiagnosticCommand(code: ByteArray): String {
    val hex = code.joinToString("") { "%02x".format(it.toInt() and 255) }
    // Fixed local code, hex encoded. Runs directly in memory, no staging or file writes for inspection.
    return "exec python3 -c 'import sys; sys.argv=[\"myct-setup\",\"diagnose\"]; exec(bytes.fromhex(\"$hex\"))'"
}
internal class SetupLines(private val receive: (JSONObject) -> Unit) : OutputStream() {
    private val line = ByteArrayOutputStream()
    @Synchronized override fun write(b: Int) {
        if (b == 10) {
            if (line.size() > 0) receive(JSONObject(line.toString("UTF-8")))
            line.reset()
        } else { require(line.size() < 128 * 1024); line.write(b) }
    }
    override fun write(b: ByteArray, off: Int, len: Int) { for (i in off until off + len) write(b[i].toInt() and 255) }
}

internal fun setupReceiverCommand(bundle: SetupBundle, planId: String): String {
    require(Regex("[a-f0-9]{64}").matches(planId))
    val receiver = """
            import hashlib,os,subprocess,sys,tempfile
            os.umask(0o077)
            payload=sys.stdin.buffer.read(${bundle.payload.size})
            assert hashlib.sha256(payload).hexdigest()=="${bundle.hash}"
            password=bytearray(sys.stdin.buffer.readline(1025))
            try:
                with tempfile.TemporaryDirectory(prefix="myct-setup-") as work:
                    path=os.path.join(work,"payload.pyz")
                    with open(path,"xb") as file: file.write(payload)
                    status=subprocess.run([sys.executable,path,"apply","--approved","$planId"],input=password).returncode
            finally:
                password[:]=b'\0'*len(password)
            sys.exit(status)
        """.trimIndent()
    val hex = receiver.toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) }
    return "exec python3 -c 'exec(bytes.fromhex(\"$hex\"))'"
}
