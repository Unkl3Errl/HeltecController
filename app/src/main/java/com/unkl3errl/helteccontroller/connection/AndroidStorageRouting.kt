package com.unkl3errl.helteccontroller.connection

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.FileOutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.EnumMap
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32

/**
 * Drains each firmware's flash-backed virtual SD into a user-selected Android folder.
 * A device file is removed only after a complete Android document has been closed and verified.
 */
object AndroidStorageRouting {
    private const val PREFERENCES = "android_storage_routing"
    private const val ROOT_URI = "root_uri"
    private val lock = Any()
    private val mirrors = EnumMap<PersistentUsbKind, StorageSpoolMirror>(PersistentUsbKind::class.java)

    fun attach(context: Context, session: PersistentUsbSerialSession) {
        synchronized(lock) {
            mirrors.getOrPut(session.kind) {
                StorageSpoolMirror(context.applicationContext, session)
            }
        }
    }

    fun selectedRoot(context: Context): Uri? {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val uri = preferences.getString(ROOT_URI, null)?.let(Uri::parse) ?: return null
        val retained = context.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == uri && permission.isReadPermission && permission.isWritePermission
        }
        if (retained) return uri
        preferences.edit().remove(ROOT_URI).apply()
        return null
    }

    fun selectRoot(context: Context, uri: Uri) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(ROOT_URI, uri.toString())
            .apply()
        synchronized(lock) { mirrors.values.toList() }.forEach { it.requestSync() }
    }

    fun syncNow(
        context: Context,
        kind: PersistentUsbKind,
        completion: (String) -> Unit,
    ) {
        val session = PersistentDeviceConnections.usb(context, kind)
        val mirror = synchronized(lock) {
            mirrors.getOrPut(kind) { StorageSpoolMirror(context.applicationContext, session) }
        }
        mirror.requestSync(completion)
    }
}

private class StorageSpoolMirror(
    private val context: Context,
    private val session: PersistentUsbSerialSession,
) : PersistentUsbSerialSession.Listener {
    private companion object {
        const val QUIET_WINDOW_MS = 10_000L
        const val SYNC_INTERVAL_SECONDS = 10L
        const val COMMAND_TIMEOUT_SECONDS = 8L
        const val READ_CHUNK_BYTES = 768L
        const val BRUCE_FIELD_READ_CHUNK_BYTES = 384
        const val MAX_DIRECTORIES = 128
        const val MAX_FILES = 512
    }

    private data class Observation(
        val size: Long,
        val modifiedSeconds: Long,
        val unchangedSinceMs: Long,
    )

    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val running = AtomicBoolean(false)
    private val callbacks = CopyOnWriteArrayList<(String) -> Unit>()
    private val collector = SdResponseCollector()
    private val bruceCollector = HeltecBridgeResponseCollector()
    private val observations = mutableMapOf<String, Observation>()
    private val deviceRoot: String = when (session.kind) {
        PersistentUsbKind.GHOSTESP -> "/mnt/ghostesp"
        PersistentUsbKind.BRUCE, PersistentUsbKind.MARAUDER -> "/"
    }

    init {
        session.addListener(this, receiveExclusiveData = true)
        executor.scheduleWithFixedDelay(
            ::runAutomaticSync,
            SYNC_INTERVAL_SECONDS,
            SYNC_INTERVAL_SECONDS,
            TimeUnit.SECONDS,
        )
    }

    fun requestSync(completion: ((String) -> Unit)? = null) {
        completion?.let(callbacks::add)
        executor.execute(::runRequestedSync)
    }

    override fun onStatus(message: String, connected: Boolean) {
        if (connected) executor.schedule(::runAutomaticSync, 2, TimeUnit.SECONDS)
    }

    override fun onData(data: ByteArray) {
        collector.accept(data)
        if (session.kind == PersistentUsbKind.BRUCE) bruceCollector.accept(data)
    }

    override fun onError(message: String) = Unit

    private fun runAutomaticSync() {
        if (AndroidStorageRouting.selectedRoot(context) == null || !session.isConnected) return
        runSync(notify = false)
    }

    private fun runRequestedSync() = runSync(notify = true)

    private fun runSync(notify: Boolean) {
        if (!running.compareAndSet(false, true)) return
        val result = runCatching { drainStableFiles() }
            .fold(
                onSuccess = { count ->
                    if (count == 0) "${session.kind.displayName} storage is caught up"
                    else "Archived and released $count ${session.kind.displayName} file${if (count == 1) "" else "s"}"
                },
                onFailure = { error ->
                    "${session.kind.displayName} storage sync paused: " +
                        (error.message ?: error.javaClass.simpleName)
                },
            )
        running.set(false)
        if (notify || callbacks.isNotEmpty()) {
            callbacks.toList().also(callbacks::removeAll).forEach { callback -> callback(result) }
        }
    }

    private fun drainStableFiles(): Int {
        check(session.isConnected) { "USB is not connected" }
        val rootUri = AndroidStorageRouting.selectedRoot(context)
            ?: error("Choose an Android storage folder first")
        val selectedRoot = DocumentFile.fromTreeUri(context, rootUri)
            ?: error("Android can no longer open the selected folder")
        check(selectedRoot.canWrite()) { "The selected Android folder is not writable" }
        val archiveRoot = selectedRoot.findFile(session.kind.displayName)
            ?: selectedRoot.createDirectory(session.kind.displayName)
            ?: error("Could not create the ${session.kind.displayName} archive folder")

        val remoteFiles = listRemoteFiles()
        observations.keys.retainAll(remoteFiles.mapTo(mutableSetOf(), SdRemoteFile::path))

        var released = if (session.kind == PersistentUsbKind.BRUCE) {
            val fieldLogs = archiveRoot.findFile("FieldLogs")
                ?: archiveRoot.createDirectory("FieldLogs")
                ?: error("Could not create the Bruce FieldLogs archive folder")
            drainBruceFieldLogs(fieldLogs)
        } else {
            0
        }
        val now = SystemClock.elapsedRealtime()
        val stable = remoteFiles.filter { file ->
            if (!isArchiveCandidate(file.path)) return@filter false
            val previous = observations[file.path]
            val unchanged = previous?.size == file.size &&
                previous.modifiedSeconds == file.modifiedSeconds
            val unchangedSince = if (unchanged) previous!!.unchangedSinceMs else now
            observations[file.path] = Observation(file.size, file.modifiedSeconds, unchangedSince)
            unchanged && now - unchangedSince >= QUIET_WINDOW_MS
        }.sortedWith(compareBy<SdRemoteFile> { if (it.modifiedSeconds == 0L) Long.MAX_VALUE else it.modifiedSeconds }
            .thenBy { it.path })

        stable.forEach { file ->
            archiveFile(archiveRoot, file)
            observations.remove(file.path)
            released++
        }
        return released
    }

    private fun drainBruceFieldLogs(directory: DocumentFile): Int {
        val listing = bridgeTransact("logger-files")
        val segments = BruceFieldLogProtocol.parseArchiveSegments(listing)
            .sortedBy(BruceArchiveSegment::name)
        var released = 0
        segments.forEach { segment ->
            val sourceName = segment.name
            val existing = directory.findFile(sourceName)
            if (existing != null && documentMatches(existing, segment)) {
                acknowledgeBruceSegment(segment)
                released++
                return@forEach
            }

            val finalName = if (existing == null) {
                sourceName
            } else {
                availableChecksumName(directory, sourceName, segment.crc32)
            }
            val alreadyFinal = directory.findFile(finalName)
            if (alreadyFinal != null && documentMatches(alreadyFinal, segment)) {
                acknowledgeBruceSegment(segment)
                released++
                return@forEach
            }

            val partialName = ".$finalName.partial-${segment.crc32}"
            var partial = directory.findFile(partialName)
                ?: directory.createFile("application/x-ndjson", partialName)
                ?: error("Could not create a resumable Bruce field-log document")
            if (!partial.isFile || partial.length() > segment.size) {
                runCatching { partial.delete() }
                partial = directory.createFile("application/x-ndjson", partialName)
                    ?: error("Could not replace an invalid Bruce field-log partial")
            }

            appendBruceSegment(partial, segment)
            if (!documentMatches(partial, segment)) {
                runCatching { partial.delete() }
                error("Bruce field-log checksum verification failed; the source was retained")
            }
            check(partial.renameTo(finalName)) {
                "Android could not finalize Bruce field log $finalName"
            }
            val finalized = directory.findFile(finalName)
                ?: error("Android could not find finalized Bruce field log $finalName")
            check(documentMatches(finalized, segment)) {
                "Final Bruce field-log verification failed; the source was retained"
            }
            acknowledgeBruceSegment(segment)
            released++
        }
        return released
    }

    private fun appendBruceSegment(document: DocumentFile, segment: BruceArchiveSegment) {
        var offset = document.length()
        check(offset in 0..segment.size) { "Invalid Bruce field-log resume offset" }
        if (offset == segment.size) return
        val descriptor = context.contentResolver.openFileDescriptor(document.uri, "rw")
            ?: error("Android could not reopen the Bruce field-log partial")
        descriptor.use {
            FileOutputStream(it.fileDescriptor).use { output ->
                output.channel.position(offset)
                while (offset < segment.size) {
                    val length = minOf(
                        BRUCE_FIELD_READ_CHUNK_BYTES.toLong(),
                        segment.size - offset,
                    ).toInt()
                    val payload = bridgeTransact(
                        "logger-read",
                        mapOf(
                            "name" to segment.name,
                            "offset" to offset.toString(),
                            "length" to length.toString(),
                        ),
                    )
                    output.write(
                        BruceFieldLogProtocol.decodeChunk(payload, segment, offset, length),
                    )
                    offset += length
                }
                output.flush()
                output.channel.force(true)
            }
        }
        check(document.length() == segment.size) {
            "Android reported an incomplete Bruce field-log partial"
        }
    }

    private fun acknowledgeBruceSegment(segment: BruceArchiveSegment) {
        val response = bridgeTransact(
            "logger-ack",
            mapOf(
                "name" to segment.name,
                "size" to segment.size.toString(),
                "crc32" to segment.crc32,
            ),
        )
        check(response.optString("released") == segment.name) {
            "Bruce did not confirm release of ${segment.name}"
        }
        check(response.optLong("sizeBytes", -1L) == segment.size) {
            "Bruce acknowledged the wrong field-log size"
        }
        check(response.optString("crc32").equals(segment.crc32, ignoreCase = true)) {
            "Bruce acknowledged the wrong field-log checksum"
        }
    }

    private fun bridgeTransact(
        action: String,
        values: Map<String, String> = emptyMap(),
    ): JSONObject = session.withExclusiveCommands { send ->
        bruceCollector.collect(action, values, send, COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun documentMatches(document: DocumentFile, segment: BruceArchiveSegment): Boolean {
        if (!document.isFile || document.length() != segment.size) return false
        return runCatching { documentCrc32(document) }
            .getOrNull()
            ?.equals(segment.crc32, ignoreCase = true) == true
    }

    private fun documentCrc32(document: DocumentFile): String {
        val checksum = CRC32()
        context.contentResolver.openInputStream(document.uri).use { input ->
            checkNotNull(input) { "Android could not verify the archive document" }
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val received = input.read(buffer)
                if (received < 0) break
                if (received > 0) checksum.update(buffer, 0, received)
            }
        }
        return String.format(Locale.US, "%08X", checksum.value)
    }

    private fun availableChecksumName(
        directory: DocumentFile,
        source: String,
        crc32: String,
    ): String {
        val dot = source.lastIndexOf('.').takeIf { it > 0 } ?: source.length
        val stem = source.substring(0, dot)
        val extension = source.substring(dot)
        val base = "$stem-$crc32$extension"
        if (directory.findFile(base) == null) return base
        var attempt = 2
        while (true) {
            val candidate = "$stem-$crc32-$attempt$extension"
            if (directory.findFile(candidate) == null) return candidate
            attempt++
        }
    }

    private fun listRemoteFiles(): List<SdRemoteFile> {
        val directories = ArrayDeque<String>().apply { add(deviceRoot) }
        val files = mutableListOf<SdRemoteFile>()
        var visited = 0
        while (directories.isNotEmpty()) {
            check(visited++ < MAX_DIRECTORIES) { "Device storage has too many directories" }
            val directory = directories.removeFirst()
            val response = transact("sd list ${SdStorageProtocol.quotedPath(directory)}")
            val listing = SdStorageProtocol.parseListing(response, directory)
            directories.addAll(listing.directories)
            files.addAll(listing.files)
            check(files.size <= MAX_FILES) { "Device storage has too many files for one sync" }
        }
        return files
    }

    private fun archiveFile(archiveRoot: DocumentFile, remote: SdRemoteFile) {
        val checksum = remoteChecksum(remote.path)
        check(checksum.size == remote.size) { "File changed before it could be archived" }
        val relative = remote.path.removePrefix(deviceRoot).trimStart('/')
        check(relative.isNotEmpty()) { "Refusing to archive the storage root" }
        val parts = relative.split('/').filter(String::isNotBlank)
        check(parts.isNotEmpty()) { "Invalid remote filename" }
        var directory = archiveRoot
        parts.dropLast(1).forEach { name ->
            directory = directory.findFile(name)
                ?: directory.createDirectory(name)
                ?: error("Could not create Android folder $name")
            check(directory.isDirectory) { "$name is not an Android folder" }
        }

        val sourceName = parts.last()
        val existing = directory.findFile(sourceName)
        val finalName = when {
            existing == null -> sourceName
            documentMatches(existing, remote.size, checksum.crc32) -> sourceName
            else -> availableChecksumName(directory, sourceName, checksum.crc32)
        }
        val finalizedAlready = directory.findFile(finalName)
        if (finalizedAlready != null && documentMatches(finalizedAlready, remote.size, checksum.crc32)) {
            acknowledgeRemote(remote.path, checksum)
            return
        }

        val partialName = ".$finalName.partial-${checksum.crc32}"
        var partial = directory.findFile(partialName)
            ?: directory.createFile("application/octet-stream", partialName)
            ?: error("Could not create a resumable Android archive document")
        if (!partial.isFile || partial.length() > remote.size) {
            runCatching { partial.delete() }
            partial = directory.createFile("application/octet-stream", partialName)
                ?: error("Could not replace an invalid Android archive partial")
        }
        appendRemote(remote, partial)
        if (!documentMatches(partial, remote.size, checksum.crc32)) {
            runCatching { partial.delete() }
            error("Android archive checksum failed; the device source was retained")
        }
        val current = remoteChecksum(remote.path)
        if (current != checksum) {
            runCatching { partial.delete() }
            error("File changed while it was being archived; the device source was retained")
        }
        check(partial.renameTo(finalName)) { "Android could not finalize the archive document" }
        val finalized = directory.findFile(finalName)
            ?: error("Android could not find the finalized archive document")
        check(documentMatches(finalized, remote.size, checksum.crc32)) {
            "Final Android archive checksum failed; the device source was retained"
        }
        acknowledgeRemote(remote.path, checksum)
    }

    private fun appendRemote(remote: SdRemoteFile, document: DocumentFile) {
        var offset = document.length()
        if (offset == remote.size) return
        val descriptor = context.contentResolver.openFileDescriptor(document.uri, "rw")
            ?: error("Android could not reopen the archive partial")
        descriptor.use {
            FileOutputStream(it.fileDescriptor).use { output ->
                output.channel.position(offset)
                while (offset < remote.size) {
                    val length = minOf(READ_CHUNK_BYTES, remote.size - offset)
                    val response = transact(
                        "sd read ${SdStorageProtocol.quotedPath(remote.path)} $offset $length --base64",
                    )
                    output.write(SdStorageProtocol.decodeRead(response, offset, length))
                    offset += length
                }
                output.flush()
                output.channel.force(true)
            }
        }
        check(document.length() == remote.size) { "Android reported an incomplete archive write" }
    }

    private fun remoteChecksum(path: String): SdRemoteChecksum =
        SdStorageProtocol.parseChecksum(
            transact("sd crc32 ${SdStorageProtocol.quotedPath(path)}"),
        )

    private fun acknowledgeRemote(path: String, checksum: SdRemoteChecksum) {
        transact(
            "sd ack ${SdStorageProtocol.quotedPath(path)} ${checksum.size} ${checksum.crc32}",
        )
    }

    private fun isArchiveCandidate(path: String): Boolean =
        SdStorageProtocol.isArchiveCandidate(session.kind, path)

    private fun documentMatches(document: DocumentFile, size: Long, crc32: String): Boolean {
        if (!document.isFile || document.length() != size) return false
        return runCatching { documentCrc32(document) }
            .getOrNull()
            ?.equals(crc32, ignoreCase = true) == true
    }

    private fun transact(command: String): List<String> =
        session.withExclusiveCommands { send ->
            collector.collect(command, send, COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }

}

private class HeltecBridgeResponseCollector {
    private data class ActiveRequest(
        val id: Long,
        val queue: LinkedBlockingQueue<String>,
    )

    private val lock = Any()
    private val lineBuffer = StringBuilder()
    private val sequence = AtomicLong(1_000_000_000L)
    private var active: ActiveRequest? = null

    fun accept(data: ByteArray) {
        synchronized(lock) {
            lineBuffer.append(data.toString(Charsets.UTF_8).replace("\u0000", ""))
            while (true) {
                val newline = lineBuffer.indexOf("\n")
                if (newline < 0) break
                val raw = lineBuffer.substring(0, newline).trimEnd('\r')
                lineBuffer.delete(0, newline + 1)
                val marker = raw.indexOf("@HELTEC-BRIDGE ")
                if (marker < 0) continue
                val line = raw.substring(marker)
                val request = active ?: continue
                val fields = line.split(' ', limit = 4)
                if (fields.size == 4 && fields[1].toLongOrNull() == request.id) {
                    request.queue.offer(line)
                }
            }
            if (lineBuffer.length > 32 * 1024) {
                lineBuffer.delete(0, lineBuffer.length - 8 * 1024)
            }
        }
    }

    fun collect(
        action: String,
        values: Map<String, String>,
        send: (String) -> Unit,
        timeout: Long,
        unit: TimeUnit,
    ): JSONObject {
        val id = sequence.incrementAndGet()
        val queue = LinkedBlockingQueue<String>()
        synchronized(lock) {
            check(active == null) { "Another Bruce archive request is active" }
            active = ActiveRequest(id, queue)
        }
        try {
            val form = values.entries.joinToString("&") { (key, value) ->
                "${encode(key)}=${encode(value)}"
            }
            send(buildString {
                append("@HELTEC-BRIDGE ").append(id).append(' ').append(action)
                if (form.isNotEmpty()) append(' ').append(form)
            })
            val line = queue.poll(timeout, unit)
                ?: error("Bruce did not answer '$action'")
            val fields = line.split(' ', limit = 4)
            check(fields.size == 4) { "Bruce returned a malformed archive response" }
            val payload = JSONObject(fields[3])
            if (fields[2] != "OK") {
                error(payload.optString("error", "Bruce archive request failed"))
            }
            return payload
        } finally {
            synchronized(lock) { active = null }
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}

private class SdResponseCollector {
    private val lock = Any()
    private val lineBuffer = StringBuilder()
    private var active: LinkedBlockingQueue<String>? = null

    fun accept(data: ByteArray) {
        synchronized(lock) {
            lineBuffer.append(data.toString(Charsets.UTF_8))
            while (true) {
                val newline = lineBuffer.indexOf("\n")
                if (newline < 0) break
                val raw = lineBuffer.substring(0, newline).trimEnd('\r')
                lineBuffer.delete(0, newline + 1)
                val marker = raw.indexOf("SD:")
                if (marker >= 0) active?.offer(raw.substring(marker))
            }
            if (lineBuffer.length > 8 * 1024) lineBuffer.delete(0, lineBuffer.length - 1024)
        }
    }

    fun collect(
        command: String,
        send: (String) -> Unit,
        timeout: Long,
        unit: TimeUnit,
    ): List<String> {
        val queue = LinkedBlockingQueue<String>()
        synchronized(lock) {
            check(active == null) { "Another storage response is active" }
            active = queue
        }
        try {
            send(command)
            val deadline = System.nanoTime() + unit.toNanos(timeout)
            val lines = mutableListOf<String>()
            while (true) {
                val remaining = deadline - System.nanoTime()
                check(remaining > 0) { "Device did not finish '$command'" }
                val line = queue.poll(remaining, TimeUnit.NANOSECONDS)
                    ?: error("Device did not answer '$command'")
                lines += line
                if (line.startsWith("SD:ERR:")) error(line.removePrefix("SD:ERR:"))
                if (line == "SD:OK" || line.startsWith("SD:OK:")) return lines
            }
        } finally {
            synchronized(lock) { active = null }
        }
    }
}
