package dev.myct

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.*

/** User-started SFTP only. No replay, credential/URI extras, boot receiver or sticky restart. */
class SftpTransferService : Service() {
    private val files get() = (application as MyCtApplication).terminal.files
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var updates: Job? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) { files.cancel(); return START_NOT_STICKY }
        if (intent?.action != START || !files.busy || files.status.name.isEmpty()) {
            stopSelf(); return START_NOT_STICKY
        }
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Transferência SFTP", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Transferência iniciada por você, com progresso e cancelamento."
                setShowBadge(false)
            })
            check(manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE)
            val notification = notice()
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(ID, notification)
            files.serviceStarted()
            files.onTransferChanged = { if (!files.busy || files.status.name.isEmpty()) finish() }
            updates?.cancel()
            updates = scope.launch {
                while (isActive) {
                    delay(1000) // Notification updates bounded to 1 Hz; never filenames/paths.
                    if (!files.busy || files.status.name.isEmpty()) { finish(); break }
                    if (runCatching { manager.notify(ID, notice()) }.isFailure) {
                        files.serviceStopped(); finish(); break
                    }
                }
            }
        } catch (_: Exception) { files.serviceStopped(); finish() }
        return START_NOT_STICKY
    }
    private fun notice(): Notification {
        val status = files.status
        val reopen = PendingIntent.getActivity(this, 20, Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 21, Intent(this, SftpTransferService::class.java).setAction(CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val total = status.total
        val percent = if (total != null && total > 0) (status.bytes.toDouble() / total * 100).toInt().coerceIn(0, 100) else 0
        val builder = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_terminal)
            .setContentTitle(if (status.phase == FilePhase.CANCELLING) "My-CT · cancelando SFTP" else "My-CT · transferência SFTP")
            .setContentText("${formatFileSize(status.bytes)} / ${total?.let(::formatFileSize) ?: "tamanho desconhecido"}")
            .setProgress(100, percent, total == null || total <= 0)
            .setContentIntent(reopen).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS).setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null, "Cancelar", cancel).build())
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return builder.build()
    }
    private fun finish() {
        updates?.cancel(); updates = null
        files.serviceStopped(cancelActive = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        files.serviceStopped() // Cancel channel; never disconnect the authenticated Session here.
        finish() // Must stop promptly; do not wait for a possibly blocked provider.
    }
    override fun onDestroy() {
        files.onTransferChanged = null
        files.serviceStopped()
        scope.cancel()
        super.onDestroy()
    }
    companion object {
        const val START = "dev.myct.sftp.START"
        const val CANCEL = "dev.myct.sftp.CANCEL"
        private const val CHANNEL = "sftp-transfer"
        private const val ID = 11
    }
}
