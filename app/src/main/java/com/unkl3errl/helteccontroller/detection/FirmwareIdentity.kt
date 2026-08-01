package com.unkl3errl.helteccontroller.detection

enum class FirmwareKind { UNKNOWN, BRUCE, MARAUDER }

enum class DetectionSource { USB, BRUCENET, MANUAL }

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

    fun classifyUsb(text: String): FirmwareKind {
        val bruce = bruceSignatures.any { it.containsMatchIn(text) }
        val marauder = marauderSignatures.any { it.containsMatchIn(text) }
        return when {
            bruce && !marauder -> FirmwareKind.BRUCE
            marauder && !bruce -> FirmwareKind.MARAUDER
            else -> FirmwareKind.UNKNOWN
        }
    }

    fun isBruceWebUi(status: Int, body: String): Boolean =
        status == 200 &&
            body.contains("<title>Bruce</title>", ignoreCase = true) &&
            bruceLoginAction.containsMatchIn(body)
}
