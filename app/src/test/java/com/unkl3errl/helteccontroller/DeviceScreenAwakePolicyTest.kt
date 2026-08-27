package com.unkl3errl.helteccontroller

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceScreenAwakePolicyTest {
    @Test
    fun keepsScreenOnForLiveConnectionOrFlashOnly() {
        assertTrue(DeviceScreenAwakePolicy.shouldKeepScreenOn(flashing = false, deviceConnected = true))
        assertTrue(DeviceScreenAwakePolicy.shouldKeepScreenOn(flashing = true, deviceConnected = false))
        assertFalse(DeviceScreenAwakePolicy.shouldKeepScreenOn(flashing = false, deviceConnected = false))
    }
}
