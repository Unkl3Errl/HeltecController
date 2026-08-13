package com.unkl3errl.helteccontroller.detection

import com.unkl3errl.helteccontroller.usb.UsbDeviceTarget

enum class FirmwareKind { UNKNOWN, BRUCE, MARAUDER, GHOSTESP }

enum class DetectionSource { USB, BRUCENET, GHOSTNET, MANUAL }

data class FirmwareDetection(
    val kind: FirmwareKind,
    val source: DetectionSource,
    val evidence: String,
    val version: String? = null,
    val commit: String? = null,
    val usbTarget: UsbDeviceTarget? = null,
)

object FirmwareIdentity {
    private val bruceLoginAction = Regex(
        """(?i)\baction\s*=\s*(?:[\"']/login[\"']|/login(?=\s|>))""",
    )
    private val bruceSignatures = listOf(
        Regex("(?im)^Bruce\\s+v?[^\\r\\n]*$"),
        Regex("(?im)^Device:\\s*HELTEC(?:[-_ ]|$)"),
    )
    private val marauderSignatures = listOf(
        Regex("(?im)^\\s*ESP32\\s+Marauder\\s*$"),
        Regex("(?im)^Firmware:\\s*Marauder\\s*$"),
        Regex("(?m)^============ Commands ============$"),
    )
    private val ghostEspSignatures = listOf(
        Regex("(?i)GhostESP v[^\\r\\n]+"),
        Regex("(?i)Ghost ESP Command Categories:"),
        Regex("(?im)^\\s*ghost>\\s*$"),
    )
    private val ghostEspWebUiBranding = Regex("(?i)\\b(?:GhostNet|GhostESP)\\b")

    fun classifyUsb(text: String): FirmwareKind {
        val bruce = bruceSignatures.any { it.containsMatchIn(text) }
        val marauder = marauderSignatures.any { it.containsMatchIn(text) }
        val ghostEsp = ghostEspSignatures.any { it.containsMatchIn(text) }
        return when {
            bruce && !marauder && !ghostEsp -> FirmwareKind.BRUCE
            marauder && !bruce && !ghostEsp -> FirmwareKind.MARAUDER
            ghostEsp && !bruce && !marauder -> FirmwareKind.GHOSTESP
            else -> FirmwareKind.UNKNOWN
        }
    }

    fun version(kind: FirmwareKind, text: String): String? {
        val pattern = when (kind) {
            FirmwareKind.BRUCE -> Regex("(?im)^Bruce\\s+v?([0-9A-Za-z][0-9A-Za-z._+-]*)")
            FirmwareKind.GHOSTESP -> Regex("(?im)^GhostESP\\s+v?([0-9A-Za-z][0-9A-Za-z._+-]*)")
            FirmwareKind.MARAUDER -> Regex(
                "(?im)(?:ESP32\\s+Marauder[^\\r\\n]*[\\r\\n]+\\s*|Marauder\\s+|Version:\\s*)(v?[0-9][0-9A-Za-z._+-]*)",
            )
            FirmwareKind.UNKNOWN -> return null
        }
        return pattern.find(text)?.groupValues?.getOrNull(1)?.removePrefix("v")
    }

    fun commit(kind: FirmwareKind, text: String): String? = when (kind) {
        FirmwareKind.BRUCE -> Regex("(?im)^([0-9a-f]{7,40}(?:-dirty)?)\\s*$")
            .find(text)?.groupValues?.getOrNull(1)
        FirmwareKind.GHOSTESP -> Regex("(?im)^Git:\\s+[^@\\r\\n]+@\\s*([0-9a-f]{7,40}(?:-dirty)?)")
            .find(text)?.groupValues?.getOrNull(1)
        else -> null
    }

    fun isBruceWebUi(status: Int, body: String): Boolean =
        status == 200 &&
            body.contains("<title>Bruce</title>", ignoreCase = true) &&
            bruceLoginAction.containsMatchIn(body)

    fun isGhostEspWebUi(status: Int, body: String): Boolean =
        status == 200 && ghostEspWebUiBranding.containsMatchIn(body)
}
