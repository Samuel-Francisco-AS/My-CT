package dev.myct

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID

internal typealias CloudCall = suspend (CloudQuery) -> String

/** Keeps only a view of server state. A transmitted start is never replayed. */
internal class CloudModel(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val clock: () -> Long = System::currentTimeMillis,
    private val execute: (SshConnection, CloudQuery) -> String = { ssh, query -> ssh.cloud(query) }
) {
    private var ssh: SshConnection? = null
    private var epoch = 0
    private var job: Job? = null
    private var pollJob: Job? = null
    private val transport = Mutex()
    private var needsSnapshot = true
    private var pending: String? = null
    private var handled: String? = null
    var connected by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var stale by mutableStateOf(true); private set
    var message by mutableStateOf("Conecte ao Fedora pelo Terminal."); private set
    var entries by mutableStateOf(emptyList<CloudEntry>()); private set
    var torrents by mutableStateOf(emptyList<ServerTorrent>()); private set
    var tasks by mutableStateOf(emptyList<CloudTask>()); private set
    var space by mutableStateOf<CloudSpace?>(null); private set
    var inspection by mutableStateOf<JSONObject?>(null); private set
    var result by mutableStateOf<JSONObject?>(null); private set
    var resultWork by mutableStateOf<CloudWork?>(null); private set
    var selected by mutableStateOf<String?>(null); private set
    var selectedFile by mutableStateOf<String?>(null); private set
    private var catalogsReady by mutableStateOf(false)
    val active get() = tasks.any { it.active || it.uncertain }
    val actionsEnabled get() = connected && !busy && !stale && !active && catalogsReady

    fun attach(connection: SshConnection?) {
        epoch++; job?.cancel(); job = null; pollJob?.cancel(); pollJob = null
        ssh = connection; connected = connection != null; busy = false; stale = true
        catalogsReady = false; needsSnapshot = true
        // Keep the last view marked stale until the next authoritative snapshot.
        space = null; inspection = null; selectedFile = null; result = null; resultWork = null
        // The server journal, not this memory, survives process death/reconnection.
        pending = null; handled = null
        message = if (connected) "Atualize para consultar a Nuvem no Fedora." else "SSH desconectado. Trabalhos aceitos continuam no Fedora."
    }
    fun select(key: String?) {
        if (selected != key) { selected = key; space = null; inspection = null; result = null; resultWork = null }
    }
    fun prepareFile(path: String) {
        require(cloudPath(path)); selectedFile = path
        if (!busy) refresh()
    }
    fun dismissFile() { selectedFile = null }
    fun invalidatePreview() { space = null }
    fun canRelease(plan: CloudSpace, paths: List<String>) = actionsEnabled && space === plan &&
        selected == plan.id && plan.selected == paths.sorted() && plan.valid(clock())

    fun release(plan: CloudSpace, paths: List<String>, libraryConfirmed: Boolean) {
        if (!canRelease(plan, paths) || (paths.isNotEmpty() && !libraryConfirmed)) {
            space = null; message = "Confirmação obsoleta. Consulte espaço novamente."; return
        }
        start(plan.release(libraryConfirmed, clock()))
    }
    fun start(work: CloudWork) {
        if (!actionsEnabled) return
        try {
            work.json() // Validate before opening SSH, also for common SFTP filenames.
        } catch (_: IllegalArgumentException) {
            message = "Pedido inválido. Confira o arquivo ou a seleção antes de enviar."
            return
        }
        space = null; inspection = null; result = null; resultWork = null
        val id = UUID.randomUUID().toString().replace("-", "")
        pending = id; handled = null
        request { call ->
            val reply = cloudReply(call(CloudQuery.Start(id, work)))
            val task = parseCloudTask(reply.getJSONObject("task"))
            require(task.id == id && task.work == work)
            tasks = listOf(task) + tasks.filterNot { it.id == id }
            selectedFile = null
            message = if (task.uncertain) "Aceite incerto. Consulte o diário no Fedora; não repita o pedido."
                else "${work.title}: aceito pelo Fedora. Continua mesmo ao fechar o aplicativo."
        }
    }
    fun refresh() {
        if (busy) return
        request { call ->
            if (!needsSnapshot && tasks.any { it.active }) updateActive(call) else readSnapshot(call)
        }
    }
    /** Telemetry only while active. No busy toggle, catalog locks, navigation or dialogs. */
    fun poll() {
        val connection = ssh ?: return
        if (busy || pollJob?.isActive == true || tasks.none { it.active }) return
        val generation = epoch
        pollJob = scope.launch {
            try {
                transport.withLock {
                    if (generation == epoch && !busy) updateActive { query ->
                        runInterruptible(Dispatchers.IO) { execute(connection, query) }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                // Preserve last known telemetry; its timestamp controls stale speed/ETA.
                if (generation == epoch && tasks.none { it.active }) stale = true
            }
        }
    }
    private suspend fun updateActive(call: CloudCall) {
        var finished = false
        for (task in tasks.filter { it.active }) {
            val row = cloudReply(call(CloudQuery.Task(task.id))).getJSONObject("task")
            val current = parseCloudTask(row)
            require(current.id == task.id && current.work == task.work)
            tasks = tasks.map { if (it.id == current.id) current else it }
            if (!current.active) finished = true
        }
        // Exactly one catalog read after the active work terminates, including failure/cancel.
        if (finished) readSnapshot(call, navigate = false)
    }
    fun cancel(task: CloudTask) {
        val current = tasks.find { it.id == task.id && it.work == task.work } ?: return
        if (!connected || busy || !current.canCancel) return
        request { call ->
            val reply = cloudReply(call(CloudQuery.Cancel(current.id)))
            val next = parseCloudTask(reply.getJSONObject("task"))
            require(next.id == current.id && next.work == current.work)
            tasks = tasks.map { if (it.id == next.id) next else it }
            message = if (next.active) "Cancelamento solicitado; aguardando confirmação do Fedora." else next.label
            if (!next.active) readSnapshot(call, navigate = false)
        }
    }
    private suspend fun readSnapshot(call: CloudCall, navigate: Boolean = true) {
        val reply = cloudReply(call(CloudQuery.Snapshot))
        val nextTasks = reply.cloudObjects("tasks").map(::parseCloudTask).map { task ->
            if (task.active && task.progress == null) task.copy(progress = tasks.find { it.id == task.id }?.progress) else task
        }
        val catalogs = reply.getJSONObject("catalogs")
        val nextEntries = mutableListOf<CloudEntry>()
        val notices = mutableListOf<String>()
        var ready = true
        for (kind in listOf("torrent", "file")) {
            val catalog = catalogs.getJSONObject(kind)
            if (catalog.getBoolean("ok")) nextEntries += parseCloudEntries(catalog, kind)
            else {
                ready = false
                nextEntries += entries.filter { it.kind == kind }
                notices += cloudError(catalog.optJSONObject("error"))
            }
        }
        val rpc = reply.getJSONObject("transmission")
        torrents = if (rpc.getBoolean("ok")) parseTorrents(rpc.toString()) else emptyList()
        if (!rpc.getBoolean("ok")) notices += "Transmission indisponível. Estado de seeding não confirmado."
        tasks = nextTasks; entries = nextEntries; catalogsReady = ready
        if (navigate) entries.firstOrNull { it.kind == "torrent" && it.hash == selected }?.let { select(it.id) }
        stale = false; needsSnapshot = false
        message = if (notices.isEmpty()) "Catálogo do Fedora atualizado. Cópias no Drive conferidas na data indicada; consulte espaço para revalidar."
            else notices.joinToString(" ")
        val last = pending?.let { id -> tasks.find { it.id == id } }
        if (last != null && !last.active && !last.uncertain && handled != last.id) {
            readResult(call, last)
            handled = last.id
        }
    }
    fun viewTask(task: CloudTask) {
        if (busy) { return }
        if (task.work.action in setOf("space", "inspect")) {
            val destination = entries.firstOrNull { it.hash == task.work.target }?.id ?: task.work.target
            select(destination)
        }
        request { call -> readResult(call, task) }
    }
    private suspend fun readResult(call: CloudCall, task: CloudTask) {
        val row = cloudReply(call(CloudQuery.Task(task.id))).getJSONObject("task")
        val current = parseCloudTask(row)
        require(current.id == task.id && current.work == task.work)
        tasks = tasks.map { if (it.id == current.id) current else it }
        if (current.active) {
            message = "${current.label} no Fedora. O trabalho continua após desconectar."
            return
        }
        if (task.active && !current.active) readSnapshot(call, navigate = false)
        val reply = row.optJSONObject("result")
        result = reply; resultWork = current.work
        if (current.uncertain || reply == null) {
            message = "Resultado incerto ou ainda em andamento. Confira no Fedora; nada será repetido."
            return
        }
        if (current.state == "cancelled") {
            space = null; inspection = null
            message = "Transferência cancelada no Fedora. Originais preservados; confira o catálogo antes de nova tentativa."
            return
        }
        if (!reply.getBoolean("ok")) {
            space = null; inspection = null
            message = cloudError(reply.optJSONObject("error"))
            return
        }
        message = "${current.work.title}: concluído."
        when (current.work.action) {
            "space" -> {
                // Old results may be inspected but never become a new consent window.
                val plan = parseCloudSpace(reply, clock())
                if (selected == plan.id) space = plan
            }
            "inspect" -> if (selected == current.work.target || entries.any { it.id == selected && it.hash == current.work.target }) inspection = reply
            "recover" -> message = if (reply.optBoolean("seed_started"))
                "Seeding iniciado. " + if (reply.optBoolean("upload_observed")) "Upload observado." else "Sem upload observado."
                else "Peças verificadas. Pronto para seeding, inicialmente pausado."
        }
    }
    private fun request(block: suspend (CloudCall) -> Unit) {
        val connection = ssh ?: return
        if (busy) return
        val generation = epoch
        busy = true
        job = scope.launch {
            try {
                // All blocking transport calls are serialized, with no main-thread SSH.
                transport.withLock {
                    block { query -> runInterruptible(Dispatchers.IO) { execute(connection, query) } }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation == epoch) {
                    stale = true; space = null; inspection = null
                    message = (failure as? DownloadFailure)?.safeMessage
                        ?: "Falha de rede/SSH ou resposta inválida. Atualize para consultar o resultado; o pedido não será repetido."
                }
            } finally { if (generation == epoch) busy = false }
        }
    }
}
