package com.unkl3errl.helteccontroller.marauder

enum class MarauderDisplayColor {
    GREEN,
    CYAN,
    RED,
    BLUE,
    ORANGE,
    YELLOW,
    PURPLE,
    WHITE,
}

data class MarauderDisplayItem(
    val label: String,
    val color: MarauderDisplayColor,
    val destination: String? = null,
    val command: String? = null,
    val opensCommands: Boolean = false,
)

data class MarauderDisplayPage(
    val title: String,
    val parent: String?,
    val items: List<MarauderDisplayItem>,
)

/**
 * Touch-friendly mirror of the menu hierarchy in Marauder's MenuFunctions.cpp.
 * Actions intentionally use the public serial CLI so the phone and hardware
 * display remain independent views of the same firmware state.
 */
object MarauderDisplayMenu {
    const val ROOT = "main"

    val pages: Map<String, MarauderDisplayPage> = mapOf(
        ROOT to MarauderDisplayPage(
            title = "Main Menu",
            parent = null,
            items = listOf(
                MarauderDisplayItem("WiFi", MarauderDisplayColor.GREEN, destination = "wifi"),
                MarauderDisplayItem("Bluetooth", MarauderDisplayColor.CYAN, destination = "bluetooth"),
                MarauderDisplayItem("GPS", MarauderDisplayColor.RED, destination = "gps"),
                MarauderDisplayItem("Device", MarauderDisplayColor.BLUE, destination = "device"),
                MarauderDisplayItem("Reboot", MarauderDisplayColor.WHITE, command = "reboot"),
            ),
        ),
        "wifi" to MarauderDisplayPage(
            title = "WiFi",
            parent = ROOT,
            items = listOf(
                MarauderDisplayItem("Back", MarauderDisplayColor.WHITE, destination = ROOT),
                MarauderDisplayItem("Sniffers", MarauderDisplayColor.YELLOW, destination = "sniffers"),
                MarauderDisplayItem("Scanners", MarauderDisplayColor.ORANGE, destination = "scanners"),
                MarauderDisplayItem("Attacks", MarauderDisplayColor.RED, opensCommands = true),
                MarauderDisplayItem("General Apps", MarauderDisplayColor.PURPLE, destination = "general"),
            ),
        ),
        "sniffers" to MarauderDisplayPage(
            title = "WiFi Sniffers",
            parent = "wifi",
            items = listOf(
                MarauderDisplayItem("Back", MarauderDisplayColor.WHITE, destination = "wifi"),
                MarauderDisplayItem("Raw Capture", MarauderDisplayColor.WHITE, command = "sniffraw"),
                MarauderDisplayItem("Beacon Sniff", MarauderDisplayColor.GREEN, command = "sniffbeacon"),
                MarauderDisplayItem("Probe Sniff", MarauderDisplayColor.CYAN, command = "sniffprobe"),
                MarauderDisplayItem("EAPOL/PMKID", MarauderDisplayColor.ORANGE, command = "sniffpmkid"),
                MarauderDisplayItem("SAE Commit", MarauderDisplayColor.PURPLE, command = "sniffsae"),
            ),
        ),
        "scanners" to MarauderDisplayPage(
            title = "WiFi Scanners",
            parent = "wifi",
            items = listOf(
                MarauderDisplayItem("Back", MarauderDisplayColor.WHITE, destination = "wifi"),
                MarauderDisplayItem("Scan APs & Stations", MarauderDisplayColor.GREEN, command = "scanall"),
                MarauderDisplayItem("Packet Monitor", MarauderDisplayColor.CYAN, command = "packetcount"),
                MarauderDisplayItem("Ping Scan", MarauderDisplayColor.ORANGE, command = "pingscan"),
                MarauderDisplayItem("ARP Scan", MarauderDisplayColor.YELLOW, command = "arpscan"),
                MarauderDisplayItem("Port Scan", MarauderDisplayColor.PURPLE, opensCommands = true),
            ),
        ),
        "general" to MarauderDisplayPage(
            title = "WiFi General",
            parent = "wifi",
            items = listOf(
                MarauderDisplayItem("Back", MarauderDisplayColor.WHITE, destination = "wifi"),
                MarauderDisplayItem("List APs", MarauderDisplayColor.GREEN, command = "list -a"),
                MarauderDisplayItem("List Stations", MarauderDisplayColor.CYAN, command = "list -c"),
                MarauderDisplayItem("List SSIDs", MarauderDisplayColor.ORANGE, command = "list -s"),
                MarauderDisplayItem("List Probes", MarauderDisplayColor.YELLOW, command = "list -p"),
                MarauderDisplayItem("Stop Scan", MarauderDisplayColor.RED, command = "stopscan"),
            ),
        ),
        "bluetooth" to MarauderDisplayPage(
            title = "Bluetooth",
            parent = ROOT,
            items = listOf(
                MarauderDisplayItem("Back", MarauderDisplayColor.WHITE, destination = ROOT),
                MarauderDisplayItem("BLE Sniffer", MarauderDisplayColor.YELLOW, command = "sniffbt"),
                MarauderDisplayItem("List BLE", MarauderDisplayColor.CYAN, command = "list -b"),
                MarauderDisplayItem("BLE Actions", MarauderDisplayColor.RED, opensCommands = true),
                MarauderDisplayItem("Stop Scan", MarauderDisplayColor.ORANGE, command = "stopscan"),
            ),
        ),
        "gps" to MarauderDisplayPage(
            title = "GPS",
            parent = ROOT,
            items = listOf(
                MarauderDisplayItem("Back", MarauderDisplayColor.WHITE, destination = ROOT),
                MarauderDisplayItem("GPS Data", MarauderDisplayColor.RED, command = "gpsdata"),
                MarauderDisplayItem("NMEA Stream", MarauderDisplayColor.ORANGE, command = "nmea"),
                MarauderDisplayItem("GPS Tracker", MarauderDisplayColor.GREEN, command = "gpstracker -c start"),
                MarauderDisplayItem("GPS POI", MarauderDisplayColor.CYAN, command = "gpspoi -s"),
                MarauderDisplayItem("Stop", MarauderDisplayColor.WHITE, command = "stopscan"),
            ),
        ),
        "device" to MarauderDisplayPage(
            title = "Device",
            parent = ROOT,
            items = listOf(
                MarauderDisplayItem("Back", MarauderDisplayColor.WHITE, destination = ROOT),
                MarauderDisplayItem("Save/Load Files", MarauderDisplayColor.CYAN, opensCommands = true),
                MarauderDisplayItem("Brightness", MarauderDisplayColor.YELLOW, command = "brightness -c"),
                MarauderDisplayItem("Device Info", MarauderDisplayColor.WHITE, command = "info"),
                MarauderDisplayItem("Settings", MarauderDisplayColor.BLUE, command = "settings"),
                MarauderDisplayItem("List SD Files", MarauderDisplayColor.GREEN, command = "ls /"),
            ),
        ),
    )
}
