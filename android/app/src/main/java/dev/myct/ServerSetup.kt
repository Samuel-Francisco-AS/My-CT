package dev.myct

import android.content.res.AssetManager
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.MessageDigest

internal enum class SetupState(val label: String) {
    missing("Ausente"), existing("Já existente"), running("Em execução"), installed("Instalado"),
    unmanaged("Instalação anterior não gerenciada"), intervention("Requer intervenção"), failure("Falha"), incompatible("Incompatível")
}
internal data class SetupComponent(val id: String, val state: SetupState, val description: String, val privilege: String)
internal enum class SetupTarget(val label: String) { base("Base"), transmission("Transmission"), prowlarr("Prowlarr"), rclone("Rclone / Drive") }
internal data class SetupInventory(val detected: Boolean, val managed: Boolean, val installed: Boolean, val running: Boolean,
    val enabled: Boolean, val linger: Boolean, val apiReady: Boolean, val version: String)
internal data class SetupPlan(val id: String, val release: String, val system: String, val arch: String, val user: String,
    val memory: Long, val disk: Long, val admin: String, val components: List<SetupComponent>,
    val blockers: List<String>, val optional: List<String>, val canApply: Boolean, val complete: Boolean,
    val packages: List<String>, val journal: List<String>, val target: SetupTarget, val inventory: SetupInventory?, val cloud: SetupCloud? = null)
internal fun parseSetupPlan(text: String): SetupPlan {
    val j = JSONObject(text)
    require(j.getInt("schema") == 1)
    val id = j.getString("plan_id").also { require(Regex("[a-f0-9]{64}").matches(it)) }
    fun strings(key: String) = j.getJSONArray(key).let { a ->
        require(a.length() <= 100); (0 until a.length()).map { a.getString(it).take(2048) }
    }
    val rows = j.getJSONArray("components").let { a ->
        require(a.length() in 4..5)
        (0 until a.length()).map {
            val row = a.getJSONObject(it)
            SetupComponent(row.getString("id"), SetupState.valueOf(row.getString("state")),
                row.getString("description").take(2048), row.getString("privilege").take(80))
        }
    }
    val facts = j.getJSONObject("facts")
    val target = SetupTarget.valueOf(facts.optString("target", "base"))
    val expected = if (target == SetupTarget.base) listOf("packages", "core", "postgres", "services", "verify")
        else listOf("packages", target.name, "services", "verify")
    require(rows.map { it.id } == expected)
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
    val inventory = j.optJSONObject("inventory")?.let { v ->
        SetupInventory(v.getBoolean("detected"), v.getBoolean("managed"), v.getBoolean("installed"), v.getBoolean("running"),
            v.getBoolean("enabled"), v.getBoolean("linger"), v.getBoolean("api_ready"), v.getString("version").take(64))
    }
    require(target == SetupTarget.base || inventory != null)
    require((target == SetupTarget.rclone) == j.has("cloud"))
    return SetupPlan(id, j.getString("release"), facts.getJSONObject("os").optString("PRETTY_NAME", "Desconhecido"),
        facts.getString("arch"), facts.getString("user"), j.getLong("memory_available"), j.getLong("disk_available"),
        j.getString("admin"), rows, blockers, strings("optional"), canApply, complete, packages, journal, target, inventory, j.optJSONObject("cloud")?.let { parseSetupCloud(it) })
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
internal fun setupDiagnosticCommand(code: ByteArray, target: SetupTarget = SetupTarget.base, selection: SetupCloudSelection? = null, onlineApproval: String? = null): String {
    require(onlineApproval == null || (target == SetupTarget.rclone && Regex("[a-f0-9]{64}").matches(onlineApproval)))
    val action = if (onlineApproval == null) "diagnose" else "verify-cloud"
    val approvedArg = onlineApproval?.let { ",\"--approved\",\"$it\"" } ?: ""
    val compressed = ByteArrayOutputStream().also { buffer ->
        java.util.zip.DeflaterOutputStream(buffer).use { it.write(code) }
    }.toByteArray()
    val hex = compressed.joinToString("") { "%02x".format(it.toInt() and 255) }
    // Compressed fixed APK code, never shell-interpolated source; diagnosis remains in memory.
    return "exec python3 -c 'import sys,zlib; sys.argv=[\"myct-setup\",\"$action\",\"--component\",\"${target.name}\"$approvedArg]+${setupSelectionArguments(selection)}; exec(zlib.decompress(bytes.fromhex(\"$hex\")))'"
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

internal fun setupReceiverCommand(bundle: SetupBundle, planId: String, target: SetupTarget = SetupTarget.base, selection: SetupCloudSelection? = null): String {
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
                    status=subprocess.run([sys.executable,path,"apply","--approved","$planId","--component","${target.name}"]+${setupSelectionArguments(selection)},input=password).returncode
            finally:
                password[:]=b'\0'*len(password)
            sys.exit(status)
        """.trimIndent()
    val hex = receiver.toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) }
    return "exec python3 -c 'exec(bytes.fromhex(\"$hex\"))'"
}

internal data class SetupCloudSelection(val remote: String, val config: String) {
    init {
        require(Regex("[A-Za-z][A-Za-z0-9_-]{0,63}:").matches(remote))
        require(config.isEmpty() || (config.startsWith('/') && config.length <= 1024 && config.none { it.code < 32 || it.code == 127 }))
    }
    fun arguments(): List<String> = listOf("--remote", remote, "--config", config)
}
internal data class SetupCloud(val selection: SetupCloudSelection, val configState: String, val configPath: String,
    val configured: Boolean, val manager: Boolean, val helpers: List<String>)
internal fun setupSelectionArguments(selection: SetupCloudSelection?): String {
    val json = org.json.JSONArray(selection?.arguments() ?: emptyList<String>()).toString()
    val hex = json.toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) }
    return "__import__(\"json\").loads(bytes.fromhex(\"$hex\"))"
}
internal fun parseSetupCloud(j: JSONObject): SetupCloud {
    val s = j.getJSONObject("selection")
    val c = j.getJSONObject("config")
    val helpers = j.getJSONObject("helpers")
    return SetupCloud(SetupCloudSelection(s.getString("remote"), s.getString("config")),
        c.getString("state").also { require(it in listOf("pending", "configured", "protected", "incompatible")) },
        c.getString("path").take(1024), c.getBoolean("configured"), j.getBoolean("manager"),
        helpers.keys().asSequence().map { name -> "$name · " + when (helpers.getString(name)) { "compatible" -> "Compatível"; "previous" -> "Anterior preservado"; "missing" -> "Ausente"; else -> "Requer intervenção" } }.toList())
}
internal fun setupCloudVerificationMessage(text: String): String {
    val j = JSONObject(text)
    require(j.getInt("schema") == 1)
    // Render fixed local text only. Raw OAuth/provider error output is never displayed.
    return when (j.getString("state")) {
        "verified" -> {
            require(j.getBoolean("access") && !j.getBoolean("archive_recoverable"))
            "Acesso autenticado confirmado. " + (if (j.optLong("quota_free", -1) == 0L) "Cota sem espaço livre. " else "") +
                (if (j.getBoolean("archive_accessible")) "Pasta My-CT-Archive acessível. " else "Pasta My-CT-Archive não confirmada; pode não existir. ") +
                "Integridade e recuperação dos arquivos não foram verificadas. Nenhuma transferência iniciada."
        }
        "failure" -> when (j.optString("code")) {
            "oauth" -> "Autenticação não confirmada. Revise OAuth privadamente pelo Terminal."
            "quota" -> "Acesso recusado por permissão, limite ou cota. Confira a conta."
            "network" -> "Rede ou serviço indisponível. Nenhum acesso foi presumido."
            else -> "Verificação indisponível. Diagnostique novamente; detalhes privados omitidos."
        }
        else -> error("Resposta inválida")
    }
}
