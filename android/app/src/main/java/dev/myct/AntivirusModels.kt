package dev.myct

import androidx.compose.runtime.*
import kotlinx.coroutines.*

internal class AntivirusScanModel(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val execute: (SshConnection, String) -> AvScanResult = { ssh, path -> ssh.scanAntivirus(path) }
) {
    private var connection: SshConnection? = null
    private var path: String? = null
    private var bytes: Long? = null
    private var generation = 0
    private var job: Job? = null
    var busy by mutableStateOf(false); private set
    var result by mutableStateOf<AvScanResult?>(null); private set
    var message by mutableStateOf(""); private set
    val tooLarge get() = (bytes ?: 0) > AV_MAX_BYTES
    val canScan get() = connection != null && path != null && !tooLarge && !busy
    fun attach(value: SshConnection?) { clear(); connection = value }
    fun select(value: String, size: Long?) {
        clear(); path = value; bytes = size
        message = if (tooLarge) "Arquivo acima do limite. $AV_LIMIT_NOTICE" else "Análise local sob demanda no Fedora."
    }
    fun clear() {
        generation++; job?.cancel(); busy = false; result = null; path = null; bytes = null; message = ""
    }
    fun cancel(notice: String = "Análise cancelada. Nenhum resultado completo foi obtido.") {
        if (!busy) return
        generation++; job?.cancel(); busy = false
        result = AvScanResult(AvState.INCOMPLETE, notice); message = notice
    }
    fun scan() {
        if (busy) return
        if (tooLarge) { result = AvScanResult(AvState.INCOMPLETE, "Arquivo acima do limite. $AV_LIMIT_NOTICE"); return }
        val target = path ?: return
        val ssh = connection ?: run { message = "SSH desconectado. Reconecte para analisar."; return }
        val previous = job
        val id = ++generation
        busy = true; result = null
        message = "Carregando assinaturas no Fedora: aproximadamente 10 segundos; arquivos grandes podem demorar mais."
        job = scope.launch {
            try {
                previous?.join() // Finish cancellation/EOF before opening a new exec.
                val reply = runInterruptible(Dispatchers.IO) { execute(ssh, target) }
                if (id == generation) { result = reply; message = reply.message }
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                if (id == generation) message = (e as? DownloadFailure)?.safeMessage
                    ?: "Resultado não confirmado: falha no canal SSH ou resposta inválida. Tente novamente manualmente."
            } finally { if (id == generation) busy = false }
        }
    }
}

internal class AntivirusSignaturesModel(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val pollMs: Long = 2000,
    private val execute: (SshConnection, AvCommand) -> SshExecResult = { ssh, command -> ssh.antivirus(command) }
) {
    private var connection: SshConnection? = null
    private var generation = 0
    private var job: Job? = null
    private var visible = false
    var connected by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var info by mutableStateOf<AvInfo?>(null); private set
    var update by mutableStateOf<AvUpdate?>(null); private set
    var message by mutableStateOf("Conecte ao Fedora pela aba Terminal."); private set
    var unconfirmed by mutableStateOf(false); private set
    fun attach(value: SshConnection?) {
        hide(); connection = value; connected = value != null
        info = null; update = null; unconfirmed = false
        message = if (connected) "Consulte o estado real no Fedora." else "SSH desconectado. Conecte pela aba Terminal."
    }
    fun show() { visible = true; refresh() }
    fun hide() {
        visible = false; generation++; job?.cancel(); busy = false
        unconfirmed = true // An update continues on Fedora; reopen always queries, never replays.
    }
    fun refresh() = operate(false)
    fun requestUpdate() = operate(true)
    private fun operate(request: Boolean) {
        val ssh = connection ?: run { message = "SSH desconectado. Conecte pela aba Terminal."; return }
        if (!visible || busy) return
        val previous = job
        val id = ++generation
        busy = true
        job = scope.launch {
            try {
                previous?.join()
                if (request) {
                    update = null; unconfirmed = true
                    message = "Enviando pedido de atualização…"
                    val accepted = runInterruptible(Dispatchers.IO) { parseAvRequest(execute(ssh, AvCommand.UPDATE)) }
                    if (!accepted) throw DownloadFailure("Pedido recusado; consulte o estado antes de repetir.")
                    if (id == generation) message = "Pedido enviado. A conclusão ainda será consultada no Fedora."
                } else {
                    val current = runInterruptible(Dispatchers.IO) { parseAvStatus(execute(ssh, AvCommand.STATUS)) }
                    if (id == generation) info = current
                }
                do {
                    val current = runInterruptible(Dispatchers.IO) { parseAvUpdate(execute(ssh, AvCommand.UPDATE_STATUS)) }
                    if (id != generation) return@launch
                    info = current.info ?: info; update = current; unconfirmed = current.info == null; message = current.message
                    if (!current.running) break
                    delay(pollMs)
                } while (visible && isActive)
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                if (id == generation) {
                    unconfirmed = true
                    message = (e as? DownloadFailure)?.safeMessage
                        ?: "Estado não confirmado: falha SSH ou resposta inválida. Consulte novamente; pedido anterior não será repetido."
                }
            } finally { if (id == generation) busy = false }
        }
    }
}
