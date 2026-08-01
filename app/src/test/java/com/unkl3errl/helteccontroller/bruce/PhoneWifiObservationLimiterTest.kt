package com.unkl3errl.helteccontroller.bruce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneWifiObservationLimiterTest {
    @Test
    fun normalizesDeduplicatesAndRateLimitsBssids() {
        val limiter = PhoneWifiObservationLimiter(repeatIntervalMs = 60_000L)
        val weak = observation("aa:bb:cc:dd:ee:ff", -80)
        val strong = observation("AA:BB:CC:DD:EE:FF", -42)
        val invalid = observation("not-a-bssid", -10)

        assertEquals(listOf(strong), limiter.select(listOf(weak, strong, invalid), 100_000L))
        assertTrue(limiter.select(listOf(strong), 159_999L).isEmpty())
        assertEquals(listOf(strong), limiter.select(listOf(strong), 160_000L))
    }

    @Test
    fun limitsEachScanToTheStrongestNetworks() {
        val limiter = PhoneWifiObservationLimiter(maximumPerScan = 2)
        val selected = limiter.select(
            listOf(
                observation("00:00:00:00:00:01", -75),
                observation("00:00:00:00:00:02", -35),
                observation("00:00:00:00:00:03", -55),
            ),
            1_000L,
        )
        assertEquals(listOf(-35, -55), selected.map(PhoneWifiObservation::rssiDbm))
    }

    @Test
    fun defaultPolicyCapsEachScanAtThirtyTwoNetworks() {
        val observations = (0 until 40).map { index ->
            val suffix = index.toString(16).padStart(2, '0')
            observation("02:00:00:00:00:$suffix", -30 - index)
        }

        assertEquals(32, PhoneWifiObservationLimiter().select(observations, 1_000L).size)
    }

    private fun observation(bssid: String, rssi: Int) = PhoneWifiObservation(
        bssid = bssid,
        ssid = "Test",
        capabilities = "[WPA2-PSK-CCMP][ESS]",
        rssiDbm = rssi,
        frequencyMhz = 2_437,
        channelWidth = 0,
        centerFrequency0Mhz = 0,
        centerFrequency1Mhz = 0,
        scanSequence = 1,
        sourceUnixTimeMs = 1_700_000_000_000L,
    )
}
