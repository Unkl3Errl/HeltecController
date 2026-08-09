package com.unkl3errl.helteccontroller

import org.junit.Assert.assertEquals
import org.junit.Test

class SerialConsoleTextTest {
    @Test
    fun normalizesMixedLineEndings() {
        val consoleText = SerialConsoleText()

        assertEquals(
            "first\nsecond\nthird\nfourth",
            consoleText.normalize("first\r\nsecond\rthird\nfourth"),
        )
    }

    @Test
    fun doesNotDuplicateCrLfSplitAcrossUsbPackets() {
        val consoleText = SerialConsoleText()

        assertEquals("first\n", consoleText.normalize("first\r"))
        assertEquals("second\n", consoleText.normalize("\nsecond\r\n"))
    }

    @Test
    fun removesNullBytesAndResetRestoresFreshState() {
        val consoleText = SerialConsoleText()

        assertEquals("one\n", consoleText.normalize("one\u0000\r"))
        consoleText.reset()
        assertEquals("\ntwo", consoleText.normalize("\ntwo"))
    }

    @Test
    fun stripsAnsiColorSequencesAcrossUsbPackets() {
        val consoleText = SerialConsoleText()

        assertEquals("ghost", consoleText.normalize("\u001b[38;5;36mghost\u001b["))
        assertEquals("> ", consoleText.normalize("0m> "))
    }
}
