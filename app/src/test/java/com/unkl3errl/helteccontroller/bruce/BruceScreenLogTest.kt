package com.unkl3errl.helteccontroller.bruce

import org.junit.Assert.assertEquals
import org.junit.Test

class BruceScreenLogTest {
    @Test
    fun parsesScreenInfoAndText() {
        val data = byteArrayOf(
            0xAA.toByte(), 8, 99, 0, 240.toByte(), 0, 135.toByte(), 0,
            0xAA.toByte(), 18, 16, 0, 4, 0, 8, 0, 1, 0xFF.toByte(), 0xFF.toByte(), 0, 0,
            'B'.code.toByte(), 'r'.code.toByte(), 'u'.code.toByte(), 'c'.code.toByte(), 'e'.code.toByte(),
        )
        val frame = BruceScreenLog.parse(data)
        assertEquals(240, frame.width)
        assertEquals(135, frame.height)
        assertEquals("Bruce", frame.commands.last().text)
    }

    @Test
    fun stopsCleanlyAtInterruptedPacket() {
        val frame = BruceScreenLog.parse(byteArrayOf(0xAA.toByte(), 10, 0, 0))
        assertEquals(0, frame.commands.size)
    }
}
