package com.unkl3errl.helteccontroller.connection

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SdStorageProtocolTest {
    @Test
    fun buildsAndroidHostCapacityCommand() {
        assertEquals(
            "sd host 128000000000 64000000000",
            SdStorageProtocol.hostCapacityCommand(
                VirtualSdCapacity(totalBytes = 128_000_000_000, freeBytes = 64_000_000_000),
            ),
        )
    }

    @Test
    fun archivePolicyKeepsFirmwareInputsOnDevice() {
        assertFalse(
            SdStorageProtocol.isArchiveCandidate(
                PersistentUsbKind.GHOSTESP,
                "/mnt/ghostesp/config.cfg",
            ),
        )
        assertFalse(
            SdStorageProtocol.isArchiveCandidate(
                PersistentUsbKind.GHOSTESP,
                "/mnt/ghostesp/apps/tool.gapp",
            ),
        )
        assertFalse(
            SdStorageProtocol.isArchiveCandidate(
                PersistentUsbKind.MARAUDER,
                "/SCRIPTS/startup.txt",
            ),
        )
        assertFalse(
            SdStorageProtocol.isArchiveCandidate(
                PersistentUsbKind.BRUCE,
                "/BruceFieldLogs/session-000001-000.ndjson",
            ),
        )
        assertFalse(
            SdStorageProtocol.isArchiveCandidate(
                PersistentUsbKind.BRUCE,
                "/BruceScripts/startup.js",
            ),
        )
        assertFalse(
            SdStorageProtocol.isArchiveCandidate(
                PersistentUsbKind.BRUCE,
                "/PortalTemplates/login.html",
            ),
        )
    }

    @Test
    fun archivePolicySelectsGeneratedCaptureFiles() {
        assertTrue(
            SdStorageProtocol.isArchiveCandidate(
                PersistentUsbKind.GHOSTESP,
                "/mnt/ghostesp/gps/wardriving_3.csv",
            ),
        )
        assertTrue(
            SdStorageProtocol.isArchiveCandidate(
                PersistentUsbKind.GHOSTESP,
                "/mnt/ghostesp/pcaps/rawscan_2.pcap",
            ),
        )
        assertTrue(
            SdStorageProtocol.isArchiveCandidate(
                PersistentUsbKind.MARAUDER,
                "/wardrive_7.log",
            ),
        )
        assertTrue(
            SdStorageProtocol.isArchiveCandidate(
                PersistentUsbKind.MARAUDER,
                "/tracker_2.gpx",
            ),
        )
        assertTrue(
            SdStorageProtocol.isArchiveCandidate(
                PersistentUsbKind.BRUCE,
                "/BrucePCAP/raw_12.pcap",
            ),
        )
        assertTrue(
            SdStorageProtocol.isArchiveCandidate(
                PersistentUsbKind.BRUCE,
                "/ProbeData/credentials.txt",
            ),
        )
    }

    @Test
    fun parsesNamesWithSpacesAndOptionalModifiedTime() {
        val result = SdStorageProtocol.parseListing(
            listOf(
                "SD:LIST:/",
                "SD:DIR:[0] captures",
                "SD:FILE:[1] wardrive august.csv 1234 1723456789",
                "SD:FILE:[2] legacy.pcap 42",
                "SD:OK:listed 3 entries",
            ),
            "/",
        )

        assertEquals(listOf("/captures"), result.directories)
        assertEquals(SdRemoteFile("/wardrive august.csv", 1234, 1723456789), result.files[0])
        assertEquals(SdRemoteFile("/legacy.pcap", 42, 0), result.files[1])
    }

    @Test
    fun decodesVerifiedReadChunk() {
        val decoded = SdStorageProtocol.decodeRead(
            listOf(
                "SD:READ:OFFSET:3",
                "SD:READ:LENGTH:5",
                "SD:READ:DATA:aGVs",
                "SD:READ:DATA:bG8=",
                "SD:OK",
            ),
            expectedOffset = 3,
            expectedLength = 5,
        )

        assertArrayEquals("hello".toByteArray(), decoded)
    }

    @Test
    fun rejectsTraversalAndShortChunks() {
        assertThrows(IllegalArgumentException::class.java) {
            SdStorageProtocol.quotedPath("/../secret")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SdStorageProtocol.decodeRead(
                listOf("SD:READ:OFFSET:0", "SD:READ:LENGTH:4", "SD:READ:DATA:eA=="),
                expectedOffset = 0,
                expectedLength = 4,
            )
        }
    }

    @Test
    fun parsesChecksummedFileIdentity() {
        assertEquals(
            SdRemoteChecksum(437824, "A1B2C3D4"),
            SdStorageProtocol.parseChecksum(
                listOf("SD:CRC32:a1b2c3d4", "SD:CRC32:SIZE:437824", "SD:OK"),
            ),
        )
    }
}
