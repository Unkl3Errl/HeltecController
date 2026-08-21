package com.unkl3errl.helteccontroller.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Esp32BluetoothIdentityTest {
    @Test
    fun firmwareAddressOffsetsMapToOneBoard() {
        assertEquals(
            "8C:FD:49:B5:E0:A0",
            Esp32BluetoothIdentity.hardwareKey("8c:fd:49:b5:e0:a2"),
        )
        assertTrue(
            Esp32BluetoothIdentity.sameHardware(
                "8C:FD:49:B5:E0:A1",
                "8C:FD:49:B5:E0:A2",
            ),
        )
    }

    @Test
    fun separateBoardsRemainSeparate() {
        assertFalse(
            Esp32BluetoothIdentity.sameHardware(
                "8C:FD:49:B5:E0:A1",
                "8C:FD:49:B6:8C:15",
            ),
        )
    }

    @Test
    fun malformedAddressIsRejected() {
        assertNull(Esp32BluetoothIdentity.hardwareKey("not-an-address"))
    }
}
