package com.unkl3errl.helteccontroller.usb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbDeviceTargetTest {
    @Test
    fun serialNumberKeepsIdentityAcrossAndroidDeviceIds() {
        val first = target(deviceId = 4, deviceName = "/dev/bus/usb/001/004", serial = "AA:BB")
        val reattached = target(deviceId = 9, deviceName = "/dev/bus/usb/001/009", serial = "aa:bb")

        assertTrue(first.samePhysicalDevice(reattached))
        assertTrue(first.stableKey == reattached.stableKey)
    }

    @Test
    fun differentHubPortsRemainDistinctWithoutSerialPermission() {
        val first = target(deviceId = 4, deviceName = "/dev/bus/usb/001/004", serial = null)
        val second = target(deviceId = 5, deviceName = "/dev/bus/usb/001/005", serial = null)

        assertFalse(first.samePhysicalDevice(second))
        assertTrue(first.displayLabel().contains("Android port 4"))
    }

    @Test
    fun reusedAndroidIdDoesNotMatchDifferentUsbHardware() {
        val esp = target(deviceId = 4, deviceName = "/dev/bus/usb/001/004", serial = null)
        val other = esp.copy(vendorId = 0x10C4, productId = 0xEA60)

        assertFalse(esp.samePhysicalDevice(other))
    }

    private fun target(deviceId: Int, deviceName: String, serial: String?) = UsbDeviceTarget(
        deviceId = deviceId,
        deviceName = deviceName,
        vendorId = UsbDeviceTarget.ESPRESSIF_VID,
        productId = UsbDeviceTarget.ESP32_USB_JTAG_PID,
        manufacturerName = "Espressif",
        productName = "USB JTAG/serial debug unit",
        serialNumber = serial,
    )
}
