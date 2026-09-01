package com.unkl3errl.helteccontroller.bruce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BruceDisplayMenuTest {
    @Test
    fun hierarchyMatchesOfficialHeltecOledMenu() {
        assertEquals(
            listOf("Dashboard", "GPS monitor", "LoRa receiver", "Field logger", "System"),
            BruceDisplayMenu.pages.getValue(BruceDisplayMenu.ROOT).items.map { it.label },
        )
        assertEquals(
            listOf("Always on", "Turn off after 15s", "Turn off after 30s", "Turn off after 45s", "Turn off after 60s", "Back"),
            BruceDisplayMenu.pages.getValue("display").items.map { it.label },
        )
    }

    @Test
    fun everyDestinationAndActionIsUsable() {
        BruceDisplayMenu.pages.values.forEach { page ->
            page.parent?.let { assertTrue(BruceDisplayMenu.pages.containsKey(it)) }
            page.items.forEach { item ->
                item.destination?.let { assertTrue(BruceDisplayMenu.pages.containsKey(it)) }
                assertEquals(
                    "Display item must have one destination or action: ${item.label}",
                    1,
                    listOf(item.destination != null, item.action != null).count { it },
                )
            }
        }
        assertEquals(
            BruceDisplayAction.FIELD_LOG_START,
            BruceDisplayMenu.pages.getValue("field_log").items[1].action,
        )
    }

    @Test
    fun everyOfficialSubmenuEntryResolvesToItsLabeledAction() {
        val actions = BruceDisplayMenu.pages.values
            .flatMap { it.items }
            .mapNotNull { item -> item.action?.let { item.label to it } }
            .toMap()

        assertEquals(BruceDisplayAction.GPS_TOGGLE, actions.getValue("Toggle monitor"))
        assertEquals(BruceDisplayAction.LORA_TOGGLE, actions.getValue("Toggle receiver"))
        assertEquals(BruceDisplayAction.FIELD_LOG_START, actions.getValue("Start GPS+BLE+WiFi"))
        assertEquals(BruceDisplayAction.FIELD_LOG_STOP, actions.getValue("Stop logging"))
        assertEquals(BruceDisplayAction.DEVICE_INFO, actions.getValue("Device info"))
        assertEquals(BruceDisplayAction.SLEEP, actions.getValue("Sleep (PRG wake)"))
        assertEquals(BruceDisplayAction.POWER_DOWN, actions.getValue("Power down"))
    }
}
