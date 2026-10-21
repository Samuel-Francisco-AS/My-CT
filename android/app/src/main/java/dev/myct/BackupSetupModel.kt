package dev.myct

import android.content.res.AssetManager
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import org.json.JSONObject

internal class BackupSetupModel(private val assets: AssetManager) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var connection: SshConnection? = null
    private var generation = 0
    private val bundle by lazy { SetupBundle.load(assets) }
    var connected by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var reply by mutableStateOf<JSONObject?>(null); private set
    var message by mutableStateOf("Inventário passivo; nenhuma exportação ou restauração automática."); private set
    var destination by mutableStateOf(""); private set
    var packagePath by mutableStateOf(""); private set
    var mediaRoot by mutableStateOf(""); private set
    var recipient by mutableStateOf(""); private set
    var database by mutableStateOf(false); private set
    var catalogs by mutableStateOf(false); private set
    fun attach(value: SshConnection?) {
        generation++; connection = value; connected = value != null; busy = false; reply = null
        destination = ""; packagePath = ""; mediaRoot = ""; recipient = ""; database = false; catalogs = false
        message = "Consulte o estado real. Nenhum pedido anterior será repetido."
    }
    fun edit(destination: String = this.destination, packagePath: String = this.packagePath, mediaRoot: String = this.mediaRoot,
             recipient: String = this.recipient, database: Boolean = this.database, catalogs: Boolean = this.catalogs) {
        if (busy) return
        this.destination = destination; this.packagePath = packagePath; this.mediaRoot = mediaRoot
        this.recipient = recipient; this.database = database; this.catalogs = catalogs; reply = null
    }
    private fun exportRequest(operation: String) = JSONObject().put("operation", operation).put("path", destination)
        .put("database", database).put("catalogs", catalogs).put("recipient", recipient)
    fun inventory() = execute(JSONObject().put("operation", "inventory"))
    fun planExport() {
        if (recipient.isNotEmpty() && !Regex("age1[0-9a-z]{58}").matches(recipient)) {
            message = "Informe somente destinatário público age1. Nunca informe a identidade secreta neste campo."; return
        }
        execute(exportRequest("plan-export"))
    }
    fun inspect(identity: ByteArray) = execute(JSONObject().put("operation", "inspect").put("path", packagePath).put("media_root", mediaRoot), identity)
    fun approve(identity: ByteArray) {
        val plan = reply
        if (busy || plan?.optBoolean("can_apply") != true) { identity.fill(0); return }
        val operation = plan.optString("operation")
        val request = when (operation) {
            "plan-export" -> exportRequest("export")
            "inspect" -> JSONObject().put("operation", "restore").put("path", packagePath).put("media_root", mediaRoot)
            else -> { identity.fill(0); return }
        }
        request.put("approved", plan.getString("plan_id")); execute(request, identity)
    }
    private fun execute(request: JSONObject, identity: ByteArray = byteArrayOf()) {
        val current = connection
        if (busy || current == null) { identity.fill(0); return }
        val id = generation; busy = true; reply = null
        message = when (request.optString("operation")) {
            "export" -> "Exportando somente as categorias aprovadas; pacote parcial não será publicado."
            "restore" -> "Restaurando somente a prévia aprovada; arquivos existentes serão preservados."
            "inspect" -> "Verificando pacote e comparando destinos; nenhuma restauração nesta etapa."
            else -> "Consultando inventário/plano, sem exportar dados."
        }
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { current.setupBackup(bundle, request, identity) }
                if (id == generation) {
                    reply = result
                    message = result.optString("message").ifBlank { "Prévia pronta. Confira conteúdo, exclusões, caminhos e conflitos antes de aprovar." }
                }
            } catch (_: Exception) {
                if (id == generation) message = "Operação sem conclusão confirmada. Inspecione o pacote/destino antes de tentar novamente; nenhum sucesso foi presumido."
            } finally { identity.fill(0); if (id == generation) busy = false }
        }
    }
}
