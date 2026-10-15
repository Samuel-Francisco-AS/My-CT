package dev.myct

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.widget.Toast

/** Explicitly started while UI is visible, for an already-authenticated interactive SSH session. */
class SshForegroundService : Service() {
    private val model get() = (application as MyCtApplication).terminal
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == DISCONNECT) {
            finishConnection("Conexão e modo persistente encerrados pelo usuário.")
            return START_NOT_STICKY
        }
        if (intent?.action != ENABLE || model.phase != TerminalModel.Phase.CONNECTED || !notificationsAllowed()) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Conexão SSH", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Sessão SSH iniciada pelo usuário; use Desconectar para encerrar."
                setShowBadge(false)
            })
            if (manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) {
                Toast.makeText(this, "Ative o canal de notificação SSH para usar o modo persistente.", Toast.LENGTH_LONG).show()
                stopSelf(); return START_NOT_STICKY
            }
            val reopen = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(this, 1, Intent(this, SshForegroundService::class.java).setAction(DISCONNECT),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val publicNotice = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_terminal)
                .setContentTitle("My-CT · conexão SSH ativa").build()
            val notice = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_terminal)
                .setContentTitle("My-CT · conexão SSH ativa").setContentText("${model.user}@${model.host}:${model.port}")
                .setCategory(Notification.CATEGORY_SERVICE).setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(publicNotice).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(reopen)
                .addAction(Notification.Action.Builder(null, "Desconectar", stop).build())
            if (Build.VERSION.SDK_INT >= 31) notice.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notice.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIFICATION_ID, notice.build())
            model.backgroundEnabled = true
            model.onConnectionStateChanged = {
                if (model.phase != TerminalModel.Phase.CONNECTED && model.phase != TerminalModel.Phase.CONNECTING) {
                    model.backgroundEnabled = false
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        } catch (_: Exception) {
            model.backgroundEnabled = false
            Toast.makeText(this, "O Android não permitiu manter SSH em primeiro plano. A sessão atual continua na tela.", Toast.LENGTH_LONG).show()
            stopSelf()
        }
        // Never restart a dead process, reconnect, replay commands or recover a password.
        return START_NOT_STICKY
    }
    private fun notificationsAllowed(): Boolean =
        (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    private fun finishConnection(reason: String) {
        model.onConnectionStateChanged = null
        model.backgroundEnabled = false
        model.disconnect(reason)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onTimeout(startId: Int, fgsType: Int) { finishConnection("O Android encerrou o modo persistente. Reconecte manualmente.") }
    override fun onDestroy() {
        model.onConnectionStateChanged = null
        if (model.backgroundEnabled) {
            model.backgroundEnabled = false
            model.disconnect("Serviço encerrado. Uma nova autenticação será necessária.")
        }
        super.onDestroy()
    }
    companion object {
        const val ENABLE = "dev.myct.ssh.ENABLE"
        const val DISCONNECT = "dev.myct.ssh.DISCONNECT"
        const val CHANNEL = "ssh-session"
        const val NOTIFICATION_ID = 10
    }
}
