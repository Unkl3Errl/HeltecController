package com.unkl3errl.helteccontroller.guided

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidedCommandCatalogTest {
    @Test
    fun packagedCatalogHasEveryFirmwareAndAllTemplatesRender() {
        val catalogFile = listOf(
            File("src/main/assets/firmware_commands.tsv"),
            File("app/src/main/assets/firmware_commands.tsv"),
        ).firstOrNull(File::isFile) ?: error("Packaged command catalog was not found")
        val commands = GuidedCommandCatalog.parse(catalogFile.readText())

        assertTrue(commands.count { it.firmware == GuidedFirmware.BRUCE } >= 70)
        assertTrue(commands.count { it.firmware == GuidedFirmware.GHOSTESP } >= 200)
        assertTrue(commands.count { it.firmware == GuidedFirmware.MARAUDER } >= 85)
        commands.forEach { command ->
            val values = command.parameters.associate { parameter ->
                parameter.token to (parameter.choices.firstOrNull() ?: "sample")
            }
            val rendered = command.render(values)
            assertFalse("Unrendered template: $rendered", rendered.contains('<'))
        }
    }

    @Test
    fun parsesFieldsChoicesAndRendersCommand() {
        val catalog = GuidedCommandCatalog.parse(
            "bruce\tWi-Fi\ttest-connect\tConnect\tConnect to a network.\twifi connect <ssid> [password]\treview",
        )

        val command = catalog.single()
        assertEquals(listOf("Ssid", "Password"), command.parameters.map { it.label })
        assertTrue(command.parameters.first().required)
        assertFalse(command.parameters.last().required)
        assertTrue(command.parameters.last().secret)
        assertEquals(
            "wifi connect LabNet secret",
            command.render(mapOf("<ssid>" to "LabNet", "[password]" to "secret")),
        )
    }

    @Test
    fun emptyOptionalFieldIsRemovedAndWhitespaceIsCollapsed() {
        val command = GuidedCommandCatalog.parse(
            "ghostesp\tWi-Fi\ttest-scan\tScan\tScan Wi-Fi.\tscanap [seconds]\tsafe",
        ).single()

        assertEquals("scanap", command.render(emptyMap()))
    }

    @Test
    fun commaSeparatedRequiredValueBecomesChoice() {
        val command = GuidedCommandCatalog.parse(
            "marauder\tSystem\ttest-setting\tSetting\tChange setting.\tsettings -s <setting> <enable,disable>\treview",
        ).single()

        assertEquals(listOf("enable", "disable"), command.parameters.last().choices)
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingRequiredFieldIsRejected() {
        val command = GuidedCommandCatalog.parse(
            "bruce\tRF\ttest-rf\tSend\tSend RF.\trf tx <key>\tactive",
        ).single()

        command.render(emptyMap())
    }
}
