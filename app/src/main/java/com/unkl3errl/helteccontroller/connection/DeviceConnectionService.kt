package com.unkl3errl.helteccontroller.connection

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.unkl3errl.helteccontroller.MainActivity
import com.unkl3errl.helteccontroller.R

/** Keeps live board transports outside the Activity lifecycle. */
class DeviceConnectionService : Service() {
    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateNotification()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Device connection",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps the Heltec USB or local Wi-Fi session running in the background"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle("Heltec device session")
        .setContentText(PersistentDeviceConnections.connectionSummary())
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    companion object {
        private const val CHANNEL_ID = "device_connection"
        private const val NOTIFICATION_ID = 4101
        private const val ACTION_REFRESH =
            "com.unkl3errl.helteccontroller.action.REFRESH_CONNECTION_SERVICE"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context.applicationContext,
                Intent(context, DeviceConnectionService::class.java),
            )
        }

        fun refresh(context: Context) {
            runCatching {
                context.applicationContext.startService(
                    Intent(context, DeviceConnectionService::class.java).setAction(ACTION_REFRESH),
                )
            }
        }
    }
}
