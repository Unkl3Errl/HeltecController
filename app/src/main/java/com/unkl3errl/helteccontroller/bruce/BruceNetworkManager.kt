package com.unkl3errl.helteccontroller.bruce

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier

class BruceNetworkManager(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onBruceNetworkAvailable(network: Network)
        fun onBruceNetworkLost()
        fun onBruceNetworkError(message: String)
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun request(ssid: String, password: String) {
        if (ssid.isBlank()) {
            listener.onBruceNetworkError("Enter the BruceNet SSID")
            return
        }
        releaseCallback()

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
                listener.onBruceNetworkAvailable(network)
            }

            override fun onLost(network: Network) {
                if (callback !== this) return
                // Network specifier requests can show system UI; retry only after a user gesture.
                releaseCallback(this)
                listener.onBruceNetworkLost()
            }

            override fun onUnavailable() {
                if (callback !== this) return
                releaseCallback(this)
                listener.onBruceNetworkError(
                    "BruceNet connection was canceled or is unavailable; tap Detect BruceNet to retry",
                )
            }
        }
        callback = networkCallback
        connectivityManager.requestNetwork(request, networkCallback)
    }

    fun release() {
        releaseCallback()
    }

    private fun releaseCallback(expected: ConnectivityManager.NetworkCallback? = null) {
        val active = callback ?: return
        if (expected != null && active !== expected) return
        callback = null
        runCatching { connectivityManager.unregisterNetworkCallback(active) }
    }
}
