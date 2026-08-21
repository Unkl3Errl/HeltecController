package com.unkl3errl.helteccontroller.firmware

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.unkl3errl.helteccontroller.MainActivity
import com.unkl3errl.helteccontroller.R
import com.unkl3errl.helteccontroller.detection.FirmwareKind

/** Periodically checks signed images and official stable source releases. */
class FirmwareUpdateJobService : JobService() {
    private var repository: FirmwareImageRepository? = null
    private var activeParameters: JobParameters? = null

    override fun onStartJob(params: JobParameters): Boolean {
        activeParameters = params
        val imageRepository = FirmwareImageRepository(this)
        repository = imageRepository
        imageRepository.initialize(object : FirmwareImageRepository.Listener {
            override fun onCatalogChanged(catalog: FirmwareCatalog) {
                FirmwareUpdateNotifications.recordCatalog(this@FirmwareUpdateJobService, catalog)
            }

            override fun onUpstreamReleasesChanged(
                releases: Map<FirmwareKind, UpstreamRelease>,
            ) {
                val catalog = imageRepository.catalog ?: return
                FirmwareUpdateNotifications.recordUpstream(
                    this@FirmwareUpdateJobService,
                    catalog,
                    releases,
                )
                if (imageRepository.upstreamRefreshComplete) finishJob(false)
            }

            override fun onCatalogStatus(message: String) {
                if (message.startsWith("Bundled firmware verification failed")) finishJob(false)
            }
        })
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        repository?.close()
        repository = null
        activeParameters = null
        return true
    }

    private fun finishJob(reschedule: Boolean) {
        val params = activeParameters ?: return
        activeParameters = null
        repository?.close()
        repository = null
        jobFinished(params, reschedule)
    }

    companion object {
        private const val JOB_ID = 4215
        private const val MINIMUM_PERIOD_MS = 15 * 60_000L

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val info = JobInfo.Builder(
                JOB_ID,
                ComponentName(context, FirmwareUpdateJobService::class.java),
            )
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(MINIMUM_PERIOD_MS)
                .setPersisted(true)
                .build()
            scheduler.schedule(info)
        }
    }
}

private object FirmwareUpdateNotifications {
    private const val CHANNEL_ID = "firmware_updates"
    private const val PREFS = "firmware_background_updates"

    fun recordCatalog(context: Context, catalog: FirmwareCatalog) {
        catalog.releases.values.forEach { release ->
            advanceVersion(
                context,
                "image_${release.kind.name}",
                release.version,
            ) {
                notify(
                    context,
                    release.kind,
                    1,
                    "${release.displayName} image ${release.version} is ready",
                    "Released ${release.releasedAt} · open the app to review and flash the signed image.",
                )
            }
        }
    }

    fun recordUpstream(
        context: Context,
        catalog: FirmwareCatalog,
        releases: Map<FirmwareKind, UpstreamRelease>,
    ) {
        releases.values.forEach { upstream ->
            val compatible = catalog.releases[upstream.kind] ?: return@forEach
            advanceVersion(
                context,
                "upstream_${upstream.kind.name}",
                upstream.version,
            ) {
                val pending = FirmwareVersion.isUpstreamBaselineOlder(
                    compatible.upstream.baselineVersion,
                    upstream.version,
                ) == true
                notify(
                    context,
                    upstream.kind,
                    2,
                    "${compatible.displayName} source ${upstream.version} was released",
                    if (pending) {
                        "Released ${upstream.releasedAt} · the signed compatibility image is being prepared."
                    } else {
                        "Released ${upstream.releasedAt} · this source release is included in the signed image."
                    },
                )
            }
        }
    }

    private fun advanceVersion(
        context: Context,
        key: String,
        current: String,
        onUpgrade: () -> Unit,
    ) {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = preferences.getString(key, null)
        when {
            previous == null -> preferences.edit().putString(key, current).apply()
            FirmwareVersion.matches(previous, current) -> Unit
            FirmwareVersion.isOlder(current, previous) == true -> Unit
            else -> {
                preferences.edit().putString(key, current).apply()
                onUpgrade()
            }
        }
    }

    private fun notify(
        context: Context,
        kind: FirmwareKind,
        type: Int,
        title: String,
        message: String,
    ) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Firmware updates",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Signed firmware images and official stable source releases"
            },
        )
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        runCatching { manager.notify(4200 + kind.ordinal * 10 + type, notification) }
    }
}
