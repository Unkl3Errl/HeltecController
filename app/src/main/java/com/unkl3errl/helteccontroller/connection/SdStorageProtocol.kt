package com.unkl3errl.helteccontroller.connection

import java.util.Base64

internal data class SdRemoteFile(
    val path: String,
    val size: Long,
    val modifiedSeconds: Long,
)

internal data class SdDirectoryListing(
    val directories: List<String>,
    val files: List<SdRemoteFile>,
)

internal data class SdRemoteChecksum(
    val size: Long,
    val crc32: String,
)

internal object SdStorageProtocol {
    private val bruceOutputRules = listOf(
        "/brucepcap/" to setOf("pcap"),
        "/brucewardriving/" to setOf("csv"),
        "/brucegps/" to setOf("gpx"),
        "/bruce/terminal/" to setOf("log"),
        "/bruceevilcreds/" to setOf("csv"),
        "/ntlm/" to setOf("txt"),
        "/portalcreds/" to setOf("txt"),
        "/probedata/" to setOf("bin", "csv", "pcap", "txt"),
    )
    private val ghostArchiveRoots = listOf(
        "/mnt/ghostesp/pcaps/",
        "/mnt/ghostesp/gps/",
        "/mnt/ghostesp/scans/",
        "/mnt/ghostesp/captures/",
        "/mnt/ghostesp/sweeps/",
        "/mnt/ghostesp/logs/",
        "/mnt/ghostesp/debug/",
        "/mnt/ghostesp/ghostchi/pcaps/",
        "/mnt/ghostesp/ghostchi/sessions/",
    )
    private val directoryLine = Regex("""^SD:DIR:\[\d+] (.+)$""")
    private val timestampedFileLine = Regex("""^SD:FILE:\[\d+] (.+?) (\d+) (\d+)$""")
    private val legacyFileLine = Regex("""^SD:FILE:\[\d+] (.+?) (\d+)$""")

    fun quotedPath(path: String): String {
        require(path.startsWith('/')) { "Storage path must be absolute" }
        require(!path.contains("..")) { "Parent traversal is not allowed" }
        require(path.none { it == '"' || it == '\r' || it == '\n' || it.code < 0x20 }) {
            "Storage path contains an unsupported character"
        }
        return "\"$path\""
    }

    fun parseListing(lines: List<String>, parent: String): SdDirectoryListing {
        val directories = mutableListOf<String>()
        val files = mutableListOf<SdRemoteFile>()
        lines.forEach { line ->
            directoryLine.matchEntire(line)?.let { match ->
                safeChild(parent, match.groupValues[1])?.let(directories::add)
                return@forEach
            }
            (timestampedFileLine.matchEntire(line) ?: legacyFileLine.matchEntire(line))?.let { match ->
                val child = safeChild(parent, match.groupValues[1]) ?: return@forEach
                files += SdRemoteFile(
                    path = child,
                    size = match.groupValues[2].toLong(),
                    modifiedSeconds = match.groupValues.getOrNull(3)?.toLongOrNull() ?: 0L,
                )
            }
        }
        return SdDirectoryListing(directories, files)
    }

    fun decodeRead(lines: List<String>, expectedOffset: Long, expectedLength: Long): ByteArray {
        val offset = valueAfter(lines, "SD:READ:OFFSET:")?.toLongOrNull()
        val length = valueAfter(lines, "SD:READ:LENGTH:")?.toLongOrNull()
        require(offset == expectedOffset) { "Device returned the wrong file offset" }
        require(length == expectedLength) { "Device returned the wrong chunk length" }
        val encoded = lines.asSequence()
            .filter { it.startsWith("SD:READ:DATA:") }
            .joinToString("") { it.removePrefix("SD:READ:DATA:") }
        val decoded = if (encoded.isEmpty()) ByteArray(0) else Base64.getDecoder().decode(encoded)
        require(decoded.size.toLong() == expectedLength) { "Device returned a short file chunk" }
        return decoded
    }

    fun parseSize(lines: List<String>): Long =
        valueAfter(lines, "SD:SIZE:")?.toLongOrNull()
            ?: error("Device did not return a file size")

    fun parseChecksum(lines: List<String>): SdRemoteChecksum {
        val value = valueAfter(lines, "SD:CRC32:")
            ?.takeIf { !it.startsWith("SIZE:") }
            ?.uppercase()
            ?: error("Device did not return a CRC-32 checksum")
        require(value.matches(Regex("""^[0-9A-F]{8}$"""))) {
            "Device returned an invalid CRC-32 checksum"
        }
        val size = valueAfter(lines, "SD:CRC32:SIZE:")?.toLongOrNull()
            ?: error("Device did not return the checksummed file size")
        return SdRemoteChecksum(size, value)
    }

    fun isArchiveCandidate(kind: PersistentUsbKind, path: String): Boolean = when (kind) {
        PersistentUsbKind.BRUCE -> {
            val normalized = path.lowercase()
            val extension = normalized.substringAfterLast('.', "")
            bruceOutputRules.any { (root, extensions) ->
                normalized.startsWith(root) && extension in extensions
            }
        }

        PersistentUsbKind.GHOSTESP -> ghostArchiveRoots.any(path::startsWith)

        PersistentUsbKind.MARAUDER -> {
            val name = path.substringAfterLast('/')
            path.count { it == '/' } == 1 && (
                name.endsWith(".pcap", ignoreCase = true) ||
                    name.endsWith(".log", ignoreCase = true) ||
                    ((name.startsWith("tracker_") ||
                        name.startsWith("poi_") ||
                        name.startsWith("wardrive_poi_")) &&
                        name.endsWith(".gpx", ignoreCase = true))
                )
        }
    }

    private fun valueAfter(lines: List<String>, prefix: String): String? =
        lines.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)

    private fun safeChild(parent: String, name: String): String? {
        if (name.isBlank() || name == "." || name == ".." || name.contains('/')) return null
        if (name.any { it == '"' || it == '\r' || it == '\n' || it.code < 0x20 }) return null
        return if (parent == "/") "/$name" else "$parent/$name"
    }
}
