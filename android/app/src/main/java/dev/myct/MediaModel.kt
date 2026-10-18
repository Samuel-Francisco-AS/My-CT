package dev.myct

import androidx.compose.runtime.*
import kotlinx.coroutines.*

/** Reuses verified SSH; leaves transmitted mutations untouched when the screen is closed. */
internal class MediaModel internal constructor(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val execute: (SshConnection, MediaAction) -> String = { ssh, action -> ssh.media(action) }
) {
    private var connection: SshConnection? = null
    private var generation = 0
    private var job: Job? = null
    var connected by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    val actionsEnabled get() = connected && !busy && !stale
    var stale by mutableStateOf(false)
        private set
    var preview by mutableStateOf<MediaPreview?>(null)
        private set
    var entries by mutableStateOf(emptyList<MediaEntry>())
        private set
    var message by mutableStateOf("Conecte ao Fedora pelo Terminal.")
        private set

    fun attach(ssh: SshConnection?) {
        generation++
        job?.cancel(); job = null
        connection = ssh
        connected = ssh != null
        busy = false; stale = false
        preview = null; entries = emptyList()
        message = if (connected) "Consulte a biblioteca do Fedora." else "SSH desconectado."
    }

    fun refresh() = operate(MediaAction.Scan)
    fun sync() = operate(MediaAction.Sync)
    fun approveSeries(id: String, title: String, season: Int, episode: Int) =
        operate(MediaAction.ApproveSeries(id, title, season, episode))
    fun approveMovie(id: String, title: String, year: Int) =
        operate(MediaAction.ApproveMovie(id, title, year))
    fun skip(id: String) = operate(MediaAction.Skip(id))
    fun remove(path: String) = operate(MediaAction.Remove(path))

    private fun operate(action: MediaAction) {
        val ssh = connection ?: return
        if (busy) return
        val epoch = ++generation
        busy = true
        job = scope.launch {
            try {
                if (action != MediaAction.Scan) {
                    // A timeout cannot prove whether the command applied; never retry automatically.
                    runInterruptible(Dispatchers.IO) { execute(ssh, action) }
                }
                val nextPreview = parseMediaPreview(runInterruptible(Dispatchers.IO) { execute(ssh, MediaAction.Scan) })
                val nextEntries = parseMediaEntries(runInterruptible(Dispatchers.IO) { execute(ssh, MediaAction.List) })
                if (epoch == generation) {
                    preview = nextPreview
                    entries = nextEntries
                    stale = false
                    message = when (action) {
                        MediaAction.Scan -> "Prévia atualizada. Nenhuma importação foi realizada."
                        MediaAction.Sync -> "Importação solicitada. Confira os resultados e pendências."
                        else -> "Comando aceito pelo Fedora. Biblioteca consultada novamente."
                    }
                }
            } catch (_: CancellationException) {
            } catch (e: DownloadFailure) {
                if (epoch == generation) { stale = true; message = e.safeMessage }
            } catch (_: Exception) {
                if (epoch == generation) {
                    stale = true
                    message = "Não foi possível confirmar o resultado. Atualize antes de repetir qualquer ação."
                }
            } finally {
                if (epoch == generation) busy = false
            }
        }
    }
}
