package com.scriptrunner.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.graphics.drawable.Icon

/**
 * Foreground service – keeps the process alive so the server is not killed
 * when the app goes to background. The stop action kills the currently selected script.
 */
class ServerKeepAliveService : Service() {

    companion object {
        const val CHANNEL_ID = "av_tools_keepalive"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.scriptrunner.app.STOP_SERVICE"

        fun start(context: Context) {
            val intent = Intent(context, ServerKeepAliveService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ServerKeepAliveService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            TermuxHelper.getCurrent(this)?.let { TermuxHelper.stopServer(this, it) }
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notif_running)))

        return START_STICKY
    }

    private fun createNotificationChannel() {
        run {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ServerKeepAliveService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = Notification.Builder(this, CHANNEL_ID)

        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_notification),
            getString(R.string.notif_stop),
            stopPending
        ).build()

        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openPending)
            .addAction(stopAction)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }
}
