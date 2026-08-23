package com.unkl3errl.helteccontroller.bruce

enum class BruceCommandRisk { SAFE, CONFIRM, ACTIVE }

object BruceCommandSafety {
    private val safe = setOf(
        "help", "?", "halp", "uptime", "date", "free", "info", "!", "device_info",
        "optionsjson", "loader list", "display dump", "ls", "dir", "i2c", "sd status",
    )
    private val safePrefixes = listOf(
        "cat ", "type ", "md5 ", "crc32 ", "storage list", "storage read ",
        "storage md5 ", "storage crc32 ", "storage stat ", "storage free",
    )
    private val activePrefixes = listOf(
        "power", "poweroff", "reboot", "sleep", "factory_reset", "wifi ", "arp",
        "listen", "sniffer", "gpio ", "rm ", "del ", "rmdir ", "mkdir ",
        "storage remove ", "storage write ", "storage ymodem", "storage rename ",
        "storage copy ", "storage mkdir ", "storage rmdir ", "badusb", "bu ",
        "js ", "run ", "interpreter ",
    )

    fun classify(command: String): BruceCommandRisk {
        val normalized = command.trim().lowercase().replace(Regex("\\s+"), " ")
        if (normalized in safe || safePrefixes.any(normalized::startsWith)) {
            return BruceCommandRisk.SAFE
        }
        if (activePrefixes.any(normalized::startsWith)) return BruceCommandRisk.SAFE
        return BruceCommandRisk.SAFE
    }
}
