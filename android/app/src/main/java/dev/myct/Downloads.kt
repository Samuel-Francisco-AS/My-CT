package dev.myct

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.URI

internal val downloadId = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
internal const val DOWNLOAD_INSTALL = "cd ~/Projetos/My-CT\nmake -C server myct-download\ninstall -Dm0700 server/myct-download \"\$HOME/.local/bin/myct-download\""

enum class DownloadState(val label: String) {
    starting("Iniciando"), running("Baixando"), completed("Concluído"), failed("Falhou"),
    canceled("Cancelado; parcial preservado"), interrupted("Interrompido")
}
data class ServerDownload(val id: String, val state: DownloadState, val bytes: Long, val total: Long?,
                          val path: String, val message: String) {
    val active get() = state == DownloadState.starting || state == DownloadState.running
    val progress: Float? get() = total?.takeIf { it > 0 }?.let { (bytes.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }
    val directory get() = path.substringBeforeLast('/')
    val name get() = path.substringAfterLast('/').let { if (state != DownloadState.completed) it.removeSuffix(".part") else it }
}
internal fun parseDownloads(output: String): List<ServerDownload> {
    if (output.isBlank()) return emptyList()
    val rows = output.trimEnd('\n').split("\n\n")
    val result = rows.map { row ->
        val lines = row.split('\n')
        val keys = listOf("id", "state", "bytes", "total", "path", "message")
        require(lines.size == keys.size)
        val values = lines.mapIndexed { i, line ->
            require(line.startsWith(keys[i] + ": "))
            line.substring(keys[i].length + 2).also { require(it.none { c -> c.isISOControl() }) }
        }
        val (id, state, bytes, total, path) = values
        require(downloadId.matches(id))
        val count = bytes.toLong(); val size = total.toLong()
        require(count >= 0 && size >= -1)
        val directory = path.substringBeforeLast('/')
        require(path.startsWith('/') && directory.endsWith("/Downloads/My-CT/$id"))
        require(path.split('/').none { it == "." || it == ".." } && path.substringAfterLast('/').isNotEmpty())
        ServerDownload(id, DownloadState.valueOf(state), count, size.takeIf { it >= 0 }, path, values[5])
    }
    require(result.map { it.id }.distinct().size == result.size)
    return result.sortedByDescending { it.id }
}

internal sealed interface DownloadAction {
    data object List : DownloadAction
    data class Start(val url: String, val name: String) : DownloadAction
    data class Cancel(val id: String) : DownloadAction
}
// SSH exec uses a shell on the server: quote every argument, never interpolate raw user text.
internal fun downloadCommand(action: DownloadAction): String {
    fun quote(value: String): String {
        require(value.length <= 8192 && value.none { it.isISOControl() })
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }
    val args = when (action) {
        DownloadAction.List -> "list"
        is DownloadAction.Cancel -> { require(downloadId.matches(action.id)); "cancel ${quote(action.id)}" }
        is DownloadAction.Start -> {
            require(URI(action.url).scheme?.lowercase() in setOf("http", "https"))
            // In this CLI, this filename would be interpreted as a privileged option.
            require(action.name != "--allow-private")
            "start ${quote(action.url)}" + if (action.name.isEmpty()) "" else " ${quote(action.name)}"
        }
    }
    return "if [ -x \"\$HOME/.local/bin/myct-download\" ]; then exec \"\$HOME/.local/bin/myct-download\" $args; else exit 127; fi"
}

internal class DownloadFailure(val safeMessage: String) : Exception(safeMessage)
internal interface DownloadExec : AutoCloseable {
    fun connect(command: String, output: OutputStream, error: OutputStream)
    val done: Boolean
    val exitStatus: Int
}
private class BoundedDownloadOutput : OutputStream() {
    private val data = ByteArrayOutputStream()
    var overflow = false
        private set
    @Synchronized override fun write(b: Int) { if (data.size() < 262144) data.write(b) else overflow = true }
    @Synchronized override fun write(b: ByteArray, off: Int, len: Int) {
        val count = minOf(len, 262144 - data.size())
        data.write(b, off, count)
        if (count < len) overflow = true
    }
    @Synchronized fun text(): String = data.toString("UTF-8")
    @Synchronized fun exceeded() = overflow
}
/** Bounded, interruptible exec; closing it never closes the shared SSH session or Terminal PTY. */
internal fun executeDownload(action: DownloadAction, open: () -> DownloadExec, timeoutMs: Long = 25000): String {
    return executeSshCommand(downloadCommand(action), open, timeoutMs) { status, reason ->
        if (status == 127) DownloadFailure("CLI ausente ou sem permissão. Instale no Fedora, sem downloads ativos:\n$DOWNLOAD_INSTALL")
        else DownloadFailure(when {
            reason.contains("Outro download") -> "Já existe uma transferência ativa; não há fila. Aguarde ou cancele."
            reason.contains("Nome inválido") -> "Nome inválido. Use até 120 letras ASCII, números, ponto, _ ou -, sem .part final."
            reason.contains("Linger não") -> "A CLI informou linger indisponível. Consulte a documentação MYCT-5A com o proprietário."
            action is DownloadAction.Start -> "Pedido recusado pela CLI. Confira URL HTTP/HTTPS pública, nome, DNS e conexão. Atualize antes de repetir."
            else -> "Falha ao consultar/cancelar pela CLI. Confira SSH e permissões; atualize antes de repetir."
        })
    }
}

internal fun executeSshCommand(command: String, open: () -> DownloadExec, timeoutMs: Long = 25000,
                               failure: (Int, String) -> DownloadFailure): String {
    val result = executeSshResult(command, open, timeoutMs)
    if (result.status != 0) throw failure(result.status, result.error)
    return result.output
}

internal data class SshExecResult(val output: String, val error: String, val status: Int)
/** Raw exit status is data: the AV contract returns informative JSON on exit 1. */
internal fun executeSshResult(command: String, open: () -> DownloadExec, timeoutMs: Long = 25000): SshExecResult {
    val output = BoundedDownloadOutput(); val error = BoundedDownloadOutput()
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    open().use { channel ->
        channel.connect(command, output, error)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            if (output.exceeded() || error.exceeded()) throw DownloadFailure("Resposta da CLI excedeu o limite de 256 KiB por saída.")
            if (System.nanoTime() >= deadline) throw DownloadFailure("Tempo limite SSH. Atualize para conferir o resultado antes de repetir.")
            if (channel.done) break
            Thread.sleep(25)
        }
        if (channel.exitStatus < 0) throw DownloadFailure("Canal SSH encerrou sem resultado remoto. Confira a conexão.")
        return SshExecResult(output.text(), error.text(), channel.exitStatus)
    }
}
