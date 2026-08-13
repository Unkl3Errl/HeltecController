package com.unkl3errl.helteccontroller.connection

import org.json.JSONObject
import java.util.Base64
import java.util.Locale
import java.util.zip.CRC32

internal data class BruceArchiveSegment(
    val name: String,
    val size: Long,
    val crc32: String,
)

internal object BruceFieldLogProtocol {
    private val safeName = Regex("""^session-\d{6}-\d{3}\.ndjson$""")
    private val crc32 = Regex("""^[0-9A-Fa-f]{8}$""")

    fun parseArchiveSegments(payload: JSONObject): List<BruceArchiveSegment> {
        val files = payload.optJSONArray("files") ?: return emptyList()
        return buildList {
            for (index in 0 until files.length()) {
                val item = files.optJSONObject(index) ?: continue
                validatedSegment(
                    name = item.optString("name"),
                    size = item.optLong("sizeBytes", -1L),
                    checksum = item.optString("crc32"),
                    archiveReady = item.optBoolean("archiveReady", false),
                )?.let(::add)
            }
        }
    }

    fun decodeChunk(
        payload: JSONObject,
        expected: BruceArchiveSegment,
        expectedOffset: Long,
        expectedLength: Int,
    ): ByteArray {
        return decodeChunkFields(
            responseName = payload.optString("name"),
            responseSize = payload.optLong("sizeBytes", -1L),
            responseOffset = payload.optLong("offset", -1L),
            responseLength = payload.optInt("length", -1),
            encoding = payload.optString("encoding"),
            data = payload.optString("data"),
            expected = expected,
            expectedOffset = expectedOffset,
            expectedLength = expectedLength,
        )
    }

    fun validatedSegment(
        name: String,
        size: Long,
        checksum: String,
        archiveReady: Boolean,
    ): BruceArchiveSegment? {
        val normalizedChecksum = checksum.uppercase(Locale.US)
        if (!archiveReady || !safeName.matches(name) || size < 0 ||
            !crc32.matches(normalizedChecksum)
        ) {
            return null
        }
        return BruceArchiveSegment(name, size, normalizedChecksum)
    }

    fun decodeChunkFields(
        responseName: String,
        responseSize: Long,
        responseOffset: Long,
        responseLength: Int,
        encoding: String,
        data: String,
        expected: BruceArchiveSegment,
        expectedOffset: Long,
        expectedLength: Int,
    ): ByteArray {
        require(responseName == expected.name) {
            "Bruce returned the wrong field-log segment"
        }
        require(responseSize == expected.size) {
            "Bruce field-log size changed during transfer"
        }
        require(responseOffset == expectedOffset) {
            "Bruce returned the wrong field-log offset"
        }
        require(responseLength == expectedLength) {
            "Bruce returned a short field-log chunk"
        }
        require(encoding == "base64") {
            "Bruce returned an unsupported field-log encoding"
        }
        val decoded = Base64.getDecoder().decode(data)
        require(decoded.size == expectedLength) { "Bruce returned incomplete field-log data" }
        return decoded
    }

    fun crc32Hex(bytes: ByteArray): String = CRC32().run {
        update(bytes)
        String.format(Locale.US, "%08X", value)
    }
}
