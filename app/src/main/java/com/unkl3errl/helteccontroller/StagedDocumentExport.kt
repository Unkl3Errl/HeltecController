package com.unkl3errl.helteccontroller

import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * Downloads content into private app storage before opening the user-selected document.
 * A transport failure therefore cannot truncate or otherwise modify that document.
 */
internal fun stageAndExportDocument(
    cacheDirectory: File,
    temporaryPrefix: String,
    download: (OutputStream) -> Long,
    openDestination: () -> OutputStream,
): Long {
    val staged = File.createTempFile(temporaryPrefix, ".part", cacheDirectory)
    return try {
        val reportedBytes = staged.outputStream().buffered().use(download)
        val stagedBytes = staged.length()
        if (reportedBytes != stagedBytes) {
            throw IOException(
                "Download was incomplete: received $stagedBytes of $reportedBytes reported bytes",
            )
        }
        copyPreparedDocument(staged, openDestination)
    } finally {
        if (!staged.delete() && staged.exists()) staged.deleteOnExit()
    }
}

/** Copies an already-prepared private file without taking ownership of the destination URI. */
internal fun copyPreparedDocument(
    source: File,
    openDestination: () -> OutputStream,
): Long {
    require(source.isFile) { "Prepared export is no longer available" }
    val sourceBytes = source.length()
    source.inputStream().buffered().use { input ->
        openDestination().buffered().use { output ->
            input.copyTo(output)
            output.flush()
        }
    }
    return sourceBytes
}
