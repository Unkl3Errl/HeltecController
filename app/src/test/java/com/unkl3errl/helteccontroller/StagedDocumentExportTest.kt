package com.unkl3errl.helteccontroller

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class StagedDocumentExportTest {
    @Test
    fun downloadFailureDoesNotOpenOrModifyDestination() {
        withCacheDirectory { cache ->
            val existing = ByteArrayOutputStream().apply { write("existing".toByteArray()) }
            var destinationOpened = false

            try {
                stageAndExportDocument(
                    cacheDirectory = cache,
                    temporaryPrefix = "failed-export-",
                    download = { output ->
                        output.write("partial".toByteArray())
                        throw IOException("link lost")
                    },
                    openDestination = {
                        destinationOpened = true
                        existing
                    },
                )
                fail("Expected the simulated transport failure")
            } catch (expected: IOException) {
                assertEquals("link lost", expected.message)
            }

            assertFalse(destinationOpened)
            assertArrayEquals("existing".toByteArray(), existing.toByteArray())
            assertEquals(emptyList<String>(), cache.list()?.toList().orEmpty())
        }
    }

    @Test
    fun successfulDownloadIsCopiedAfterItIsComplete() {
        withCacheDirectory { cache ->
            val payload = "complete export".toByteArray()
            val destination = ByteArrayOutputStream()

            val copied = stageAndExportDocument(
                cacheDirectory = cache,
                temporaryPrefix = "successful-export-",
                download = { output ->
                    output.write(payload)
                    payload.size.toLong()
                },
                openDestination = { destination },
            )

            assertEquals(payload.size.toLong(), copied)
            assertArrayEquals(payload, destination.toByteArray())
            assertEquals(emptyList<String>(), cache.list()?.toList().orEmpty())
        }
    }

    @Test
    fun inconsistentDownloadLengthDoesNotOpenDestination() {
        withCacheDirectory { cache ->
            var destinationOpened = false

            try {
                stageAndExportDocument(
                    cacheDirectory = cache,
                    temporaryPrefix = "short-export-",
                    download = { output ->
                        output.write(byteArrayOf(1, 2, 3))
                        4L
                    },
                    openDestination = {
                        destinationOpened = true
                        ByteArrayOutputStream()
                    },
                )
                fail("Expected an incomplete-download error")
            } catch (expected: IOException) {
                assertEquals(
                    "Download was incomplete: received 3 of 4 reported bytes",
                    expected.message,
                )
            }

            assertFalse(destinationOpened)
            assertEquals(emptyList<String>(), cache.list()?.toList().orEmpty())
        }
    }

    @Test
    fun missingPreparedExportDoesNotOpenDestination() {
        withCacheDirectory { cache ->
            var destinationOpened = false

            try {
                copyPreparedDocument(File(cache, "evicted-export.tmp")) {
                    destinationOpened = true
                    ByteArrayOutputStream()
                }
                fail("Expected the missing-export error")
            } catch (expected: IllegalArgumentException) {
                assertEquals("Prepared export is no longer available", expected.message)
            }

            assertFalse(destinationOpened)
        }
    }

    private fun withCacheDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("heltec-export-test-").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
