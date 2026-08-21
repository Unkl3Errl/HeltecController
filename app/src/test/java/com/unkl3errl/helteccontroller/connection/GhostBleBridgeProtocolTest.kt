package com.unkl3errl.helteccontroller.connection

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GhostBleBridgeProtocolTest {
    @Test
    fun `short command uses one upstream-compatible frame`() {
        val frames = GhostBleBridgeProtocol.commandFrames(
            commandId = 0x12345678,
            command = "sd status".toByteArray(),
            mtu = 128,
        )

        assertEquals(1, frames.size)
        val frame = frames.single()
        assertArrayEquals(byteArrayOf(0x47, 0x42, 0x01, 0x01), frame.copyOfRange(0, 4))
        assertEquals(0, frame[5].toInt())
        assertArrayEquals(
            byteArrayOf(0x78, 0x56, 0x34, 0x12),
            frame.copyOfRange(6, 10),
        )
        assertEquals("sd status", frame.copyOfRange(12, frame.size).toString(Charsets.UTF_8))
    }

    @Test
    fun `long command fragments within negotiated mtu`() {
        val frames = GhostBleBridgeProtocol.commandFrames(
            commandId = 7,
            command = ByteArray(250) { 'x'.code.toByte() },
            mtu = 128,
        )

        assertEquals(3, frames.size)
        assertEquals(0x03, frames.first()[5].toInt())
        assertEquals(0x02, frames[1][5].toInt())
        assertEquals(0x00, frames.last()[5].toInt())
        assertTrue(frames.all { it.size <= 125 })
    }

    @Test
    fun `decoder retains split frame and returns data payload`() {
        val payload = "SD:OK\r\n".toByteArray()
        val frame = responseFrame(
            type = GhostBleBridgeProtocol.TYPE_DATA,
            commandId = 42,
            payload = payload,
        )
        val decoder = GhostBleBridgeProtocol.Decoder()

        assertTrue(decoder.feed(frame.copyOfRange(0, 5)).frames.isEmpty())
        val decoded = decoder.feed(frame.copyOfRange(5, frame.size))

        assertEquals(1, decoded.frames.size)
        assertEquals(42, decoded.frames.single().commandId)
        assertArrayEquals(payload, decoded.frames.single().payload)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `command longer than upstream limit is rejected`() {
        GhostBleBridgeProtocol.commandFrames(1, ByteArray(251), 128)
    }

    private fun responseFrame(type: Int, commandId: Int, payload: ByteArray): ByteArray =
        ByteArray(GhostBleBridgeProtocol.HEADER_BYTES + payload.size).also { frame ->
            frame[0] = 0x47
            frame[1] = 0x42
            frame[2] = 0x01
            frame[3] = type.toByte()
            frame[6] = (commandId and 0xff).toByte()
            frame[7] = ((commandId ushr 8) and 0xff).toByte()
            frame[8] = ((commandId ushr 16) and 0xff).toByte()
            frame[9] = ((commandId ushr 24) and 0xff).toByte()
            frame[10] = (payload.size and 0xff).toByte()
            frame[11] = ((payload.size ushr 8) and 0xff).toByte()
            payload.copyInto(frame, GhostBleBridgeProtocol.HEADER_BYTES)
        }
}
