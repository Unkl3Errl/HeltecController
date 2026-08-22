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
        assertEquals(
            FirmwareKind.BRUCE,
            FirmwareIdentity.classifyUsb(
                "[BOOT] reset=power_on (1), power guard=1200 ms\r\n",
            ),
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

    @Test fun detectsGhostEspFromVersionHelpAndPromptResponses() {
        assertEquals(
            FirmwareKind.GHOSTESP,
            FirmwareIdentity.classifyUsb("GhostESP v2.1 (Revival)\r\nBuild: 42\r\n"),
        )
        assertEquals(
            FirmwareKind.GHOSTESP,
            FirmwareIdentity.classifyUsb("\r\nGhost ESP Command Categories:\r\n\r\nWiFi\r\n"),
        )
        assertEquals(
            FirmwareKind.GHOSTESP,
            FirmwareIdentity.classifyUsb("command complete\r\nghost> "),
        )
    }

    @Test fun refusesEmptyAmbiguousAndUnrelatedResponses() {
        assertEquals(FirmwareKind.UNKNOWN, FirmwareIdentity.classifyUsb(""))
        assertEquals(FirmwareKind.UNKNOWN, FirmwareIdentity.classifyUsb("ready\r\n> "))
        assertEquals(
            FirmwareKind.UNKNOWN,
            FirmwareIdentity.classifyUsb("[BOOT] reset=power_on (1)\r\n"),
        )
        assertEquals(
            FirmwareKind.UNKNOWN,
            FirmwareIdentity.classifyUsb("Bruce v1\nFirmware: Marauder\n"),
        )
        assertEquals(
            FirmwareKind.UNKNOWN,
            FirmwareIdentity.classifyUsb("Bruce v1\nghost> \n"),
        )
    }

    @Test fun extractsReleaseVersionsAndSourceCommits() {
        val bruce = "Bruce v0.6.0\r\n12496e7e572d-dirty\r\n"
        assertEquals("0.6.0", FirmwareIdentity.version(FirmwareKind.BRUCE, bruce))
        assertEquals("12496e7e572d-dirty", FirmwareIdentity.commit(FirmwareKind.BRUCE, bruce))

        val ghost = "GhostESP v2.1.0-mobile.2 (Revival)\r\nGit: main @ fb21cebe\r\n"
        assertEquals(
            "2.1.0-mobile.2",
            FirmwareIdentity.version(FirmwareKind.GHOSTESP, ghost),
        )
        assertEquals("fb21cebe", FirmwareIdentity.commit(FirmwareKind.GHOSTESP, ghost))

        val marauder = "Firmware: Marauder\r\nVersion: v1.14.1-mobile.3\r\n"
        assertEquals(
            "1.14.1-mobile.3",
            FirmwareIdentity.version(FirmwareKind.MARAUDER, marauder),
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

    @Test fun recognizesGhostEspWebUiBranding() {
        assertTrue(
            FirmwareIdentity.isGhostEspWebUi(
                200,
                "<html><head><title>GhostNet</title></head><body></body></html>",
            ),
        )
        assertTrue(
            FirmwareIdentity.isGhostEspWebUi(
                200,
                "<html><head><title>Device</title></head><body>GhostESP console</body></html>",
            ),
        )
        assertFalse(FirmwareIdentity.isGhostEspWebUi(302, "<title>GhostNet</title>"))
        assertFalse(FirmwareIdentity.isGhostEspWebUi(200, "<title>Router</title>"))
    }
}
