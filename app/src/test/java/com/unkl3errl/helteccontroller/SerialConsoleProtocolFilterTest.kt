package com.unkl3errl.helteccontroller

import org.junit.Assert.assertEquals
import org.junit.Test

class SerialConsoleProtocolFilterTest {
    @Test
    fun `hides bridge responses split across serial packets`() {
        val filter = SerialConsoleProtocolFilter()

        assertEquals("", filter.filter("@HELT"))
        assertEquals("", filter.filter("EC-BRIDGE 7 OK {\"saved\":true}\r\n"))
        assertEquals("ready\r\n", filter.filter("ready\r\n"))
    }

    @Test
    fun `hides echoed bridge commands with prompt and mixed case`() {
        val filter = SerialConsoleProtocolFilter()

        assertEquals("", filter.filter("  > @heltec-bridge 8 logger-files\n"))
        assertEquals("normal output\n", filter.filter("normal output\n"))
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

        assertEquals("", filter.filter("# SD:HOST:free=123\r\n"))
        assertEquals("", filter.filter("SD:OK:host-capacity\r\n"))
        assertEquals("", filter.filter("# \r\n"))
        assertEquals("ready\r\n", filter.filter("ready\r\n"))
    }

    @Test
    fun `shows an explicitly requested storage response through its terminator`() {
        val filter = SerialConsoleProtocolFilter()
        filter.showNextStorageResponse()

        assertEquals("SD:STATUS:mounted=true\r\n", filter.filter("SD:STATUS:mounted=true\r\n"))
        assertEquals("SD:OK\r\n", filter.filter("SD:OK\r\n"))
        assertEquals("", filter.filter("SD:HOST:free=123\r\n"))
    }

    @Test
    fun `background capacity acknowledgement cannot consume a requested response`() {
        val filter = SerialConsoleProtocolFilter()
        filter.showNextStorageResponse()

        assertEquals("", filter.filter("SD:HOST:free=123\r\nSD:OK:host-capacity\r\n"))
        assertEquals("SD:STATUS:mounted=true\r\n", filter.filter("SD:STATUS:mounted=true\r\n"))
        assertEquals("SD:OK\r\n", filter.filter("SD:OK\r\n"))
    }

    @Test
    fun `drops queued prompts before an explicitly requested storage response`() {
        val filter = SerialConsoleProtocolFilter()
        filter.showNextStorageResponse()

        assertEquals(
            "SD:STATUS:mounted=true\r\nSD:OK\r\n",
            filter.filter("# # # SD:STATUS:mounted=true\r\nSD:OK\r\n"),
        )
    }
}
