package com.unkl3errl.helteccontroller.bruce

import java.util.Locale

data class PhoneWifiObservation(
    val bssid: String,
    val ssid: String,
    val capabilities: String,
    val rssiDbm: Int,
    val frequencyMhz: Int,
    val channelWidth: Int,
    val centerFrequency0Mhz: Int,
    val centerFrequency1Mhz: Int,
    val scanSequence: Long,
    val sourceUnixTimeMs: Long,
) {
    fun asForm(): Map<String, String> = linkedMapOf(
        "bssid" to bssid,
        "ssid" to ssid,
        "capabilities" to capabilities,
        "rssiDbm" to rssiDbm.toString(),
        "frequencyMhz" to frequencyMhz.toString(),
        "channelWidth" to channelWidth.toString(),
        "centerFrequency0Mhz" to centerFrequency0Mhz.toString(),
        "centerFrequency1Mhz" to centerFrequency1Mhz.toString(),
        "scanSequence" to scanSequence.toString(),
        "sourceUnixTimeMs" to sourceUnixTimeMs.toString(),
    )
}

class PhoneWifiObservationLimiter(
    private val repeatIntervalMs: Long = 60_000L,
    private val maximumPerScan: Int = 32,
    private val maximumTrackedNetworks: Int = 512,
) {
    private val lastEmittedAt = linkedMapOf<String, Long>()

    fun select(observations: List<PhoneWifiObservation>, nowMs: Long): List<PhoneWifiObservation> {
        val strongestByBssid = linkedMapOf<String, PhoneWifiObservation>()
        observations.forEach { observation ->
            val bssid = observation.bssid.uppercase(Locale.US)
            if (!BSSID.matches(bssid)) return@forEach
            val normalized = observation.copy(
                bssid = bssid,
                ssid = observation.ssid.take(64),
                capabilities = observation.capabilities.take(160),
            )
            val previous = strongestByBssid[bssid]
            if (previous == null || normalized.rssiDbm > previous.rssiDbm) {
                strongestByBssid[bssid] = normalized
            }
        }

        val selected = strongestByBssid.values
            .sortedByDescending(PhoneWifiObservation::rssiDbm)
            .take(maximumPerScan)
            .filter { observation ->
                val previous = lastEmittedAt[observation.bssid]
                previous == null || nowMs - previous >= repeatIntervalMs
            }
        selected.forEach { lastEmittedAt[it.bssid] = nowMs }

        while (lastEmittedAt.size > maximumTrackedNetworks) {
            val oldest = lastEmittedAt.minByOrNull { it.value }?.key ?: break
            lastEmittedAt.remove(oldest)
        }
        return selected
    }

    fun clear() = lastEmittedAt.clear()

    private companion object {
        val BSSID = Regex("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}")
    }
}
