package com.unkl3errl.helteccontroller.detection

enum class FirmwareKind { UNKNOWN, BRUCE, MARAUDER, GHOSTESP }

enum class DetectionSource { USB, BRUCENET, GHOSTNET, MANUAL }

data class FirmwareDetection(
    val kind: FirmwareKind,
    val source: DetectionSource,
    val evidence: String,
)

object FirmwareIdentity {
    private val bruceLoginAction = Regex(
        """(?i)\baction\s*=\s*(?:[\"']/login[\"']|/login(?=\s|>))""",
    )
    private val bruceSignatures = listOf(
        Regex("(?im)^Bruce\\s+v[^\\r\\n]*$"),
        Regex("(?im)^Device:\\s*HELTEC(?:[-_ ]|$)"),
    )
    private val marauderSignatures = listOf(
        Regex("(?im)^\\s*ESP32\\s+Marauder\\s*$"),
        Regex("(?im)^Firmware:\\s*Marauder\\s*$"),
        Regex("(?m)^============ Commands ============$"),
    )
    private val ghostEspSignatures = listOf(
        Regex("(?i)GhostESP v2\\.1 \\(Revival\\)"),
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

    fun isBruceWebUi(status: Int, body: String): Boolean =
        status == 200 &&
            body.contains("<title>Bruce</title>", ignoreCase = true) &&
            bruceLoginAction.containsMatchIn(body)

    fun isGhostEspWebUi(status: Int, body: String): Boolean =
        status == 200 && ghostEspWebUiBranding.containsMatchIn(body)
}
