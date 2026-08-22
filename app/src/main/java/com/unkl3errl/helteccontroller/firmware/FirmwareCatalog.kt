package com.unkl3errl.helteccontroller.firmware

import com.unkl3errl.helteccontroller.detection.FirmwareKind
import org.json.JSONObject

data class FirmwareRelease(
    val kind: FirmwareKind,
    val displayName: String,
    val version: String,
    val releasedAt: String,
    val summary: String,
    val sourceRepository: String,
    val sourceCommit: String,
    val upstream: FirmwareUpstream,
    val imageAsset: String?,
    val imageUrl: String?,
    val imageSha256: String,
    val imageSizeBytes: Long,
)

data class FirmwareUpstream(
    val repository: String,
    val latestReleaseApi: String,
    val baselineVersion: String,
    val baselineCommit: String,
)

data class UpstreamRelease(
    val kind: FirmwareKind,
    val version: String,
    val releasedAt: String,
    val releaseUrl: String,
)

data class FirmwareCatalog(
    val generatedAt: String,
    val releases: Map<FirmwareKind, FirmwareRelease>,
)

internal fun FirmwareCatalog.isAtLeastAsNewAs(baseline: FirmwareCatalog): Boolean =
    generatedAt >= baseline.generatedAt && baseline.releases.all { (kind, baselineRelease) ->
        releases[kind]?.let { candidate ->
            FirmwareVersion.isOlder(candidate.version, baselineRelease.version) != true
        } == true
    }

object FirmwareCatalogParser {
    fun parse(text: String): FirmwareCatalog {
        val root = JSONObject(text)
        require(root.getInt("schemaVersion") == 1) { "Unsupported firmware catalog" }
        val board = root.getJSONObject("board")
        require(board.getString("chip") == "ESP32-S3") { "Catalog is for the wrong chip" }
        require(board.getLong("flashBytes") == 16L * 1024L * 1024L) {
            "Catalog is for the wrong flash size"
        }
        val items = root.getJSONArray("firmwares")
        val releases = buildMap {
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                val kind = when (item.getString("id")) {
                    "bruce" -> FirmwareKind.BRUCE
                    "ghostesp" -> FirmwareKind.GHOSTESP
                    "marauder" -> FirmwareKind.MARAUDER
                    else -> error("Unknown firmware id")
                }
                require(!containsKey(kind)) { "Duplicate firmware id" }
                val source = item.getJSONObject("source")
                val upstream = item.getJSONObject("upstream")
                val image = item.getJSONObject("image")
                val sha256 = image.getString("sha256").lowercase()
                require(sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid image digest" }
                val size = image.getLong("sizeBytes")
                require(size in 64 * 1024..4L * 1024L * 1024L) { "Invalid image size" }
                put(
                    kind,
                    FirmwareRelease(
                        kind = kind,
                        displayName = item.getString("displayName"),
                        version = item.getString("version"),
                        releasedAt = item.getString("releasedAt"),
                        summary = item.getString("summary"),
                        sourceRepository = source.getString("repository"),
                        sourceCommit = source.getString("commit"),
                        upstream = FirmwareUpstream(
                            repository = upstream.getString("repository"),
                            latestReleaseApi = upstream.getString("latestReleaseApi"),
                            baselineVersion = upstream.getString("baselineVersion"),
                            baselineCommit = upstream.getString("baselineCommit"),
                        ),
                        imageAsset = image.optionalString("asset"),
                        imageUrl = image.optionalString("url"),
                        imageSha256 = sha256,
                        imageSizeBytes = size,
                    ),
                )
            }
        }
        require(releases.keys == setOf(
            FirmwareKind.BRUCE,
            FirmwareKind.GHOSTESP,
            FirmwareKind.MARAUDER,
        )) { "Catalog must contain all three firmware images" }
        return FirmwareCatalog(root.getString("generatedAt"), releases)
    }

    private fun JSONObject.optionalString(name: String): String? =
        if (!has(name) || isNull(name)) null else getString(name).takeIf(String::isNotBlank)
}

object FirmwareVersion {
    fun isOlder(installed: String?, latest: String): Boolean? {
        if (installed.isNullOrBlank()) return null
        val current = components(installed)
        val target = components(latest)
        if (current == null || target == null) return installed != latest
        val count = maxOf(current.size, target.size)
        for (index in 0 until count) {
            val left = current.getOrElse(index) { 0 }
            val right = target.getOrElse(index) { 0 }
            if (left != right) return left < right
        }
        val currentMobile = mobileRevision(installed)
        val targetMobile = mobileRevision(latest)
        if (currentMobile != null && targetMobile != null && currentMobile != targetMobile) {
            return currentMobile < targetMobile
        }
        return normalize(installed) != normalize(latest)
    }

    fun matches(installed: String?, latest: String): Boolean =
        installed != null && normalize(installed) == normalize(latest)

    fun stableVersion(value: String): String? =
        Regex("^v?(\\d+\\.\\d+(?:\\.\\d+)?)$")
            .matchEntire(value.trim())
            ?.groupValues
            ?.get(1)

    fun isUpstreamBaselineOlder(baseline: String, latestStable: String): Boolean? {
        val latest = stableVersion(latestStable)?.let(::numericComponents) ?: return null
        val match = Regex("^v?(\\d+\\.\\d+(?:\\.\\d+)?)(?:-pre\\d+)?$")
            .matchEntire(baseline.trim()) ?: return null
        val current = numericComponents(match.groupValues[1])
        val count = maxOf(current.size, latest.size)
        for (index in 0 until count) {
            val left = current.getOrElse(index) { 0 }
            val right = latest.getOrElse(index) { 0 }
            if (left != right) return left < right
        }
        return "-pre" in baseline.lowercase()
    }

    private fun components(value: String): List<Int>? {
        val match = Regex("^v?(\\d+(?:\\.\\d+){1,3})").find(value.trim()) ?: return null
        return numericComponents(match.groupValues[1])
    }

    private fun numericComponents(value: String): List<Int> =
        value.split('.').map(String::toInt)

    private fun normalize(value: String): String = value.trim().removePrefix("v").lowercase()

    private fun mobileRevision(value: String): Int? =
        Regex("-mobile\\.(\\d+)", RegexOption.IGNORE_CASE)
            .find(value)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
}
