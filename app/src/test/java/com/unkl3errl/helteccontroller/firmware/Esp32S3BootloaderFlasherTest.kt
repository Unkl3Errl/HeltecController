package com.unkl3errl.helteccontroller.firmware

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Esp32S3BootloaderFlasherTest {
    @Test
    fun parsesStandardTwentyByteSecurityPayload() {
        val payload = ByteArray(20).apply {
            this[0] = 0x02
            this[4] = 0x06
            this[12] = 0x09
            this[16] = 0x34
            this[17] = 0x12
        }

        val parsed = parseEsp32S3SecurityInfo(payload)

        assertEquals(0x02L, parsed.flags)
        assertEquals(0x06, parsed.flashCryptCount)
        assertEquals(9, parsed.chipId)
    }

    @Test
    fun ignoresRomStatusBytesAfterSecurityPayload() {
        val replyData = ByteArray(22).apply {
            this[12] = 0x09
            this[20] = 0x00
            this[21] = 0x00
        }

        assertEquals(9, parseEsp32S3SecurityInfo(replyData).chipId)
    }

    @Test
    fun rejectsTruncatedSecurityPayload() {
        assertThrows(IllegalArgumentException::class.java) {
            parseEsp32S3SecurityInfo(ByteArray(19))
        }
    }

    @Test
    fun recognizesDirectUsbIoFailureAsRecoverable() {
        assertTrue(isRecoverableEsp32S3UsbFailure(IOException("USB endpoint disappeared")))
    }

    @Test
    fun recognizesWrappedUsbIoFailureAsRecoverable() {
        assertTrue(
            isRecoverableEsp32S3UsbFailure(
                IllegalStateException("flash stage failed", IOException("rc=-1")),
            ),
        )
    }

    @Test
    fun doesNotRetryFirmwareOrSecurityRejections() {
        assertFalse(
            isRecoverableEsp32S3UsbFailure(
                IllegalArgumentException("Secure Boot is enabled"),
            ),
        )
    }
}
