package org.streamdock.app.dl

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.streamdock.app.MainActivity
import org.streamdock.app.R

/** Keeps concurrent in-app downloads alive in the background and shows their combined progress. */
class TransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DownloadCenter.init(applicationContext)
        startForeground(NOTIFICATION_ID, notification(DownloadCenter.summaryText()))
        scope.launch {
            DownloadCenter.items.collectLatest { items ->
                if (items.none { it.active }) { stopSelf(); return@collectLatest }
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                runCatching { manager.notify(NOTIFICATION_ID, notification(DownloadCenter.summaryText())) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE_ALL -> DownloadCenter.pauseAll(applicationContext)
        }
        return START_STICKY
    }

    private fun notification(text: String): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW).apply { description = "In-app download progress" })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra("tab", 3), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 1, Intent(this, TransferService::class.java).setAction(ACTION_PAUSE_ALL), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_dock).setContentTitle("StreamDock").setContentText(text)
            .setOngoing(true).setContentIntent(open).addAction(0, "Pause all", pause).build()
    }

    companion object {
        private const val CHANNEL = "download-progress"
        private const val NOTIFICATION_ID = 4711
        private const val ACTION_PAUSE_ALL = "org.streamdock.app.PAUSE_ALL"

        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, TransferService::class.java)
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            }
        }
        fun stopIfIdle(context: Context) {
            if (!DownloadCenter.hasActive()) runCatching { context.stopService(Intent(context, TransferService::class.java)) }
        }
    }
}
