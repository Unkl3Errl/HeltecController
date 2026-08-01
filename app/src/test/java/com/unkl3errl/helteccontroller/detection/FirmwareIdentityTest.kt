package com.unkl3errl.helteccontroller.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirmwareIdentityTest {
    @Test fun detectsBruceFromReadOnlyInfoAndHelpResponses() {
        assertEquals(
            FirmwareKind.BRUCE,
            FirmwareIdentity.classifyUsb("Bruce v1.12.3\r\nSDK: esp-idf\r\n"),
        )
        assertEquals(
            FirmwareKind.BRUCE,
            FirmwareIdentity.classifyUsb("noise\nDevice: HELTEC-V4\n"),
        )
    }

    @Test fun detectsMarauderFromBannerInfoAndHelpResponses() {
        assertEquals(
            FirmwareKind.MARAUDER,
            FirmwareIdentity.classifyUsb("         ESP32 Marauder      \r\n"),
        )
        assertEquals(
            FirmwareKind.MARAUDER,
            FirmwareIdentity.classifyUsb("#info\r\nFirmware: Marauder\r\n"),
        )
        assertEquals(
            FirmwareKind.MARAUDER,
            FirmwareIdentity.classifyUsb("============ Commands ============\nchannel [-s <channel>]\n"),
        )
    }

    @Test fun refusesEmptyAmbiguousAndUnrelatedResponses() {
        assertEquals(FirmwareKind.UNKNOWN, FirmwareIdentity.classifyUsb(""))
        assertEquals(FirmwareKind.UNKNOWN, FirmwareIdentity.classifyUsb("ready\r\n> "))
        assertEquals(
            FirmwareKind.UNKNOWN,
            FirmwareIdentity.classifyUsb("Bruce v1\nFirmware: Marauder\n"),
        )
    }

    @Test fun verifiesTheUnauthenticatedBruceLoginSignature() {
        val quoted = "<html><head><title>Bruce</title></head><form action=\"/login\"></form></html>"
        val minified = "<html><head><title>Bruce</title></head><form action=/login method=POST></form></html>"
        assertTrue(FirmwareIdentity.isBruceWebUi(200, quoted))
        assertTrue(FirmwareIdentity.isBruceWebUi(200, minified))
        assertFalse(FirmwareIdentity.isBruceWebUi(302, minified))
        assertFalse(FirmwareIdentity.isBruceWebUi(200, "<title>Router</title>"))
        assertFalse(
            FirmwareIdentity.isBruceWebUi(
                200,
                "<title>Bruce</title><form action=/login-attacker></form>",
            ),
        )
    }
}
