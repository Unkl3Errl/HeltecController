package com.unkl3errl.helteccontroller

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.unkl3errl.helteccontroller.bruce.PhoneWifiObservation
import com.unkl3errl.helteccontroller.bruce.PhoneWifiObservationLimiter

class AndroidWifiFieldScanner(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onPhoneWifiStarted()
        fun onPhoneWifiStopped()
        fun onPhoneWifiStatus(message: String)
        fun onPhoneWifiBatch(observations: List<PhoneWifiObservation>)
        fun onPhoneWifiError(message: String)
    }

    companion object {
        private const val SCAN_INTERVAL_MS = 60_000L
    }

    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    private val locationManager = appContext.getSystemService(LocationManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val limiter = PhoneWifiObservationLimiter()
    private var registered = false
    private var running = false
    private var scanSequence = 0L
    private var newestProcessedTimestampUs = 0L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!running || intent.action != WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) return
            if (!intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)) {
                listener.onPhoneWifiStatus("Android scan returned no fresh results; retrying")
                scheduleNextScan()
                return
            }
            collectResults()
            scheduleNextScan()
        }
    }

    fun start() {
        if (running) return
        if (!wifiManager.isWifiEnabled && !wifiManager.isScanAlwaysAvailable) {
            listener.onPhoneWifiError("Enable Android Wi-Fi or Wi-Fi scan-always-available")
            return
        }
        if (!locationManager.isLocationEnabled) {
            listener.onPhoneWifiError("Enable Android location services for Wi-Fi scan results")
            return
        }
        registerReceiver()
        running = true
        limiter.clear()
        listener.onPhoneWifiStarted()
        requestScan()
    }

    fun stop() {
        val wasRunning = running
        running = false
        handler.removeCallbacksAndMessages(null)
        unregisterReceiver()
        limiter.clear()
        if (wasRunning) listener.onPhoneWifiStopped()
    }

    fun destroy() = stop()

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun requestScan() {
        if (!running) return
        val accepted = runCatching { wifiManager.startScan() }.getOrElse { error ->
            listener.onPhoneWifiError(
                "Android Wi-Fi scan failed: ${error.message ?: error.javaClass.simpleName}",
            )
            stop()
            return
        }
        if (accepted) {
            listener.onPhoneWifiStatus("Android Wi-Fi scan requested · waiting for results")
            scheduleNextScan()
        } else {
            listener.onPhoneWifiStatus("Android scan throttled · waiting for the next scan window")
            scheduleNextScan()
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun collectResults() {
        val results = runCatching { wifiManager.scanResults }.getOrElse { error ->
            listener.onPhoneWifiError(
                "Android could not read Wi-Fi results: ${error.message ?: error.javaClass.simpleName}",
            )
            return
        }
        val newest = results.maxOfOrNull(ScanResult::timestamp) ?: 0L
        if (newest <= newestProcessedTimestampUs) {
            listener.onPhoneWifiStatus("Android returned cached Wi-Fi results · waiting for fresh data")
            return
        }
        newestProcessedTimestampUs = newest
        scanSequence++
        val observedAt = System.currentTimeMillis()
        val observations = results.mapNotNull { result ->
            val bssid = result.BSSID?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            PhoneWifiObservation(
                bssid = bssid,
                ssid = result.SSID.orEmpty().takeUnless { it == WifiManager.UNKNOWN_SSID }.orEmpty(),
                capabilities = result.capabilities.orEmpty(),
                rssiDbm = result.level,
                frequencyMhz = result.frequency,
                channelWidth = result.channelWidth,
                centerFrequency0Mhz = result.centerFreq0,
                centerFrequency1Mhz = result.centerFreq1,
                scanSequence = scanSequence,
                sourceUnixTimeMs = observedAt,
            )
        }
        val selected = limiter.select(observations, observedAt)
        if (selected.isNotEmpty()) listener.onPhoneWifiBatch(selected)
        listener.onPhoneWifiStatus(
            "Wi-Fi scan $scanSequence · ${results.size} visible · ${selected.size} queued",
        )
    }

    private fun scheduleNextScan() {
        handler.removeCallbacksAndMessages(null)
        if (running) handler.postDelayed(::requestScan, SCAN_INTERVAL_MS)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerReceiver() {
        if (registered) return
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(receiver, filter)
        }
        registered = true
    }

    private fun unregisterReceiver() {
        if (!registered) return
        runCatching { appContext.unregisterReceiver(receiver) }
        registered = false
    }
}
