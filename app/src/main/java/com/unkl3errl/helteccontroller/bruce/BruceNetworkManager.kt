package com.unkl3errl.helteccontroller.bruce

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Handler
import android.os.Looper
import com.unkl3errl.helteccontroller.connection.DeviceConnectionService
import com.unkl3errl.helteccontroller.connection.PersistentDeviceConnections
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Process-wide local-device Wi-Fi request. Activity listeners can come and go without releasing
 * the Android NetworkSpecifier connection.
 */
class BruceNetworkManager private constructor(context: Context) {
    interface Listener {
        fun onBruceNetworkAvailable(network: Network)
        fun onBruceNetworkLost()
        fun onBruceNetworkError(message: String)
    }

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    var activeNetwork: Network? = null
        private set

    @Volatile
    var requestedSsid: String? = null
        private set

    fun attach(listener: Listener) {
        listeners.add(listener)
        val network = activeNetwork
        if (network != null) {
            mainHandler.post {
                if (listeners.contains(listener) && activeNetwork == network) {
                    listener.onBruceNetworkAvailable(network)
                }
            }
        }
    }

    fun detach(listener: Listener) {
        listeners.remove(listener)
    }

    fun request(ssid: String, password: String) {
        if (ssid.isBlank()) {
            emitError("Enter the device Wi-Fi SSID")
            return
        }
        DeviceConnectionService.start(appContext)
        releaseCallback()
        requestedSsid = ssid

        val specifierBuilder = WifiNetworkSpecifier.Builder().setSsid(ssid)
        if (password.isNotBlank()) specifierBuilder.setWpa2Passphrase(password)

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifierBuilder.build())
            .build()

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (callback !== this) return
                activeNetwork = network
                PersistentDeviceConnections.setLocalNetwork(requestedSsid)
                DeviceConnectionService.refresh(appContext)
                listeners.forEach { it.onBruceNetworkAvailable(network) }
            }

            override fun onLost(network: Network) {
                if (callback !== this) return
                activeNetwork = null
                PersistentDeviceConnections.setLocalNetwork(null)
                // Network specifier requests can show system UI; retry only after a user gesture.
                releaseCallback(this)
                listeners.forEach { it.onBruceNetworkLost() }
            }

            override fun onUnavailable() {
                if (callback !== this) return
                activeNetwork = null
                PersistentDeviceConnections.setLocalNetwork(null)
                val unavailableSsid = requestedSsid ?: "Device Wi-Fi"
                releaseCallback(this)
                emitError(
                    "$unavailableSsid connection was canceled or is unavailable; " +
                        "tap its connect button to retry",
                )
            }
        }
        callback = networkCallback
        connectivityManager.requestNetwork(request, networkCallback)
    }

    /** Explicitly release the device Wi-Fi request; Activity destruction only calls [detach]. */
    fun release() {
        releaseCallback()
    }

    private fun releaseCallback(expected: ConnectivityManager.NetworkCallback? = null) {
        val active = callback ?: return
        if (expected != null && active !== expected) return
        callback = null
        activeNetwork = null
        requestedSsid = null
        PersistentDeviceConnections.setLocalNetwork(null)
        DeviceConnectionService.refresh(appContext)
        runCatching { connectivityManager.unregisterNetworkCallback(active) }
    }

    private fun emitError(message: String) {
        listeners.forEach { it.onBruceNetworkError(message) }
    }

    companion object {
        @Volatile
        private var instance: BruceNetworkManager? = null

        fun get(context: Context): BruceNetworkManager =
            instance ?: synchronized(this) {
                instance ?: BruceNetworkManager(context.applicationContext).also { instance = it }
            }
    }
}
