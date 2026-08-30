package com.unkl3errl.helteccontroller.marauder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarauderDisplayMenuTest {
    @Test
    fun everyMenuDestinationAndActionIsUsable() {
        assertTrue(MarauderDisplayMenu.pages.containsKey(MarauderDisplayMenu.ROOT))
        MarauderDisplayMenu.pages.values.forEach { page ->
            page.parent?.let { assertTrue(MarauderDisplayMenu.pages.containsKey(it)) }
            page.items.forEach { item ->
                item.destination?.let { assertTrue(MarauderDisplayMenu.pages.containsKey(it)) }
                assertEquals(
                    "Display item must have exactly one action: ${item.label}",
                    1,
                    listOf(item.destination != null, item.command != null, item.opensCommands).count { it },
                )
            }
        }
    }

    @Test
    fun correctedListCommandsAreUsedByTheDisplay() {
        val commands = MarauderDisplayMenu.pages.getValue("general").items.mapNotNull { it.command }
        assertTrue("list -c" in commands)
        assertTrue("list -s" in commands)
        assertTrue("list -p" in commands)
    }

    @Test
    fun deviceSubmenuEntriesResolveToWorkingActions() {
        val items = MarauderDisplayMenu.pages.getValue("device").items.associateBy { it.label }

        assertEquals(MarauderDisplayMenu.ROOT, items.getValue("Back").destination)
        assertTrue(items.getValue("Save/Load Files").opensCommands)
        assertEquals("brightness -c", items.getValue("Brightness").command)
        assertEquals("info", items.getValue("Device Info").command)
        assertEquals("settings", items.getValue("Settings").command)
        assertEquals("ls /", items.getValue("List SD Files").command)
    }
}
