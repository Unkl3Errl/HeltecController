package com.unkl3errl.helteccontroller.firmware

import com.unkl3errl.helteccontroller.detection.FirmwareKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirmwareCatalogSelectionTest {
    @Test
    fun sameDayCachedCatalogCannotReplaceNewerBundledFirmware() {
        val bundled = catalog("2026-08-21", "1.16.1-mobile.3")
        val staleCache = catalog("2026-08-21", "1.16.1-mobile.2")

        assertFalse(staleCache.isAtLeastAsNewAs(bundled))
        assertTrue(bundled.isAtLeastAsNewAs(staleCache))
    }

    @Test
    fun olderDatedCatalogCannotReplaceBundledCatalog() {
        val bundled = catalog("2026-08-21", "1.16.1-mobile.3")
        val older = catalog("2026-08-20", "1.16.1-mobile.4")

        assertFalse(older.isAtLeastAsNewAs(bundled))
    }

    private fun catalog(date: String, bruceVersion: String): FirmwareCatalog = FirmwareCatalog(
        generatedAt = date,
        releases = FirmwareKind.entries.associateWith { kind ->
            release(kind, if (kind == FirmwareKind.BRUCE) bruceVersion else "1.0.0-mobile.1")
        },
    )

    private fun release(kind: FirmwareKind, version: String) = FirmwareRelease(
        kind = kind,
        displayName = kind.name,
        version = version,
        releasedAt = "2026-08-21",
        summary = "test",
        sourceRepository = "https://github.com/example/source",
        sourceCommit = "0".repeat(40),
        upstream = FirmwareUpstream(
            repository = "https://github.com/example/upstream",
            latestReleaseApi = "https://api.github.com/repos/example/upstream/releases/latest",
            baselineVersion = "1.0.0",
            baselineCommit = "1".repeat(40),
        ),
        imageAsset = "test.bin",
        imageUrl = "https://github.com/example/source/releases/download/v1/test.bin",
        imageSha256 = "2".repeat(64),
        imageSizeBytes = 65_536,
    )
}
