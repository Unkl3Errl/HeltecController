package com.unkl3errl.helteccontroller

import org.junit.Assert.assertEquals
import org.junit.Test

class SerialConsoleProtocolFilterTest {
    @Test
    fun `hides bridge responses split across serial packets`() {
        val filter = SerialConsoleProtocolFilter()

        assertEquals("", filter.filter("@HELT"))
        assertEquals("", filter.filter("EC-BRIDGE 7 OK {\"saved\":true}\r\n"))
        assertEquals("ready\n", filter.filter("ready\r\n"))
    }

    @Test
    fun `hides echoed bridge commands with prompt and mixed case`() {
        val filter = SerialConsoleProtocolFilter()

        assertEquals("", filter.filter("  > @heltec-bridge 8 logger-files\n"))
        assertEquals("normal output\n", filter.filter("normal output\n"))
    }

    @Test
    fun `hides Bruce command-wrapped bridge lines at every packet boundary`() {
        val line = "# # COMMAND: @HELTEC-BRIDGE 9 logger-status\r\n"

        for (splitAt in 0..line.length) {
            val filter = SerialConsoleProtocolFilter()
            val visible = filter.filter(line.substring(0, splitAt)) +
                filter.filter(line.substring(splitAt))

            assertEquals("split at $splitAt", "", visible)
        }
    }

    @Test
    fun `hides an orphaned bridge response at every packet boundary`() {
        val line = "8 OK {\"formatVersion\":1,\"initialized\":true}\r\n"

        for (splitAt in 0..line.length) {
            val filter = SerialConsoleProtocolFilter()
            val visible = filter.filter(line.substring(0, splitAt)) +
                filter.filter(line.substring(splitAt))

            assertEquals("split at $splitAt", "", visible)
        }
    }

    @Test
    fun `keeps ordinary numeric console output visible`() {
        val filter = SerialConsoleProtocolFilter()

        assertEquals("8 devices found\n", filter.filter("8 devices found\r\n"))
    }

    @Test
    fun `hides an orphaned bridge error response`() {
        val filter = SerialConsoleProtocolFilter()

        assertEquals("", filter.filter("12 ERROR {\"error\":\"invalid request\"}\r\n"))
    }

    @Test
    fun `keeps ordinary Bruce command echoes visible`() {
        val filter = SerialConsoleProtocolFilter()

        assertEquals("# COMMAND: info\n", filter.filter("# COMMAND: info\r\n"))
        assertEquals("Bruce v1.16.1\n", filter.filter("Bruce v1.16.1\r\n"))
    }

    @Test
    fun `does not delay normal partial console text`() {
        val filter = SerialConsoleProtocolFilter()

        assertEquals("M", filter.filter("M"))
        assertEquals("arauder> ", filter.filter("arauder> "))
    }

    @Test
    fun `hides background storage protocol and prompt fragments`() {
        val filter = SerialConsoleProtocolFilter()

        assertEquals("", filter.filter("sd list /mnt/ghostesp/scans\r\n"))
        assertEquals("", filter.filter("> sd read /mnt/ghostesp/scans/test.bin 0 128\r\n"))
        assertEquals("", filter.filter("# sD ack /mnt/ghostesp/scans/test.bin 128 C0FFEE\r\n"))
        assertEquals("", filter.filter("# SD:HOST:free=123\r\n"))
        assertEquals("", filter.filter("SD:OK:host-capacity\r\n"))
        assertEquals("", filter.filter("# \r\n"))
        assertEquals("ready\n", filter.filter("ready\r\n"))
    }

    @Test
    fun `hides bare and ANSI prompts after mixed line endings`() {
        val filter = SerialConsoleProtocolFilter()

        assertEquals(
            "first\nsecond\nthird\n",
            filter.filter(
                "first\r>\rsecond\r\n\u001b[38;5;36m>\u001b[0m\r\nthird\n#\n",
            ),
        )

        val coloredPrompt = "\u001b[38;5;36m>\u001b[0m\r\n"
        for (splitAt in 0..coloredPrompt.length) {
            val splitFilter = SerialConsoleProtocolFilter()
            assertEquals(
                "split at $splitAt",
                "",
                splitFilter.filter(coloredPrompt.substring(0, splitAt)) +
                    splitFilter.filter(coloredPrompt.substring(splitAt)),
            )
        }
    }

    @Test
    fun `shows an explicitly requested storage response through its terminator`() {
        val filter = SerialConsoleProtocolFilter()
        filter.showNextStorageResponse()

        assertEquals("SD:STATUS:mounted=true\n", filter.filter("SD:STATUS:mounted=true\r\n"))
        assertEquals("SD:OK\n", filter.filter("SD:OK\r\n"))
        assertEquals("", filter.filter("SD:HOST:free=123\r\n"))
    }

    @Test
    fun `background capacity acknowledgement cannot consume a requested response`() {
        val filter = SerialConsoleProtocolFilter()
        filter.showNextStorageResponse()

        assertEquals("", filter.filter("SD:HOST:free=123\r\nSD:OK:host-capacity\r\n"))
        assertEquals("SD:STATUS:mounted=true\n", filter.filter("SD:STATUS:mounted=true\r\n"))
        assertEquals("SD:OK\n", filter.filter("SD:OK\r\n"))
    }

    @Test
    fun `drops queued prompts before an explicitly requested storage response`() {
        val filter = SerialConsoleProtocolFilter()
        filter.showNextStorageResponse()

        assertEquals(
            "SD:STATUS:mounted=true\nSD:OK\n",
            filter.filter("# # # SD:STATUS:mounted=true\r\nSD:OK\r\n"),
        )
    }
}
