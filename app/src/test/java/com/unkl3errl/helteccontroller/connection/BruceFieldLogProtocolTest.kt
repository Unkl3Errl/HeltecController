package com.unkl3errl.helteccontroller.connection

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BruceFieldLogProtocolTest {
    @Test
    fun calculatesStandardCrc32() {
        assertEquals("3610A686", BruceFieldLogProtocol.crc32Hex("hello".toByteArray()))
        assertEquals("00000000", BruceFieldLogProtocol.crc32Hex(ByteArray(0)))
    }

    @Test
    fun selectsOnlyClosedWellFormedSegments() {
        assertEquals(
            BruceArchiveSegment("session-000001-002.ndjson", 437824, "57B1123D"),
            BruceFieldLogProtocol.validatedSegment(
                "session-000001-002.ndjson",
                437824,
                "57b1123d",
                true,
            ),
        )
        assertEquals(
            null,
            BruceFieldLogProtocol.validatedSegment(
                "session-000001-003.ndjson",
                10,
                "12345678",
                false,
            ),
        )
        assertEquals(
            null,
            BruceFieldLogProtocol.validatedSegment("../config.json", 10, "12345678", true),
        )
    }

    @Test
    fun validatesEveryChunkIdentityField() {
        val segment = BruceArchiveSegment("session-000001-002.ndjson", 5, "3610A686")
        assertArrayEquals(
            "hello".toByteArray(),
            BruceFieldLogProtocol.decodeChunkFields(
                segment.name,
                segment.size,
                0,
                5,
                "base64",
                "aGVsbG8=",
                segment,
                0,
                5,
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            BruceFieldLogProtocol.decodeChunkFields(
                segment.name,
                segment.size,
                1,
                5,
                "base64",
                "aGVsbG8=",
                segment,
                0,
                5,
            )
        }
    }
}
