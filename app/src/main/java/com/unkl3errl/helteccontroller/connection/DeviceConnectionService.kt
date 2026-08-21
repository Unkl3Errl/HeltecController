package com.unkl3errl.helteccontroller.connection

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.unkl3errl.helteccontroller.MainActivity
import com.unkl3errl.helteccontroller.R

/** Keeps live board transports outside the Activity lifecycle. */
class DeviceConnectionService : Service() {
    private lateinit var transferWakeLock: PowerManager.WakeLock

    override fun onCreate() {
        super.onCreate()
        transferWakeLock = getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:storage-transfer",
        ).apply { setReferenceCounted(false) }
        createChannel()
        startForeground(NOTIFICATION_ID, notification())
        PersistentDeviceConnections.restoreBluetooth(this)
        updateWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateWakeLock()
        updateNotification()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (::transferWakeLock.isInitialized && transferWakeLock.isHeld) transferWakeLock.release()
        super.onDestroy()
    }

    @SuppressLint("WakelockTimeout")
    private fun updateWakeLock() {
        val deviceConnected = PersistentDeviceConnections.activeKinds().isNotEmpty()
        if (deviceConnected && !transferWakeLock.isHeld) transferWakeLock.acquire()
        else if (!deviceConnected && transferWakeLock.isHeld) transferWakeLock.release()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Device connection",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps USB, Bluetooth, or local Wi-Fi device sessions running in the background"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle("Firmware device session")
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
