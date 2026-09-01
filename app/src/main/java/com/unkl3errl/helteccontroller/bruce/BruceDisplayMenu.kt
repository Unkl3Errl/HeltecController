package com.unkl3errl.helteccontroller.bruce

enum class BruceDisplayAction {
    NETWORK_STATUS,
    WEBUI_LOGIN,
    GPS_STATUS,
    GPS_TOGGLE,
    LORA_STATUS,
    LORA_TOGGLE,
    FIELD_LOG_STATUS,
    FIELD_LOG_START,
    FIELD_LOG_STOP,
    HARDWARE_STATUS,
    DEVICE_INFO,
    BUTTON_HELP,
    TIMEOUT_OFF,
    TIMEOUT_15,
    TIMEOUT_30,
    TIMEOUT_45,
    TIMEOUT_60,
    SLEEP,
    POWER_DOWN,
}

data class BruceDisplayItem(
    val label: String,
    val destination: String? = null,
    val action: BruceDisplayAction? = null,
)

data class BruceDisplayPage(
    val title: String,
    val parent: String?,
    val items: List<BruceDisplayItem>,
)

/**
 * Exact menu hierarchy from boards/heltec-wifi-lora-32-v4/interface.cpp in Bruce.
 */
object BruceDisplayMenu {
    const val ROOT = "root"

    val pages: Map<String, BruceDisplayPage> = mapOf(
        ROOT to BruceDisplayPage(
            title = "BRUCE",
            parent = null,
            items = listOf(
                BruceDisplayItem("Dashboard", destination = "dashboard"),
                BruceDisplayItem("GPS monitor", destination = "gps"),
                BruceDisplayItem("LoRa receiver", destination = "lora"),
                BruceDisplayItem("Field logger", destination = "field_log"),
                BruceDisplayItem("System", destination = "system"),
            ),
        ),
        "dashboard" to BruceDisplayPage(
            title = "DASHBOARD",
            parent = ROOT,
            items = listOf(
                BruceDisplayItem("Network", action = BruceDisplayAction.NETWORK_STATUS),
                BruceDisplayItem("WebUI login", action = BruceDisplayAction.WEBUI_LOGIN),
                BruceDisplayItem("GPS status", action = BruceDisplayAction.GPS_STATUS),
                BruceDisplayItem("LoRa status", action = BruceDisplayAction.LORA_STATUS),
                BruceDisplayItem("Field-log status", action = BruceDisplayAction.FIELD_LOG_STATUS),
                BruceDisplayItem("Hardware", action = BruceDisplayAction.HARDWARE_STATUS),
                BruceDisplayItem("Back", destination = ROOT),
            ),
        ),
        "gps" to BruceDisplayPage(
            title = "GPS MONITOR",
            parent = ROOT,
            items = listOf(
                BruceDisplayItem("Status", action = BruceDisplayAction.GPS_STATUS),
                BruceDisplayItem("Toggle monitor", action = BruceDisplayAction.GPS_TOGGLE),
                BruceDisplayItem("Back", destination = ROOT),
            ),
        ),
        "lora" to BruceDisplayPage(
            title = "LORA RECEIVER",
            parent = ROOT,
            items = listOf(
                BruceDisplayItem("Status", action = BruceDisplayAction.LORA_STATUS),
                BruceDisplayItem("Toggle receiver", action = BruceDisplayAction.LORA_TOGGLE),
                BruceDisplayItem("Back", destination = ROOT),
            ),
        ),
        "field_log" to BruceDisplayPage(
            title = "FIELD LOGGER",
            parent = ROOT,
            items = listOf(
                BruceDisplayItem("Status", action = BruceDisplayAction.FIELD_LOG_STATUS),
                BruceDisplayItem("Start GPS+BLE+WiFi", action = BruceDisplayAction.FIELD_LOG_START),
                BruceDisplayItem("Stop logging", action = BruceDisplayAction.FIELD_LOG_STOP),
                BruceDisplayItem("Back", destination = ROOT),
            ),
        ),
        "system" to BruceDisplayPage(
            title = "SYSTEM",
            parent = ROOT,
            items = listOf(
                BruceDisplayItem("Network info", action = BruceDisplayAction.NETWORK_STATUS),
                BruceDisplayItem("WebUI login", action = BruceDisplayAction.WEBUI_LOGIN),
                BruceDisplayItem("Hardware status", action = BruceDisplayAction.HARDWARE_STATUS),
                BruceDisplayItem("Device info", action = BruceDisplayAction.DEVICE_INFO),
                BruceDisplayItem("Display timeout", destination = "display"),
                BruceDisplayItem("Button help", action = BruceDisplayAction.BUTTON_HELP),
                BruceDisplayItem("Sleep (PRG wake)", action = BruceDisplayAction.SLEEP),
                BruceDisplayItem("Power down", action = BruceDisplayAction.POWER_DOWN),
                BruceDisplayItem("Back", destination = ROOT),
            ),
        ),
        "display" to BruceDisplayPage(
            title = "DISPLAY TIMEOUT",
            parent = "system",
            items = listOf(
                BruceDisplayItem("Always on", action = BruceDisplayAction.TIMEOUT_OFF),
                BruceDisplayItem("Turn off after 15s", action = BruceDisplayAction.TIMEOUT_15),
                BruceDisplayItem("Turn off after 30s", action = BruceDisplayAction.TIMEOUT_30),
                BruceDisplayItem("Turn off after 45s", action = BruceDisplayAction.TIMEOUT_45),
                BruceDisplayItem("Turn off after 60s", action = BruceDisplayAction.TIMEOUT_60),
                BruceDisplayItem("Back", destination = "system"),
            ),
        ),
    )
}
