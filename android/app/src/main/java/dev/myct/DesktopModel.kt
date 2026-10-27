package dev.myct

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import java.io.Closeable

internal interface DesktopPeer : Closeable {
    fun observeProgress(observer: (DesktopStep, Boolean) -> Unit) {}
    fun connect(port: Int, password: CharArray)
    fun read(onFrame: (RfbFramebuffer) -> Unit)
    fun pointer(x: Int, y: Int, mask: Int)
    fun key(sym: Int, down: Boolean)
    fun dispose()
}
private class SocketDesktopPeer : DesktopPeer {
    private val client = RfbClient()
    override fun observeProgress(observer: (DesktopStep, Boolean) -> Unit) { client.progress = observer }
    override fun connect(port: Int, password: CharArray) = client.connect(port, password)
    override fun read(onFrame: (RfbFramebuffer) -> Unit) = client.readUpdate(onFrame)
    override fun pointer(x: Int, y: Int, mask: Int) = client.pointer(x, y, mask)
    override fun key(sym: Int, down: Boolean) = client.key(sym, down)
    override fun close() = client.close()
    override fun dispose() = client.disposeDecoder()
}

/** UI-thread owner. Blocking transport, decoding and writes run on IO workers. */
internal class DesktopModel(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val tunnel: (SshConnection) -> DesktopTunnel = { it.openDesktopTunnel() },
    private val query: (SshConnection, DesktopOperation) -> SshExecResult = { c, op -> c.desktopService(op) },
    private val makePeer: () -> DesktopPeer = { SocketDesktopPeer() },
    private val pollDelayMs: Long = 750,
    private val inspectAdministration: (SshConnection) -> DesktopAdministration = { it.desktopAdministration() },
    private val configurationGraceMs: Long = 3000
) {
    private var connection: SshConnection? = null
    @Volatile private var epoch = 0
    private var serviceEpoch = 0
    private var serviceJob: Job? = null
    private var linkJob: Job? = null
    private var peer: DesktopPeer? = null
    private var events: DesktopInputBuffer? = null
    @Volatile private var visible = false
    private val renderLock = Any()
    private var render: ((RfbFramebuffer) -> Unit)? = null
    private var clearImage: (() -> Unit)? = null
    @Volatile private var surfaceOwner: Any? = null
    private var latestFrame: RfbFramebuffer? = null
    private var handoffJob: Job? = null
    private var surfaceStartedNs = 0L
    private var connectionStartedNs = 0L
    private var handoffStartedNs = 0L
    var connectionFirstFrameMs by mutableStateOf<Long?>(null); private set
    var configurationRecoveryMs by mutableStateOf<Long?>(null); private set
    var surfaceRecoveryMs by mutableStateOf<Long?>(null); private set
    var surfacePresentations by mutableIntStateOf(0); private set
    internal val attempt get() = epoch
    var diagnostics by mutableStateOf(DesktopTrace()); private set
    var presented by mutableStateOf(false); private set
    var ssh by mutableStateOf(false); private set
    var service by mutableStateOf(DesktopService.UNKNOWN); private set
    var administration by mutableStateOf(DesktopAdministration()); private set
    var adminOutcome by mutableStateOf(DesktopAdminOutcome.NONE); private set
    var adminMessage by mutableStateOf(""); private set
    var changing by mutableStateOf(false); private set
    private var stopPending = false
    var link by mutableStateOf(DesktopLink.DISCONNECTED); private set
    var retiring by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf("Conecte o SSH pelo Terminal."); private set
    var frames by mutableLongStateOf(0); private set
    var resolution by mutableStateOf(""); private set
    var ctrl by mutableStateOf(false); private set
    var alt by mutableStateOf(false); private set
    var shift by mutableStateOf(false); private set
    var drag by mutableStateOf(false); private set
    private var pointerX = 0; private var pointerY = 0
    internal val cursorX get() = pointerX
    internal val cursorY get() = pointerY
    internal var framebufferWidth = 1280; private set
    internal var framebufferHeight = 720; private set
    internal var transform = DesktopTransform()
    var interaction by mutableStateOf(DesktopInteraction.DIRECT); private set
    var localPan by mutableStateOf(false); private set
    var trackpadGain by mutableFloatStateOf(1.5f); private set
    internal var inputGeneration = 0; private set
    private var pointerTouched = false
    var inputFailures by mutableIntStateOf(0); private set
    private var completedInputStats = 0 to 0L
    fun inputStats() = events?.stats() ?: completedInputStats
    fun changeInteraction(value: DesktopInteraction) {
        releaseInput(); drag = false; interaction = value
        if (value == DesktopInteraction.TRACKPAD && !pointerTouched) {
            pointerX = framebufferWidth / 2; pointerY = framebufferHeight / 2
        }
    }
    fun changePan(value: Boolean) { releaseInput(); localPan = value }
    fun changeSensitivity(value: Float) { trackpadGain = value.coerceIn(.5f, 3f) }

    fun attach(value: SshConnection?) {
        if (connection === value) return
        disconnect(); serviceJob?.cancel(); serviceJob = null
        serviceEpoch++; connection = value; ssh = value != null; busy = false
        service = DesktopService.UNKNOWN
        administration = DesktopAdministration(); adminMessage = ""; adminOutcome = DesktopAdminOutcome.NONE
        changing = false; stopPending = false
        diagnostics = DesktopTrace()
        if (visible) diagnostics.step(DesktopStep.SCREEN, true)
        if (ssh) diagnostics.step(DesktopStep.SSH, true)
        message = if (ssh) "Consulte a sessão gráfica ou conecte ao display :1." else "Conecte o SSH pelo Terminal."
        if (visible && ssh) refresh()
    }
    /** Only the current surface may detach/acknowledge. No Activity is retained after detach. */
    fun show(renderer: (RfbFramebuffer) -> Unit, clear: () -> Unit, owner: Any = renderer) {
        handoffJob?.cancel(); handoffJob = null
        val id = epoch
        synchronized(renderLock) {
            if (surfaceOwner !== owner) {
                presented = false; surfaceRecoveryMs = null; surfaceStartedNs = System.nanoTime()
            }
            visible = true; surfaceOwner = owner; render = renderer; clearImage = clear
        }
        diagnostics.step(DesktopStep.SCREEN, true)
        diagnostics.step(DesktopStep.SSH, ssh)
        // An idle VNC server need not send a new update after rotation. Reuse its current
        // framebuffer under the same lock/owner checks; pixel copying stays on IO.
        scope.launch(Dispatchers.IO) {
            synchronized(renderLock) {
                if (id == epoch && surfaceOwner === owner && link == DesktopLink.CONNECTED) {
                    latestFrame?.let { frame ->
                        try { render?.invoke(frame) }
                        catch (_: Exception) { scope.launch { if (ownsSurface(owner)) renderingFailed(id, owner) } }
                    }
                }
            }
        }
        if (ssh && link != DesktopLink.CONNECTED && link != DesktopLink.CONNECTING) refresh()
    }
    fun ownsSurface(owner: Any): Boolean = surfaceOwner === owner
    fun detachSurface(owner: Any, changingConfiguration: Boolean) {
        val detached = synchronized(renderLock) {
            if (surfaceOwner !== owner) false else {
                clearImage?.invoke()
                render = null; clearImage = null; surfaceOwner = null; visible = false
                presented = false; true
            }
        }
        if (!detached) return
        releaseInput()
        if (!changingConfiguration) { hide(); return }
        handoffJob?.cancel()
        handoffStartedNs = System.nanoTime()
        val id = epoch
        handoffJob = scope.launch {
            delay(configurationGraceMs)
            if (id == epoch && !visible) hide()
        }
    }
    fun hide() {
        handoffJob?.cancel(); handoffJob = null
        visible = false; disconnect()
        synchronized(renderLock) { render = null; clearImage = null; surfaceOwner = null; latestFrame = null }
    }
    fun refresh() = operation(DesktopOperation.STATUS)
    fun start() = operation(DesktopOperation.START)
    fun stop() = operation(DesktopOperation.STOP)

    private suspend fun readService(current: SshConnection): DesktopService = try {
        runInterruptible(Dispatchers.IO) { parseDesktopService(query(current, DesktopOperation.STATUS)) }
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { DesktopService.ERROR }

    private suspend fun readAdministration(current: SshConnection): DesktopAdministration = try {
        runInterruptible(Dispatchers.IO) { inspectAdministration(current) }
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { DesktopAdministration(DesktopAdminAccess.ERROR) }

    private fun unavailableOutcome(access: DesktopAdminAccess) =
        if (access == DesktopAdminAccess.DENIED || access == DesktopAdminAccess.READY) DesktopAdminOutcome.DENIED else DesktopAdminOutcome.UNAVAILABLE

    private fun operation(op: DesktopOperation) {
        val current = connection ?: return
        if (busy || op != DesktopOperation.STATUS && link == DesktopLink.CONNECTING) return
        val id = serviceEpoch
        busy = true; changing = op != DesktopOperation.STATUS
        if (changing) { adminOutcome = DesktopAdminOutcome.RUNNING; adminMessage = "Verificando autorização e estado remoto…" }
        serviceJob = scope.launch {
            try {
                val access = readAdministration(current)
                val actual = readService(current)
                if (id != serviceEpoch) return@launch
                administration = access; service = actual
                if (op == DesktopOperation.STATUS) {
                    adminOutcome = DesktopAdminOutcome.NONE
                    adminMessage = if (actual == DesktopService.ERROR) "Não foi possível confirmar o estado da unidade Fedora."
                        else "Estado da unidade Fedora atualizado."
                    return@launch
                }
                val permitted = if (op == DesktopOperation.START) access.canStart else access.canStop
                if (!permitted) {
                    adminOutcome = unavailableOutcome(access.access)
                    adminMessage = access.message + " Nenhum comando de alteração foi enviado."
                    return@launch
                }
                if (actual == DesktopService.ERROR || actual == DesktopService.UNKNOWN) {
                    adminOutcome = DesktopAdminOutcome.UNCERTAIN
                    adminMessage = "Estado remoto não confirmado; nenhuma alteração enviada. Atualize antes de repetir."
                    return@launch
                }
                val desired = if (op == DesktopOperation.START) DesktopService.AVAILABLE else DesktopService.OFF
                if (actual == desired) {
                    adminOutcome = DesktopAdminOutcome.SUCCEEDED
                    adminMessage = if (op == DesktopOperation.START) "Unidade já ativa, conforme consulta."
                        else "Unidade já desligada, conforme consulta. Nenhuma alteração enviada."
                    return@launch
                }
                stopPending = op == DesktopOperation.STOP
                val reply = runInterruptible(Dispatchers.IO) { query(current, op) }
                if (id != serviceEpoch) return@launch
                if (reply.status != 0) {
                    // Recheck even on denial: failure is not evidence that the unit stopped.
                    val afterAccess = readAdministration(current)
                    val afterState = readService(current)
                    if (id != serviceEpoch) return@launch
                    administration = afterAccess; service = afterState
                    adminOutcome = if (afterAccess.access != DesktopAdminAccess.READY) unavailableOutcome(afterAccess.access)
                        else DesktopAdminOutcome.FAILED
                    adminMessage = if (afterAccess.access != DesktopAdminAccess.READY) afterAccess.message
                        else "Solicitação administrativa falhou. O estado da unidade foi consultado novamente."
                    return@launch
                }
                adminMessage = if (op == DesktopOperation.START) "Início solicitado; aguardando confirmação do Fedora…"
                    else "Encerramento solicitado; aguardando confirmação do Fedora…"
                // A successful --no-block only schedules the job. It is not completion.
                withTimeout(60_000) {
                    while (true) {
                        val confirmed = readService(current)
                        if (id != serviceEpoch) return@withTimeout
                        service = confirmed
                        if (confirmed == desired) {
                            if (op == DesktopOperation.STOP) disconnect()
                            adminOutcome = DesktopAdminOutcome.SUCCEEDED
                            adminMessage = if (op == DesktopOperation.STOP) "Desktop encerrado: unidade desligada confirmada no Fedora."
                                else "Desktop iniciado: unidade ativa confirmada no Fedora. Use Conectar."
                            break
                        }
                        if (confirmed == DesktopService.ERROR) error("status unavailable")
                        delay(pollDelayMs)
                    }
                }
            } catch (e: CancellationException) {
                if (e is TimeoutCancellationException && id == serviceEpoch) {
                    val afterState = readService(current)
                    if (id == serviceEpoch) {
                        service = afterState; adminOutcome = DesktopAdminOutcome.UNCERTAIN
                        adminMessage = "Prazo de confirmação excedido. Não repita automaticamente; atualize o estado da unidade."
                    }
                } else throw e
            } catch (_: Exception) {
                if (id == serviceEpoch) {
                    val afterState = readService(current)
                    val afterAccess = readAdministration(current)
                    if (id == serviceEpoch) {
                        service = afterState; administration = afterAccess
                        adminOutcome = DesktopAdminOutcome.UNCERTAIN
                        adminMessage = "Resultado administrativo não confirmado. O estado foi consultado; atualize antes de repetir."
                    }
                }
            } finally {
                if (id == serviceEpoch) { busy = false; changing = false; stopPending = false }
            }
        }
    }
    fun connect(password: CharArray) {
        val current = connection
        if (current == null || !visible || linkJob != null || changing) { password.fill('\u0000'); return }
        val id = ++epoch
        serviceEpoch++; serviceJob?.cancel(); serviceJob = null; busy = false
        diagnostics = DesktopTrace().also { it.step(DesktopStep.SCREEN, true); it.step(DesktopStep.SSH, true) }
        val trace = diagnostics
        presented = false; transform = DesktopTransform(); pointerTouched = false; pointerX = 0; pointerY = 0
        connectionStartedNs = System.nanoTime(); surfaceStartedNs = connectionStartedNs
        handoffStartedNs = 0; connectionFirstFrameMs = null; configurationRecoveryMs = null; surfacePresentations = 0
        link = DesktopLink.CONNECTING; frames = 0; resolution = ""; message = "Conectando por SSH ao display :1…"
        val client = try { makePeer() } catch (_: Exception) {
            password.fill('\u0000'); trace.fail(DesktopFailure.PROTOCOL)
            link = DesktopLink.ERROR; message = "Não foi possível preparar o cliente VNC. Tente novamente ou volte."
            return
        }
        peer = client
        client.observeProgress(trace::step)
        val queue = DesktopInputBuffer(); events = queue
        val began = java.util.concurrent.atomic.AtomicBoolean(false)
        val task = scope.launch(start = CoroutineStart.LAZY) {
            began.set(true)
            var lease: DesktopTunnel? = null
            try {
                // Keep ownership in the worker so cancellation after acquire cannot leak the lease.
                withContext(Dispatchers.IO) {
                    trace.step(DesktopStep.TUNNEL, false)
                    lease = tunnel(current)
                    trace.step(DesktopStep.TUNNEL, true)
                    trace.step(DesktopStep.TCP, false)
                    try { client.connect(lease!!.port, password) }
                    finally { password.fill('\u0000') }
                }
                if (id != epoch) return@launch
                link = DesktopLink.CONNECTED
                message = "VNC autenticado. Aguardando primeiro quadro…"
                coroutineScope {
                    val writer = launch(Dispatchers.IO) {
                        try { queue.consume(client) } finally { client.close() }
                    }
                    try {
                        withContext(Dispatchers.IO) {
                            while (isActive) client.read { frame ->
                                // Render into a reusable bitmap on this worker. UI callbacks only invalidate.
                                trace.step(DesktopStep.FRAME_READY, true)
                                trace.step(DesktopStep.RENDER, false)
                                synchronized(renderLock) {
                                    if (id == epoch) {
                                        latestFrame = frame
                                        try { render?.invoke(frame) }
                                        catch (_: Exception) { throw DesktopRenderFailure() }
                                    }
                                }
                                scope.launch {
                                    if (id == epoch) {
                                        frames++
                                        synchronized(frame) { framebufferWidth = frame.width; framebufferHeight = frame.height }
                                        if (!pointerTouched && interaction == DesktopInteraction.TRACKPAD) {
                                            pointerX = framebufferWidth / 2; pointerY = framebufferHeight / 2
                                        } else {
                                            pointerX = pointerX.coerceIn(0, framebufferWidth - 1); pointerY = pointerY.coerceIn(0, framebufferHeight - 1)
                                        }
                                        resolution = "${framebufferWidth}×${framebufferHeight}"
                                        message = "Desktop conectado · $resolution · $frames atualizações" }
                                }
                            }
                        }
                    } finally { writer.cancel(); queue.close(); client.close() }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (id == epoch) {
                    retiring = true // Disable Retry before publishing ERROR; cleanup still owns the lease.
                    if (stopPending) {
                        link = DesktopLink.DISCONNECTED
                        message = "Conexão gráfica interrompida durante a solicitação; aguardando estado remoto."
                    } else {
                        link = DesktopLink.ERROR
                        val failure = desktopFailure(e, trace.state.current)
                        trace.fail(failure)
                        message = "${failure.label}. Última etapa concluída: ${trace.state.lastCompleted?.label ?: "entrada"}. Tente novamente ou volte ao My-CT."
                    }
                }
            } finally {
                if (id == epoch) retiring = true
                password.fill('\u0000'); queue.close(); client.close()
                withContext(NonCancellable + Dispatchers.IO) { lease?.close(); client.dispose() }
                if (id == epoch) {
                    completedInputStats = queue.stats()
                    peer = null; events = null; clearModifiers()
                    synchronized(renderLock) { latestFrame = null; clearImage?.invoke() }
                }
            }
        }
        linkJob = task
        task.invokeOnCompletion {
            password.fill('\u0000')
            if (!began.get()) { queue.close(); client.close(); client.dispose() }
            scope.launch {
                if (linkJob === task) { linkJob = null; retiring = false }
            }
        }
        task.start()
    }
    fun disconnect() {
        handoffJob?.cancel(); handoffJob = null; handoffStartedNs = 0
        epoch++
        events?.let { completedInputStats = it.stats(); it.close() }; events = null
        peer?.close(); peer = null // Unblock reader/writer before cancelling their coroutine.
        if (linkJob != null) { retiring = true; linkJob?.cancel() }
        // A callback already copying pixels must finish before the image is discarded.
        // No network work is allowed in render callbacks.
        synchronized(renderLock) { latestFrame = null; clearImage?.invoke() }
        clearModifiers(); drag = false; presented = false
        link = DesktopLink.DISCONNECTED
        if (ssh) message = "Visualização desconectada. Aplicativos do Fedora permanecem abertos."
    }
    fun framePresented(token: Int, owner: Any? = null) {
        if (token != epoch || link != DesktopLink.CONNECTED || presented || owner != null && !ownsSurface(owner)) return
        presented = true
        surfacePresentations++
        val now = System.nanoTime()
        surfaceRecoveryMs = ((now - surfaceStartedNs) / 1_000_000).coerceAtLeast(0)
        if (connectionFirstFrameMs == null && connectionStartedNs != 0L) connectionFirstFrameMs = (now - connectionStartedNs) / 1_000_000
        if (handoffStartedNs != 0L) { configurationRecoveryMs = (now - handoffStartedNs) / 1_000_000; handoffStartedNs = 0 }
        diagnostics.step(DesktopStep.RENDER, true)
    }
    fun renderingFailed(token: Int, owner: Any? = null) {
        if (token != epoch || link != DesktopLink.CONNECTED || owner != null && !ownsSurface(owner)) return
        val trace = diagnostics
        disconnect()
        trace.fail(DesktopFailure.RENDER); link = DesktopLink.ERROR
        message = "Falha de renderização. Reconecte ou volte ao My-CT."
    }
    private fun enqueue(event: (DesktopPeer) -> Unit) {
        if (link != DesktopLink.CONNECTED) return
        if (events?.action(event) != true) inputOverflow()
    }
    private fun inputOverflow() {
        inputFailures++
        disconnect(); link = DesktopLink.ERROR; message = "Fila de entrada cheia; reconecte para evitar teclas presas."
    }
    fun pointer(x: Int, y: Int, mask: Int = 0) {
        pointerTouched = true; pointerX = x; pointerY = y; enqueue { it.pointer(x, y, mask) }
    }
    fun movePointer(x: Int, y: Int, mask: Int = 0) {
        if (link != DesktopLink.CONNECTED || x == pointerX && y == pointerY) return
        pointerTouched = true; pointerX = x; pointerY = y
        if (events?.motion(x, y, mask) != true) inputOverflow()
    }
    fun click(button: Int = 1) { val x = pointerX; val y = pointerY; enqueue { it.pointer(x, y, button); it.pointer(x, y, 0) } }
    fun scroll(up: Boolean) = click(if (up) 8 else 16)
    fun changeDrag(value: Boolean) { releaseInput(); drag = value }
    fun toggleModifier(sym: Int) {
        when (sym) { DesktopKeys.CTRL -> ctrl = !ctrl; DesktopKeys.ALT -> alt = !alt; DesktopKeys.SHIFT -> shift = !shift }
    }
    fun text(value: String) {
        val keys = DesktopKeys.text(value.take(4096))
        val mods = modifiers(ctrl, alt, shift)
        enqueue { p -> keys.forEach { sym -> chord(p, sym, mods) } }
    }
    fun press(sym: Int, control: Boolean = ctrl, alternate: Boolean = alt, shifted: Boolean = shift) {
        val mods = modifiers(control, alternate, shifted)
        enqueue { p -> chord(p, sym, mods) }
    }
    /** One FIFO event keeps an IME correction atomic with respect to pointer/key events. */
    fun replaceTypedTail(backspaces: Int, value: String) {
        val keys = DesktopKeys.text(value.take(4096))
        val mods = modifiers(ctrl, alt, shift)
        enqueue { p ->
            repeat(backspaces.coerceIn(0, 1024)) { chord(p, DesktopKeys.BACKSPACE, emptyList()) }
            keys.forEach { chord(p, it, mods) }
        }
    }
    fun releasePointer() {
        inputGeneration++
        val x = pointerX; val y = pointerY
        enqueue { it.pointer(x, y, 0) }
    }
    private fun modifiers(control: Boolean, alternate: Boolean, shifted: Boolean) =
        listOfNotNull(DesktopKeys.CTRL.takeIf { control }, DesktopKeys.ALT.takeIf { alternate }, DesktopKeys.SHIFT.takeIf { shifted })
    private fun chord(p: DesktopPeer, sym: Int, mods: List<Int>) {
        try { mods.forEach { p.key(it, true) }; p.key(sym, true); p.key(sym, false) }
        finally { mods.asReversed().forEach { p.key(it, false) } }
    }
    fun releaseInput() {
        inputGeneration++
        clearModifiers()
        val x = pointerX; val y = pointerY
        enqueue { p -> p.pointer(x, y, 0); listOf(DesktopKeys.CTRL, DesktopKeys.ALT, DesktopKeys.SHIFT).forEach { p.key(it, false) } }
    }
    private fun clearModifiers() { ctrl = false; alt = false; shift = false }
}
