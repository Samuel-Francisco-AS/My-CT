package dev.myct

import android.app.Application
import android.content.Intent
import android.app.NotificationManager
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.runtime.*
import androidx.core.content.edit
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpException
import kotlinx.coroutines.*
import java.io.IOException

// State survives Activity recreation, never persisted as credentials or replayed after reconnect.
enum class FilePhase { IDLE, LISTING, TRANSFERRING, CANCELLING, COMPLETE, CANCELLED, ERROR }
data class FileStatus(val phase: FilePhase = FilePhase.IDLE, val name: String = "", val bytes: Long = 0,
                      val total: Long? = null, val message: String = "Abra Arquivos para listar o diretório inicial.") {
    val busy get() = phase == FilePhase.LISTING || phase == FilePhase.TRANSFERRING || phase == FilePhase.CANCELLING
}
data class UploadChoice(val uri: Uri, val directory: String, val epoch: Int, val name: String, val size: Long?)
data class DownloadChoice(val path: String, val entry: RemoteEntry, val epoch: Int)

class FilesModel(private val app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var connection: SshConnection? = null
    private var epoch = 0
    private var control by mutableStateOf<TransferControl?>(null)
    val busy get() = control != null
    var status by mutableStateOf(FileStatus())
        private set
    var listing by mutableStateOf<RemoteListing?>(null)
        private set
    var uploadChoice by mutableStateOf<UploadChoice?>(null)
        private set
    var downloadChoice by mutableStateOf<DownloadChoice?>(null)
        private set
    var uploadDestination: Pair<Int, String>? = null
        private set
    var completedDownload by mutableStateOf<Uri?>(null)
        private set
    var recoveryNotice by mutableStateOf("")
        private set
    fun dismissRecoveryNotice() {
        recoveryNotice = ""
        if (!busy) transferPrefs.edit { putBoolean("unfinished", false) }
    }

    fun attach(value: SshConnection?) {
        val old = control
        if (old != null && status.name.isNotEmpty()) recoveryNotice =
            "Transferência interrompida pela mudança de conexão. Confira o destino antes de repetir: pode haver documento SAF parcial, temporário remoto .myct-upload-* ou upload já publicado. Nada será repetido automaticamente."
        control = null
        epoch++
        connection = value
        listing = null
        uploadChoice = null; downloadChoice = null; uploadDestination = null; completedDownload = null
        status = FileStatus(message = if (value == null) "SSH desconectado. Reconecte manualmente." else "Abra Arquivos para listar o diretório inicial.")
        if (old != null) scope.launch(Dispatchers.IO) { old.cancel() }
    }
    fun cancel() {
        val token = control ?: return
        if (status.phase == FilePhase.COMPLETE) return // Publication/close already completed.
        status = status.copy(phase = FilePhase.CANCELLING, message = "Cancelando; aguarde o fechamento dos streams…")
        scope.launch(Dispatchers.IO) { token.cancel() }
    }
    var transferServiceActive by mutableStateOf(false)
        internal set
    var onTransferChanged: (() -> Unit)? = null
    private var serviceReady: CompletableDeferred<Boolean>? = null
    private var background = false
    val transferKeepsConnection get() = transferServiceActive || serviceReady != null
    fun onForeground() { background = false }
    private val transferPrefs = app.getSharedPreferences("sftp-recovery", 0)
    init {
        if (transferPrefs.getBoolean("unfinished", false)) recoveryNotice =
            "O processo terminou durante uma transferência. Pode haver documento PARCIAL no Android ou temporário/upload no Fedora. Confira o destino antes de repetir; não há retomada automática."
    }
    fun serviceStarted() { transferServiceActive = true; serviceReady?.complete(true) }
    fun serviceStopped(cancelActive: Boolean = true) {
        val wasActive = transferServiceActive
        transferServiceActive = false
        serviceReady?.complete(false)
        if (cancelActive && wasActive && busy && status.name.isNotEmpty()) cancel()
    }
    private suspend fun startTransferService(): Boolean {
        val manager = app.getSystemService(NotificationManager::class.java)
        if ((Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) ||
            !manager.areNotificationsEnabled()) return false
        val ready = CompletableDeferred<Boolean>()
        serviceReady = ready
        return try {
            app.startForegroundService(Intent(app, SftpTransferService::class.java).setAction(SftpTransferService.START))
            withTimeoutOrNull(5000) { ready.await() } == true
        } catch (_: Exception) { false }
        finally { serviceReady = null }
    }
    fun onBackground() {
        background = true
        if (busy && !transferKeepsConnection) cancel()
    }
    fun load(path: String? = listing?.path) {
        operate(FileStatus(FilePhase.LISTING, message = "Carregando…")) { store, token, id, finish ->
            token.check()
            val canonical = store.canonical(path ?: store.home())
            require(store.stat(canonical)?.kind == RemoteKind.DIRECTORY)
            val result = store.list(canonical)
            token.check()
            finish {
                if (epoch == id) {
                    listing = result
                    status = FileStatus(message = if (result.limited) "Lista limitada a 2.000 itens ou contém nomes não suportados."
                        else if (result.entries.isEmpty()) "Diretório vazio." else "${result.entries.size} itens")
                }
            }
        }
    }
    fun beginUploadSelection(): Boolean {
        val directory = listing?.path ?: return false
        if (busy || connection == null) return false
        uploadDestination = epoch to directory
        return true
    }
    fun pickedUpload(uri: Uri?) {
        val destination = uploadDestination
        uploadDestination = null
        if (uri == null) return
        if (destination == null || destination.first != epoch || connection == null || uri.scheme != "content") {
            status = FileStatus(FilePhase.ERROR, message = "Seleção expirada. Escolha o arquivo novamente nesta conexão."); return
        }
        operate(FileStatus(FilePhase.LISTING, message = "Lendo metadados do provedor Android…")) { _, token, id, finish ->
            var name = "arquivo"
            var size: Long? = null
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val ni = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0 && !cursor.isNull(ni)) name = cursor.getString(ni).take(512)
                    if (si >= 0 && !cursor.isNull(si)) size = cursor.getLong(si).takeIf { it >= 0 }
                }
            }
            token.check()
            finish { if (id == epoch) {
                uploadChoice = UploadChoice(uri, destination.second, id, name, size)
                status = FileStatus(message = "Confirme o nome de destino. Arquivos existentes não serão substituídos.")
            } }
        }
    }
    fun dismissUpload() { uploadChoice = null }
    fun upload(name: String) {
        val choice = uploadChoice ?: return
        if (choice.epoch != epoch || busy) return
        if (runCatching { RemotePath.name(name) }.isFailure) {
            status = FileStatus(FilePhase.ERROR, message = "Nome inválido: use até 255 bytes UTF-8, sem barra, controles, . ou ..."); return
        }
        uploadChoice = null
        completedDownload = null
        operate(FileStatus(FilePhase.TRANSFERRING, name, total = choice.size, message = "Enviando…")) { store, token, id, finish ->
            val result = (app.contentResolver.openInputStream(choice.uri) ?: throw IOException()).use { source ->
                SftpTransfers.upload(store, choice.directory, name, source, choice.size, token, progress(token))
            }
            finish { if (id == epoch) {
                status = FileStatus(FilePhase.COMPLETE, name, result.bytes, result.bytes, "Upload concluído: ${formatFileSize(result.bytes)} (${result.bytes} bytes); tamanho remoto conferido. Atualize a lista.")
            } }
        }
    }
    fun selectDownload(entry: RemoteEntry) {
        val directory = listing?.path ?: return
        if (!busy && connection != null && entry.kind == RemoteKind.FILE)
            downloadChoice = DownloadChoice(RemotePath.child(directory, entry.name), entry, epoch)
    }
    fun dismissDownload() { downloadChoice = null }
    fun pickedDownload(uri: Uri?) {
        val choice = downloadChoice
        downloadChoice = null
        if (uri == null) return
        if (choice == null || choice.epoch != epoch || connection == null || uri.scheme != "content") {
            status = FileStatus(FilePhase.ERROR, message = "Destino criado, mas seleção expirada; pode haver documento vazio no provedor. Escolha novamente."); return
        }
        completedDownload = null
        operate(FileStatus(FilePhase.TRANSFERRING, choice.entry.name, total = choice.entry.size,
            message = "Baixando; o destino SAF pode ficar parcial até a conclusão."), onFailure = {
                // Also runs if opening SFTP failed, before the provider stream was opened.
                val removed = runCatching { DocumentsContract.deleteDocument(app.contentResolver, uri) }.getOrDefault(false)
                if (removed) "Documento incompleto removido do provedor."
                else "O provedor pode manter um arquivo PARCIAL no destino escolhido; remova-o manualmente."
            }) { store, token, id, finish ->
            val result = (app.contentResolver.openOutputStream(uri, "wt") ?: throw IOException()).use { target ->
                SftpTransfers.download(store, choice.path, choice.entry, target, token, progress(token))
            }
            // SIZE is optional. A provider query failure is not evidence of corruption.
            val sizeCheck = completedDocumentSize(result.bytes) {
                app.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getLong(0).takeIf { it >= 0 } else null
                }
            }
            token.check()
            finish { if (id == epoch) {
                completedDownload = uri
                status = FileStatus(FilePhase.COMPLETE, choice.entry.name, result.bytes, result.bytes,
                    "Download concluído: ${formatFileSize(result.bytes)} (${result.bytes} bytes). Contagem e origem conferidas. $sizeCheck Hash remoto indisponível; SHA-256 local: ${result.sha256}")
            } }
        }
    }
    private fun progress(token: TransferControl): (Long) -> Unit = { bytes ->
        token.check()
        token.bytesTransferred.set(bytes) // One slot per operation; no queued UI updates.
    }
    private fun operate(initial: FileStatus, onFailure: (suspend () -> String)? = null, block: suspend (RemoteStore, TransferControl, Int, (() -> Unit) -> Unit) -> Unit) {
        val ssh = connection ?: run { status = FileStatus(FilePhase.ERROR, message = "SSH desconectado."); return }
        if (busy) return // No unbounded job/transfer queue.
        val id = epoch
        val token = TransferControl()
        control = token
        status = initial
        scope.launch {
            val ticker = launch {
                while (isActive) {
                    delay(100)
                    if (epoch == id && control === token && status.phase == FilePhase.TRANSFERRING)
                        status = status.copy(bytes = token.bytesTransferred.get())
                }
            }
            var completion: (() -> Unit)? = null
            val transfer = initial.phase == FilePhase.TRANSFERRING
            try {
                if (transfer) {
                    val active = startTransferService() // Request while the initiating UI is visible.
                    withContext(Dispatchers.IO) { transferPrefs.edit(commit = true) { putBoolean("unfinished", true) } }
                    if (id != epoch || (background && !active)) { token.cancel(); token.check() }
                    status = status.copy(message = status.message + if (active)
                        " Continua em segundo plano com notificação; sem retomada automática."
                        else " Serviço indisponível: mantenha o app visível. Ative notificações para a próxima transferência.")
                }
                withContext(Dispatchers.IO) {
                    val store = ssh.openFiles(token)
                    store.use { block(it, token, id) { action -> completion = action } }
                }
                completion?.invoke() // Only after provider streams AND SFTP channel have closed.
            } catch (error: Exception) {
                val cleanupNote = withContext(Dispatchers.IO) { onFailure?.invoke().orEmpty() }
                if (id == epoch) status = status.copy(phase = if (token.cancelled) FilePhase.CANCELLED else FilePhase.ERROR,
                    message = (if (token.cancelled) "Operação cancelada. " else failure(error)) + cleanupNote +
                        if (onFailure == null && initial.phase == FilePhase.TRANSFERRING)
                            " Confira o destino antes de repetir. Temporário .myct-upload-* pode permanecer se a conexão caiu." else "")
            } finally {
                ticker.cancel()
                if (id == epoch && control === token) {
                    control = null
                    if (status.busy) status = status.copy(phase = FilePhase.IDLE)
                    if (transfer) transferPrefs.edit { putBoolean("unfinished", false) }
                }
                if (transfer) onTransferChanged?.invoke()
            }
        }
    }
    private fun failure(error: Exception): String = when {
        error is NameConflict -> "Já existe um item com esse nome. Nada foi sobrescrito; selecione novamente e use outro nome. "
        error is SafePublishUnavailable -> "Servidor sem publicação segura hardlink@openssh.com; upload recusado sem sobrescrever. "
        error is IntegrityFailure -> "Tamanho ou arquivo de origem mudou; integridade não confirmada. "
        error is IllegalArgumentException -> "Caminho ou tipo de item não suportado. Escolha um diretório para navegar ou um arquivo regular para baixar. "
        error is SftpException && error.id == ChannelSftp.SSH_FX_PERMISSION_DENIED -> "Acesso negado pelas permissões do usuário Linux. "
        error is SftpException && error.id == ChannelSftp.SSH_FX_NO_SUCH_FILE -> "Arquivo ou diretório não encontrado. Atualize a listagem. "
        error is SftpException && (error.id == ChannelSftp.SSH_FX_NO_CONNECTION || error.id == ChannelSftp.SSH_FX_CONNECTION_LOST) -> "Canal SFTP desconectado. Verifique a conexão e tente novamente manualmente. "
        error is SecurityException -> "O provedor Android recusou acesso. Selecione o arquivo novamente. "
        else -> "Falha SFTP/provedor ou conexão indisponível; verifique espaço e permissões, e tente manualmente. "
    }
}
