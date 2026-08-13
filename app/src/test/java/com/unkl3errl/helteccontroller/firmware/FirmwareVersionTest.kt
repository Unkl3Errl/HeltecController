package com.unkl3errl.helteccontroller.firmware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirmwareVersionTest {
    @Test fun semanticVersionsCompareNumerically() {
        assertTrue(FirmwareVersion.isOlder("1.9.9", "1.10.0") == true)
        assertFalse(FirmwareVersion.isOlder("2.1.0", "2.0.9") == true)
    }

    @Test fun exactBuildIsCurrent() {
        assertTrue(FirmwareVersion.matches("v1.14.1-mobile.3", "1.14.1-mobile.3"))
        assertEquals(false, FirmwareVersion.isOlder("1.14.1-mobile.3", "1.14.1-mobile.3"))
    }

    @Test fun mobileBuildRevisionIsOrdered() {
        assertTrue(FirmwareVersion.isOlder("2.1.0-mobile.1", "2.1.0-mobile.2") == true)
        assertFalse(FirmwareVersion.isOlder("2.1.0-mobile.3", "2.1.0-mobile.2") == true)
    }

    @Test fun unknownInstalledVersionIsNotCalledOlder() {
        assertNull(FirmwareVersion.isOlder(null, "1.0.0"))
    }
}
