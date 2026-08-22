package com.unkl3errl.helteccontroller

import org.junit.Assert.assertEquals
import org.junit.Test

class TransportStatusLabelerTest {
    @Test
    fun `usb and bluetooth show preferred and standby roles`() {
        val labels = TransportStatusLabeler.labels("Bruce", true, true)

        assertEquals("Bruce USB connected · preferred transport", labels.usb)
        assertEquals(
            "Bruce Bluetooth connected · standby fallback available",
            labels.bluetooth,
        )
    }

    @Test
    fun `bluetooth only shows active transport`() {
        val labels = TransportStatusLabeler.labels("Bruce", false, true)

        assertEquals("Bruce USB disconnected", labels.usb)
        assertEquals("Bruce Bluetooth connected · active transport", labels.bluetooth)
    }

    @Test
    fun `usb only shows unavailable bluetooth fallback`() {
        val labels = TransportStatusLabeler.labels("GhostESP", true, false)

        assertEquals("GhostESP USB connected · active transport", labels.usb)
        assertEquals(
            "GhostESP Bluetooth disconnected · fallback unavailable",
            labels.bluetooth,
        )
    }

    @Test
    fun `bluetooth progress remains visible without corrupting usb state`() {
        val message = "Connecting to Bruce BLE Serial over Bluetooth…"
        val labels = TransportStatusLabeler.labels("Bruce", true, false, message)

        assertEquals("Bruce USB connected · active transport", labels.usb)
        assertEquals(message, labels.bluetooth)
    }

    @Test
    fun `usb errors remain visible while both transports are offline`() {
        val message = "Bruce USB connection failed"
        val labels = TransportStatusLabeler.labels("Bruce", false, false, message)

        assertEquals(message, labels.usb)
        assertEquals("Bruce Bluetooth disconnected", labels.bluetooth)
    }
}
