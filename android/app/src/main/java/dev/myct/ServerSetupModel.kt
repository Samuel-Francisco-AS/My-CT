package dev.myct

import android.content.res.AssetManager
import androidx.compose.runtime.*
import kotlinx.coroutines.*

internal class ServerSetupModel(private val assets: AssetManager) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var connection: SshConnection? = null
    private var generation = 0
    var connected by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var plan by mutableStateOf<SetupPlan?>(null); private set
    var message by mutableStateOf("Conecte por SSH para diagnosticar o Fedora."); private set
    var events by mutableStateOf<List<String>>(emptyList()); private set
    private val bundle by lazy { SetupBundle.load(assets) }
    fun attach(value: SshConnection?) {
        generation++; connection = value; connected = value != null
        plan = null; busy = false; events = emptyList()
        message = if (connected) "Diagnóstico somente leitura; nenhuma alteração será aplicada automaticamente." else
            "Sem SSH. Reconecte e diagnostique para consultar o estado real; pedidos anteriores não serão repetidos."
    }
    fun diagnose() {
        val current = connection ?: return
        if (busy) return
        val id = generation
        busy = true; plan = null
        message = "Consultando Fedora, pacotes, serviços e banco…"
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { current.setupDiagnose(bundle) }
                if (id == generation) {
                    plan = result
                    message = when {
                        result.complete -> "Base configurada: Core, banco e serviços verificados."
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
                    current.setupApply(bundle, approved.id, password) { event ->
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
