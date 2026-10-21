package dev.myct

import android.content.res.AssetManager
import androidx.compose.runtime.*
import kotlinx.coroutines.*

internal class ServerSetupModel(private val assets: AssetManager) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var connection: SshConnection? = null
    private var generation = 0
    val backup = BackupSetupModel(assets)
    var target by mutableStateOf(SetupTarget.base); private set
    fun select(value: SetupTarget) {
        if (busy || backup.busy || target == value) return
        target = value; onlineMessage = ""; plan = null; events = emptyList(); diagnose()
    }
    var cloudRemote by mutableStateOf("myct-gdrive:"); private set
    var cloudConfig by mutableStateOf(""); private set
    private var cloudEdited = false
    var onlineMessage by mutableStateOf(""); private set
    var oauthTunnel by mutableStateOf(false); private set
    fun editCloud(remote: String, config: String) {
        if (busy) return
        cloudRemote = remote; cloudConfig = config; cloudEdited = true; plan = null; onlineMessage = ""
    }
    fun tunnel(open: Boolean) {
        val current = connection ?: return
        if (busy) return
        val id = generation; busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { current.setupOAuthTunnel(open) }
                if (id == generation) { oauthTunnel = open; message = if (open) "Túnel OAuth local 127.0.0.1:53682 aberto. Configure interativamente no Terminal." else "Túnel OAuth encerrado." }
            } catch (_: Exception) {
                if (id == generation) message = "Túnel indisponível. Confira SSH e se a porta local 53682 está livre. Nenhuma configuração foi alterada."
            } finally { if (id == generation) busy = false }
        }
    }
    fun verifyCloud() {
        val current = connection ?: return
        val approved = plan ?: return
        if (busy || approved.target != SetupTarget.rclone || approved.cloud?.configured != true) return
        val id = generation; busy = true; onlineMessage = "Verificação online autorizada em andamento…"
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { current.setupVerifyCloud(bundle, approved) }
                if (id == generation) onlineMessage = result
            } catch (_: Exception) {
                if (id == generation) onlineMessage = "Verificação indisponível ou diagnóstico mudou. Consulte novamente; nenhum acesso foi presumido."
            } finally { if (id == generation) busy = false }
        }
    }
    var connected by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var plan by mutableStateOf<SetupPlan?>(null); private set
    var message by mutableStateOf("Conecte por SSH para diagnosticar o Fedora."); private set
    var events by mutableStateOf<List<String>>(emptyList()); private set
    private val bundle by lazy { SetupBundle.load(assets) }
    fun attach(value: SshConnection?) {
        backup.attach(value)
        generation++; connection = value; connected = value != null
        plan = null; busy = false; events = emptyList(); onlineMessage = ""; oauthTunnel = false; cloudEdited = false; cloudRemote = "myct-gdrive:"; cloudConfig = ""
        message = if (connected) "Diagnóstico somente leitura; nenhuma alteração será aplicada automaticamente." else
            "Sem SSH. Reconecte e diagnostique para consultar o estado real; pedidos anteriores não serão repetidos."
    }
    fun diagnose() {
        if (target == SetupTarget.backup) { backup.inventory(); return }
        val current = connection ?: return
        if (busy) return
        val selected = try { if (target == SetupTarget.rclone && cloudEdited) SetupCloudSelection(cloudRemote, cloudConfig) else null }
            catch (_: Exception) { message = "Informe NOME: e caminho absoluto da configuração, ou deixe o caminho vazio."; return }
        val id = generation
        onlineMessage = ""
        busy = true; plan = null
        message = "Consultando Fedora, pacotes, serviços e banco…"
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { current.setupDiagnose(bundle, target, selected) }
                if (id == generation) {
                    plan = result
                    result.cloud?.selection?.let { cloudRemote = it.remote; cloudConfig = it.config }
                    message = when {
                        result.complete -> if (target == SetupTarget.rclone) "Rclone e helpers instalados. OAuth e acesso online têm verificação separada." else "${target.label}: instalação e serviços verificados."
                        result.blockers.isNotEmpty() -> "Requer intervenção. Instalações existentes foram preservadas."
                        else -> "Diagnóstico concluído. Revise o plano antes de aprovar."
                    }
                }
            } catch (_: Exception) {
                if (id == generation) message = "Diagnóstico indisponível. Confira SSH e python3 no Fedora; nenhum sucesso foi presumido."
            } finally { if (id == generation) busy = false }
        }
    }
    fun apply(password: ByteArray) {
        val current = connection
        val approved = plan
        if (busy || current == null || approved?.canApply != true) { password.fill(0); return }
        val id = generation
        busy = true; plan = null; events = emptyList()
        message = "Executando o plano aprovado. Pode levar alguns minutos."
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    current.setupApply(bundle, approved.id, password, approved.target, approved.cloud?.selection) { event ->
                        val status = event.optString("state")
                        val text = "${event.optString("component", "Instalação")} · $status · ${event.optString("message").take(2048)}"
                        scope.launch {
                            if (id == generation) {
                                events = (events + text).takeLast(40)
                                message = if (status == "complete") "Execução encerrada. Consultando o estado real…" else text
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                if (id == generation) message = "Execução sem conclusão confirmada. Etapas concluídas ficam no Fedora; consulte antes de retomar."
            } finally {
                password.fill(0)
                if (id == generation) { busy = false; diagnose() }
            }
        }
    }
}
