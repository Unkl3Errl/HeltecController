package com.unkl3errl.helteccontroller.marauder

object MarauderCommandGuidance {
    private val PORTSCAN_SERVICES = setOf("ssh", "telnet", "dns", "http", "smtp", "https", "rdp")
    private const val PORTSCAN_USAGE =
        "Port scan requires a target: portscan -a -t <IP list index>, " +
            "or portscan -s <ssh|telnet|dns|http|smtp|https|rdp>"

    fun validationError(command: String): String? {
        val arguments = command.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        if (arguments.firstOrNull()?.lowercase() != "portscan") return null

        val allTargets = arguments.indexOf("-a") >= 0
        val targetIndex = valueAfter(arguments, "-t")?.toIntOrNull()
        val service = valueAfter(arguments, "-s")?.lowercase()
        val hasIndexedTarget = allTargets && targetIndex != null && targetIndex >= 0
        val hasServiceTarget = service in PORTSCAN_SERVICES
        return if (hasIndexedTarget || hasServiceTarget) null else PORTSCAN_USAGE
    }

    private fun valueAfter(arguments: List<String>, option: String): String? {
        val index = arguments.indexOf(option)
        if (index < 0) return null
        return arguments.getOrNull(index + 1)?.takeUnless { it.startsWith('-') }
    }
}
