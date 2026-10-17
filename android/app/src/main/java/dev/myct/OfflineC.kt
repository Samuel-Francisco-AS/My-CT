package dev.myct

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

internal enum class CPhase(val label: String) {
    IDLE("Pronto"), COMPILING("Compilando C localmente…"), RUNNING("Executando · WebAssembly interpretado"),
    FINISHED("Encerrado"), COMPILE_ERROR("Erro de compilação"), FAILED("Falha local"),
    START_ERROR("Falha de inicialização"), NATIVE_FAILURE("Worker encerrado por sinal"),
    CANCELLED("Interrompido"), LIMIT("Limite atingido")
}
/** Worker compile has no user-defined exit status. Java reports signal deaths
 * as 128+signal; code alone says nothing about the underlying native cause. */
internal fun compileOutcome(code: Int): CPhase = when (code) {
    0 -> CPhase.FINISHED
    1 -> CPhase.COMPILE_ERROR
    142, 152, 153 -> CPhase.LIMIT // SIGALRM, SIGXCPU, SIGXFSZ
    in 129..159 -> CPhase.NATIVE_FAILURE
    else -> CPhase.START_ERROR
}
internal fun workerFailureMessage(code: Int): String {
    val signal = when (code) {
        132 -> "SIGILL (4)"; 133 -> "SIGTRAP (5)"; 134 -> "SIGABRT (6)"
        135 -> "SIGBUS (7)"; 136 -> "SIGFPE (8)"; 137 -> "SIGKILL (9)"
        139 -> "SIGSEGV (11)"; 142 -> "SIGALRM (14)"; 152 -> "SIGXCPU (24)"
        153 -> "SIGXFSZ (25)"; 159 -> "SIGSYS (31)"; else -> null
    }
    return if (signal != null) "Worker terminou com status $code, compatível com $signal. Consulte as etapas do diagnóstico."
        else if (code in 129..159) "Worker terminou com status $code, compatível com sinal ${code-128}. Consulte as etapas do diagnóstico."
        else "Worker terminou com status $code. Consulte o diagnóstico de inicialização."
}
internal class WorkerLaunchFailure(cause: java.io.IOException) : java.io.IOException(cause)

internal fun canCompileC(environment: IdeEnvironment, doc: IdeDocument?): Boolean =
    environment == IdeEnvironment.LOCAL && doc?.identity == LocalIdeModel.LOCAL_ID && doc.path.endsWith(".c") && !doc.uncertain

/** Saved source only. Re-read through L1A's path/symlink/size validation before use. */
internal fun captureC(workspace: LocalWorkspace, expected: EditSnapshot, artifacts: File): File {
    val actual = workspace.load(expected.path)
    if (actual.metadata != expected.metadata || !actual.bytes.contentEquals(expected.bytes)) throw EditConflict()
    Files.createDirectories(artifacts.toPath())
    val directory = File(artifacts, UUID.randomUUID().toString())
    check(directory.mkdir())
    File(directory, "origem.txt").writeText("Projetos locais/${expected.path}\nSHA-256: ${cSha(actual.bytes)}\n")
    // Fixed non-library module name: WCPL reserves names such as stdio.c.
    File(directory, "programa.c").writeBytes(actual.bytes)
    return directory
}
internal fun cSha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Every phase gets a fresh OS process. Never use a shell command, remote store,
 * executable workspace file, JIT or dlopen of generated code. */
internal class OfflineCModel(
    private val workspace: LocalWorkspace,
    private val artifacts: File,
    private val worker: File,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val compileTimeoutMs: Long = 30_000,
    private val runTimeoutMs: Long = 60_000
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val inputLock = Mutex()
    private var control: Control? = null
    var phase by mutableStateOf(CPhase.IDLE); private set
    var output by mutableStateOf(""); private set
    var source by mutableStateOf(""); private set
    var artifact by mutableStateOf<String?>(null); private set
    var exitCode by mutableStateOf<Int?>(null); private set
    var elapsedMs by mutableLongStateOf(0L); private set
    var consoleOpen by mutableStateOf(false)
    val active get() = phase == CPhase.COMPILING || phase == CPhase.RUNNING
    private var inputReady by mutableStateOf(false)
    val acceptsInput get() = phase == CPhase.RUNNING && inputReady

    private class Control {
        var process: Process? = null; private set
        var reason: CPhase? = null; private set
        @Synchronized fun attach(p: Process) { process = p; if (reason != null) p.destroyForcibly() }
        @Synchronized fun detach(p: Process) { if (process === p) process = null }
        @Synchronized fun stop(value: CPhase) { if (reason == null) reason = value; process?.destroyForcibly() }
        @Synchronized fun stopped() = reason
        @Synchronized fun current() = process
    }
    private fun append(value: String, token: Control) {
        if (output.length + value.length > MAX_OUTPUT) {
            output = (output + value).take(MAX_OUTPUT)
            token.stop(CPhase.LIMIT)
        } else output += value
    }
    fun start(environment: IdeEnvironment, doc: IdeDocument?) {
        if (active || !canCompileC(environment, doc) || doc == null) return
        consoleOpen = true
        if (doc.dirty) { phase = CPhase.FAILED; output = "Salve as alterações antes de compilar."; return }
        val expected = doc.snapshot.copy(bytes = doc.snapshot.bytes.copyOf())
        val token = Control(); control = token
        phase = CPhase.COMPILING; output = ""; exitCode = null; artifact = null; source = expected.path; elapsedMs = 0
        val began = System.nanoTime()
        scope.launch {
            try {
                val directory = withContext(io) { captureC(workspace, expected, artifacts) }
                if (token.stopped() == null) {
                    val wasm = File(directory, "programa.wasm") // fresh directory; no stale output possible
                    val compiled = command(listOf("compile", "programa.c", "programa.wasm"), directory, compileTimeoutMs, token)
                    if (token.stopped() == null && compiled == 0 && wasm.isFile) {
                        artifact = wasm.absolutePath
                        phase = CPhase.RUNNING
                        append("\n— C compilado · executando pelo WAMR embarcado —\n", token)
                        val result = command(listOf("run", "programa.wasm"), directory, runTimeoutMs, token)
                        if (token.stopped() == null) {
                            exitCode = result
                            phase = if (output.contains("[myct-wasm: encerrado;")) CPhase.FINISHED
                                else if (result in 129..159) compileOutcome(result) else CPhase.FAILED
                            if (phase == CPhase.NATIVE_FAILURE || phase == CPhase.LIMIT) append("\n${workerFailureMessage(result)}\n", token)
                        }
                    } else if (token.stopped() == null) {
                        exitCode = compiled
                        phase = if (compiled == 0) CPhase.FAILED else compileOutcome(compiled)
                        append("\n" + when (phase) {
                            CPhase.COMPILE_ERROR -> "O compilador recusou a fonte; confira o diagnóstico acima."
                            CPhase.NATIVE_FAILURE, CPhase.LIMIT -> workerFailureMessage(compiled)
                            else -> "Falha ao iniciar/concluir a compilação. " + workerFailureMessage(compiled)
                        } + " Nenhum programa foi executado.\n", token)
                    }
                }
            } catch (e: Exception) {
                if (token.stopped() == null) {
                    phase = if (e is WorkerLaunchFailure) CPhase.START_ERROR else CPhase.FAILED
                    append(when (e) {
                        is WorkerLaunchFailure -> "Não foi possível iniciar o worker instalado. Fonte preservada; confira o pacote Android.\n"
                        is EditConflict -> "O arquivo salvo mudou. Reabra e confira antes de compilar.\n"
                        is EditRejected -> e.explanation
                        else -> "Não foi possível compilar/executar localmente (${e.javaClass.simpleName}). Confira o pacote e o espaço disponível.\n"
                    }, token)
                }
            } finally {
                token.stopped()?.let { reason ->
                    phase = reason
                    val notice = if (reason == CPhase.CANCELLED) "Interrompido pelo usuário." else "Limite de tempo ou saída atingido."
                    output = output.take(MAX_OUTPUT) + "\n$notice\n"
                }
                elapsedMs = (System.nanoTime() - began) / 1_000_000
                inputReady = false
                control = null
            }
        }
    }
    private suspend fun command(args: List<String>, directory: File, timeout: Long, token: Control): Int = coroutineScope {
        val watchdog = launch { delay(timeout); token.stop(CPhase.LIMIT) }
        try {
            withContext(io) {
                if (token.stopped() != null) return@withContext -1
                val builder = ProcessBuilder(listOf(worker.absolutePath) + args).directory(directory).redirectErrorStream(true)
                builder.environment().remove("WCPL_INCLUDE_PATH"); builder.environment().remove("WCPL_LIBRARY_PATH")
                val p = try { builder.start() } catch (e: java.io.IOException) { throw WorkerLaunchFailure(e) }
                token.attach(p)
                withContext(dispatcher) { inputReady = args.first() == "run" && token.stopped() == null }
                try {
                    if (args.first() == "compile") p.outputStream.close()
                    p.inputStream.reader(Charsets.UTF_8).use { reader ->
                        val chars = CharArray(2048)
                        while (true) {
                            val count = reader.read(chars)
                            if (count < 0) break
                            val chunk = String(chars, 0, count)
                            withContext(dispatcher) { append(chunk, token) }
                        }
                    }
                    p.waitFor()
                } finally {
                    p.destroyForcibly()
                    runCatching { p.outputStream.close() }
                    token.detach(p)
                    withContext(dispatcher) { inputReady = false }
                }
            }
        } finally { watchdog.cancel() }
    }
    fun send(line: String) {
        if (!acceptsInput || line.length > 4096) return
        val token = control ?: return
        scope.launch(io) {
            inputLock.withLock {
                val p = token.current() ?: return@withLock
                try { p.outputStream.write((line + "\n").toByteArray(Charsets.UTF_8)); p.outputStream.flush() }
                catch (_: Exception) { /* Program may finish while input is submitted. */ }
            }
        }
    }
    fun endInput() {
        val token = control ?: return
        inputReady = false
        scope.launch(io) { inputLock.withLock { runCatching { token.current()?.outputStream?.close() } } }
    }
    fun cancel() { control?.stop(CPhase.CANCELLED) }
    fun close() { cancel(); scope.cancel() }
    companion object { const val MAX_OUTPUT = 65_536 }
}
