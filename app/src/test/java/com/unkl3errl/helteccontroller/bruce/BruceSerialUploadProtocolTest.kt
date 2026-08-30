package com.unkl3errl.helteccontroller.bruce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BruceSerialUploadProtocolTest {
    @Test
    fun storageWriteAddsExactSizeAndEofFraming() {
        val upload = BruceSerialUploadProtocol.prepare(
            BruceSerialUploadProtocol.STORAGE_WRITE_ID,
            "storage write /Bruce/test.txt",
            "alpha\r\nbeta",
        )

        assertEquals("storage write /Bruce/test.txt 11", upload.command)
        assertEquals(11, upload.contentBytes)
        assertEquals("alpha\nbeta\nEOF\n", upload.wirePayload.toString(Charsets.UTF_8))
    }

    @Test
    fun storageWriteHonorsLargerRequestedBuffer() {
        val upload = BruceSerialUploadProtocol.prepare(
            BruceSerialUploadProtocol.STORAGE_WRITE_ID,
            "storage write /Bruce/test.txt 4096",
            "payload",
        )

        assertEquals("storage write /Bruce/test.txt 4096", upload.command)
    }

    @Test
    fun encryptUsesSameEofProtocolWithoutChangingCommand() {
        val upload = BruceSerialUploadProtocol.prepare(
            BruceSerialUploadProtocol.ENCRYPT_ID,
            "encrypt /Bruce/secret.enc password",
            "secret text",
        )

        assertEquals("encrypt /Bruce/secret.enc password", upload.command)
        assertEquals("secret text\nEOF\n", upload.wirePayload.toString(Charsets.UTF_8))
    }

    @Test(expected = IllegalArgumentException::class)
    fun reservedEofLineIsRejected() {
        BruceSerialUploadProtocol.prepare(
            BruceSerialUploadProtocol.STORAGE_WRITE_ID,
            "storage write /Bruce/test.txt",
            "before\nEOF\nafter",
        )
    }

    @Test
    fun onlyStagedCommandsAreDetected() {
        assertEquals(
            BruceSerialUploadProtocol.STORAGE_WRITE_ID,
            BruceSerialUploadProtocol.commandIdFor("storage write /Bruce/test.txt"),
        )
        assertEquals(
            BruceSerialUploadProtocol.ENCRYPT_ID,
            BruceSerialUploadProtocol.commandIdFor("encrypt /Bruce/test.enc password"),
        )
        assertNull(BruceSerialUploadProtocol.commandIdFor("storage stat /Bruce/test.txt"))
    }
}
