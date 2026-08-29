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
}
