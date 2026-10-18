package dev.myct

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.*
import java.io.File

/** Process owner: no Activity, navigation or SSH lifetime controls this child. Main-thread API. */
internal class LocalTerminalModel(
    private val base: File,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val open: (String) -> PtyProcess = ::AndroidPty,
    private val post: (Runnable) -> Unit = { check(Handler(Looper.getMainLooper()).post(it)) }
) {
    enum class Phase { IDLE, STARTING, RUNNING, STOPPING, ENDED, ERROR }
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    var phase by mutableStateOf(Phase.IDLE); private set
    var message by mutableStateOf("Shell Android · disponível offline · inicie quando desejar."); private set
    var terminal by mutableStateOf<TerminalSession?>(null); private set
    var exitStatus by mutableStateOf<Int?>(null); private set
    var callbacks = SessionCallbacks(); private set
    val modifiers = TerminalModifiers()
    private var epoch = 0
    private var job: Job? = null
    private var process: PtyProcess? = null
    private var actions: TerminalActionQueue? = null
    private var workers: TerminalIo? = null
    val active get() = phase == Phase.STARTING || phase == Phase.RUNNING || phase == Phase.STOPPING
    val running get() = phase == Phase.RUNNING

    fun start() {
        if (active) return
        val id = ++epoch
        modifiers.clear(); exitStatus = null
        callbacks = SessionCallbacks()
        phase = Phase.STARTING; message = "Iniciando /system/bin/sh em PTY local…"
        val metrics = TerminalDiagnostics() // Does not share remote measurements.
        val queue = TerminalActionQueue(metrics)
        actions = queue
        val screen = TerminalSession(object : TerminalSession.Transport {
            override fun send(data: ByteArray) {
                if (id == epoch && running && !queue.write(data)) stop("Fila local excedida; sessão encerrada.")
            }
            override fun resize(columns: Int, rows: Int, widthPixels: Int, heightPixels: Int) {
                if (id == epoch && active && !queue.resize(columns, rows, widthPixels, heightPixels))
                    stop("Falha no resize local; inicie uma nova sessão.")
            }
        }, callbacks)
        terminal = screen
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var child: PtyProcess? = null
            var transport: TerminalIo? = null
            try {
                // Keep the acquired child in this coroutine even when stop cancels opening.
                withContext(io) {
                    val cwd = LocalWorkspace(base).terminalDirectory().absolutePath
                    child = open(cwd)
                }
                val current = checkNotNull(child)
                if (id != epoch) return@launch
                process = current
                val drained = CompletableDeferred<Unit>()
                val failure = CompletableDeferred<Exception>()
                val incoming = TerminalOutputQueue(metrics, post,
                    append = { bytes, count -> if (id == epoch) screen.append(bytes, count) },
                    finished = { drained.complete(Unit) },
                    failed = { failure.complete(it) })
                transport = TerminalIo(queue, incoming, current::read, current::write, current::resize,
                    failed = { failure.complete(it) })
                workers = transport
                phase = Phase.RUNNING; message = "/system/bin/sh · local · UID do My-CT"
                screen.requestPtyResize()
                transport.start()
                // A waiter is necessary: a background child may retain the slave after shell exit.
                coroutineScope {
                    val exited = async(io) { current.waitForExit() }
                    val monitor = launch {
                        val error = failure.await()
                        withContext(io) { current.stop() } // Wake waitpid and IO; no SSH interaction.
                        throw error
                    }
                    try {
                        val status = exited.await()
                        withTimeoutOrNull(500) { drained.await() }
                        if (id == epoch) {
                            exitStatus = status; phase = Phase.ENDED
                            message = "Shell local encerrado · status $status. Você pode iniciar outra sessão."
                        }
                    } finally { monitor.cancel() }
                }
            } catch (_: CancellationException) {
                // Explicit local stop already supplied its reason and invalidated old callbacks.
            } catch (_: LinkageError) {
                if (id == epoch) { phase = Phase.ERROR; message = "Biblioteca PTY indisponível para esta instalação/ABI. Reinstale a candidata compatível sem apagar dados." }
            } catch (_: Exception) {
                if (id == epoch) { phase = Phase.ERROR; message = "Falha ao iniciar ou acessar o PTY/processo local. Confira espaço e tente iniciar novamente." }
            } finally {
                transport?.close(); queue.close(); screen.finish()
                // Keep both the IO cleanup AND the return to main inside NonCancellable.
                // Otherwise prompt cancellation can skip the final STOPPING -> ENDED state.
                withContext(NonCancellable) {
                    withContext(io) {
                        child?.stop()
                        transport?.awaitStopped()
                        child?.close()
                    }
                    if (terminal === screen) {
                        process = null; actions = null; workers = null; job = null; modifiers.clear()
                        if (phase == Phase.STOPPING) phase = Phase.ENDED
                    }
                }
            }
        }
    }

    fun stop(reason: String = "Sessão local fechada. Você pode iniciar outra sessão.") {
        if (!active) return
        ++epoch
        phase = Phase.STOPPING; message = reason
        modifiers.clear(); terminal?.finish()
        workers?.close(); actions?.close()
        // Wake waitpid before cancelling a coroutine waiting on blocking native IO.
        process?.let { current -> scope.launch(io) { current.stop() } }
        job?.cancel()
    }
}
