package com.unkl3errl.helteccontroller.connection

import org.junit.Assert.assertEquals
import org.junit.Test

class ArchiveDeviceFolderTest {
    @Test
    fun `folder stays stable across sessions and prefers the physical USB identity`() {
        assertEquals(
            "8C-FD-49-B6-8C-14",
            stableArchiveDeviceFolderName(
                connectionId = "marauder:ble:8C:FD:49:B6:8C:15",
                usbSerialNumber = "8C:FD:49:B6:8C:14",
                bluetoothAddress = "8C:FD:49:B6:8C:15",
            ),
        )
        assertEquals(
            "8C-FD-49-B6-8C-14",
            stableArchiveDeviceFolderName(
                connectionId = "marauder:usb:new-session",
                usbSerialNumber = "8C:FD:49:B6:8C:14",
                bluetoothAddress = null,
            ),
        )
    }

    @Test
    fun `Bluetooth identity is retained when USB has not been observed`() {
        assertEquals(
            "8C-FD-49-B5-E0-A2",
            stableArchiveDeviceFolderName(
                connectionId = "ghostesp:ble:8C:FD:49:B5:E0:A2",
                usbSerialNumber = null,
                bluetoothAddress = "8C:FD:49:B5:E0:A2",
            ),
        )
    }
}
