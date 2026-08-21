package com.unkl3errl.helteccontroller.firmware

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import com.unkl3errl.helteccontroller.detection.FirmwareKind
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import org.json.JSONArray
import org.json.JSONObject

class FirmwareImageRepository(context: Context) {
    interface Listener {
        fun onCatalogChanged(catalog: FirmwareCatalog)
        fun onUpstreamReleasesChanged(releases: Map<FirmwareKind, UpstreamRelease>)
        fun onCatalogStatus(message: String)
    }

    companion object {
        private const val BUNDLED_CATALOG = "firmware/catalog.json"
        private const val BUNDLED_CATALOG_SIGNATURE = "firmware/catalog.sig"
        private const val REMOTE_CATALOG =
            "https://raw.githubusercontent.com/Unkl3Errl/HeltecController/main/firmware-catalog.json"
        private const val REMOTE_CATALOG_SIGNATURE =
            "https://raw.githubusercontent.com/Unkl3Errl/HeltecController/main/firmware-catalog.sig"
        private const val CATALOG_PUBLIC_KEY =
            "MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAiiysT/An3yszy9nj/4J4VRKHzE4Z3ejbMb6GBf1lavu6b7ep8yOz+xDBhygNEayP3JRpyBx+3nTMmJfpjY0p2AOHMoqFDUIvOJZZsRsApEO9RRyEwIb9pPR4ZfxlLJUfC+eJ9zqEbqHSMevbWqHysfwNrnvVn7ElywKxVSJSNZ+6OHiUS4uMBZF8bsXa02HUiNQ5PAyoalfGoVEOoXqsUu8nVcZwcKocY8faI21BWQWfneQXnEYqN7Qx/aiFIchhzRFFG1GDnXwdlVJjomANc1G0AqmS6tYceZxTNJnrJA7LX23YGCHqNYlJC/rdW/+HO0RVFDz6V2ZhANbKxrswl9T/v2dbY5J9NzEYeBugczKqnFT/NRNpwn+RhZJovx45LYzISimTYKT930f8j7GtKOPIOZUWsRo3qVqCj7hlrYuoUB4oJ1fCxh/iu66UCqmGY0Hc8hYRllCKfpnZCNjmbYz4UMIdnsIvDauBRurlgGVnofnkypqw86FEKAX6oJGOBSY39iYhuiZRNET+HXHvJRawwXI0hkY0RpDPLdiEreQKgiHPX6txrfHlVpcZPoyLtwjop2+qQAe/DkzEqFHlyFo5Sj3q8rrdTBiAJaT4HsSrJOvwN5gMN/XTNzmj6TET9MUFy3aPa6AhEOv1lciPnTRwR+l5JqwOi4ECJFaQCuECAwEAAQ=="
        private const val MAX_CATALOG_BYTES = 256 * 1024
        private const val MAX_UPSTREAM_RELEASE_BYTES = 256 * 1024
        private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
        private const val CACHED_CATALOG = "catalog.json"
        private const val CACHED_CATALOG_SIGNATURE = "catalog.sig"
        private const val CACHED_UPSTREAM_RELEASES = "upstream-releases.json"
        private val ALLOWED_HOSTS = setOf(
            "github.com",
            "raw.githubusercontent.com",
            "api.github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
        )
    }

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val executor = Executors.newSingleThreadExecutor()
    private val directory = File(appContext.filesDir, "firmware-images")

    @Volatile
    private var listener: Listener? = null

    @Volatile
    private var initialized = false

    @Volatile
    private var lastRefreshRequestedAt = 0L

    @Volatile
    var catalog: FirmwareCatalog? = null
        private set

    @Volatile
    var upstreamReleases: Map<FirmwareKind, UpstreamRelease> = emptyMap()
        private set

    @Volatile
    var upstreamRefreshComplete: Boolean = false
        private set

    fun initialize(listener: Listener) {
        this.listener = listener
        executor.execute {
            runCatching {
                directory.mkdirs()
                val bundledBytes = appContext.assets.open(BUNDLED_CATALOG).use { it.readBytes() }
                val bundledSignature = appContext.assets.open(BUNDLED_CATALOG_SIGNATURE)
                    .use { it.readBytes() }
                require(verifyCatalogSignature(bundledBytes, bundledSignature)) {
                    "Bundled firmware catalog signature mismatch"
                }
                val bundled = FirmwareCatalogParser.parse(bundledBytes.toString(Charsets.UTF_8))
                materializeBundled(bundled)
                val active = loadCachedCatalog()?.takeIf { it.isAtLeastAsNewAs(bundled) }
                    ?: bundled
                catalog = active
                upstreamReleases = loadCachedUpstreamReleases(active)
                initialized = true
                listener.onCatalogChanged(active)
                if (upstreamReleases.isNotEmpty()) {
                    listener.onUpstreamReleasesChanged(upstreamReleases)
                }
                listener.onCatalogStatus("Offline firmware images are ready")
            }.onFailure { error ->
                listener.onCatalogStatus(
                    "Bundled firmware verification failed: ${error.message ?: error.javaClass.simpleName}",
                )
                return@execute
            }
            lastRefreshRequestedAt = System.currentTimeMillis()
            refreshRemote(listener)
            refreshUpstreamReleases(listener)
        }
    }

    /** Refreshes both signed images and official source releases without restarting the app. */
    fun refreshIfStale(maxAgeMillis: Long = 0L) {
        val activeListener = listener ?: return
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (!initialized || now - lastRefreshRequestedAt < maxAgeMillis) return
            lastRefreshRequestedAt = now
        }
        try {
            executor.execute {
                refreshRemote(activeListener)
                refreshUpstreamReleases(activeListener)
            }
        } catch (_: RejectedExecutionException) {
            // The Activity is already being destroyed.
        }
    }

    fun close() = executor.shutdownNow()

    fun imageFile(kind: FirmwareKind): File? {
        val release = catalog?.releases?.get(kind) ?: return null
        return storedFile(release).takeIf { verifyFile(it, release) }
    }

    private fun materializeBundled(catalog: FirmwareCatalog) {
        catalog.releases.values.forEach { release ->
            val asset = requireNotNull(release.imageAsset) { "Bundled image asset is missing" }
            val destination = storedFile(release)
            if (verifyFile(destination, release)) return@forEach
            val temporary = File(directory, destination.name + ".partial")
            appContext.assets.open("firmware/$asset").use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            require(verifyFile(temporary, release)) { "${release.displayName} image checksum mismatch" }
            require(temporary.renameTo(destination)) { "Could not retain ${release.displayName} image" }
        }
    }

    private fun loadCachedCatalog(): FirmwareCatalog? = runCatching {
        val bytes = File(directory, CACHED_CATALOG).readBytes()
        val signature = File(directory, CACHED_CATALOG_SIGNATURE).readBytes()
        require(verifyCatalogSignature(bytes, signature)) { "Cached catalog signature mismatch" }
        FirmwareCatalogParser.parse(bytes.toString(Charsets.UTF_8)).also { cached ->
            cached.releases.values.forEach {
                validateSource(it)
                validateUpstream(it)
            }
        }
    }.getOrNull()

    private fun refreshRemote(listener: Listener) {
        runCatching {
            val bytes = downloadBytes(REMOTE_CATALOG, MAX_CATALOG_BYTES)
            val signature = downloadBytes(REMOTE_CATALOG_SIGNATURE, 1024)
            require(verifyCatalogSignature(bytes, signature)) { "Update catalog signature mismatch" }
            val text = bytes.toString(Charsets.UTF_8)
            val remote = FirmwareCatalogParser.parse(text)
            val bundled = requireNotNull(catalog) { "Bundled firmware catalog is unavailable" }
            require(remote.generatedAt >= bundled.generatedAt) { "Update catalog is older than bundled data" }
            remote.releases.values.forEach { release ->
                val bundledRelease = requireNotNull(bundled.releases[release.kind])
                require(FirmwareVersion.isOlder(release.version, bundledRelease.version) != true) {
                    "Update catalog would downgrade ${release.displayName}"
                }
                validateSource(release)
                val file = storedFile(release)
                if (!verifyFile(file, release)) downloadImage(release, file)
            }
            replaceAtomically(File(directory, CACHED_CATALOG), bytes)
            replaceAtomically(File(directory, CACHED_CATALOG_SIGNATURE), signature)
            catalog = remote
            listener.onCatalogChanged(remote)
            listener.onCatalogStatus("Firmware catalog is current · ${remote.generatedAt}")
        }.onFailure {
            // Offline use is expected; a verified bundled catalog remains fully flashable.
            listener.onCatalogStatus("Using verified offline firmware images")
        }
    }

    private fun refreshUpstreamReleases(listener: Listener) {
        val activeCatalog = catalog ?: return
        val refreshed = buildMap {
            putAll(upstreamReleases)
            activeCatalog.releases.values.forEach { release ->
                runCatching { downloadUpstreamRelease(release) }
                    .getOrNull()
                    ?.let { put(release.kind, it) }
            }
        }
        upstreamReleases = refreshed
        upstreamRefreshComplete = true
        if (refreshed.isNotEmpty()) persistUpstreamReleases(refreshed)
        listener.onUpstreamReleasesChanged(refreshed)
    }

    private fun downloadUpstreamRelease(release: FirmwareRelease): UpstreamRelease {
        validateUpstream(release)
        val bytes = downloadBytes(release.upstream.latestReleaseApi, MAX_UPSTREAM_RELEASE_BYTES)
        val response = JSONObject(bytes.toString(Charsets.UTF_8))
        require(!response.getBoolean("draft") && !response.getBoolean("prerelease")) {
            "Upstream response is not a stable release"
        }
        val version = requireNotNull(FirmwareVersion.stableVersion(response.getString("tag_name"))) {
            "Upstream release tag is not a stable version"
        }
        val publishedAt = response.getString("published_at")
        require(publishedAt.matches(Regex("\\d{4}-\\d{2}-\\d{2}T.*"))) {
            "Invalid upstream release date"
        }
        val releaseUrl = response.getString("html_url")
        validateUpstreamReleaseUrl(release, releaseUrl)
        return UpstreamRelease(
            kind = release.kind,
            version = version,
            releasedAt = publishedAt.substringBefore('T'),
            releaseUrl = releaseUrl,
        )
    }

    private fun loadCachedUpstreamReleases(
        activeCatalog: FirmwareCatalog,
    ): Map<FirmwareKind, UpstreamRelease> = runCatching {
        val root = JSONObject(File(directory, CACHED_UPSTREAM_RELEASES).readText())
        require(root.getInt("schemaVersion") == 1) { "Unsupported upstream cache" }
        val items = root.getJSONArray("releases")
        buildMap {
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                val kind = FirmwareKind.valueOf(item.getString("kind"))
                val source = requireNotNull(activeCatalog.releases[kind])
                val version = requireNotNull(FirmwareVersion.stableVersion(item.getString("version")))
                val releasedAt = item.getString("releasedAt")
                require(releasedAt.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
                    "Invalid cached upstream date"
                }
                val releaseUrl = item.getString("releaseUrl")
                validateUpstreamReleaseUrl(source, releaseUrl)
                put(kind, UpstreamRelease(kind, version, releasedAt, releaseUrl))
            }
        }
    }.getOrDefault(emptyMap())

    private fun persistUpstreamReleases(releases: Map<FirmwareKind, UpstreamRelease>) {
        runCatching {
            val items = JSONArray()
            releases.values.sortedBy { it.kind.name }.forEach { release ->
                items.put(
                    JSONObject()
                        .put("kind", release.kind.name)
                        .put("version", release.version)
                        .put("releasedAt", release.releasedAt)
                        .put("releaseUrl", release.releaseUrl),
                )
            }
            val bytes = JSONObject()
                .put("schemaVersion", 1)
                .put("releases", items)
                .toString()
                .toByteArray(Charsets.UTF_8)
            replaceAtomically(File(directory, CACHED_UPSTREAM_RELEASES), bytes)
        }
    }

    private fun validateUpstreamReleaseUrl(release: FirmwareRelease, releaseUrl: String) {
        val releasePage = URL(releaseUrl)
        val expectedReleasePrefix = "${URL(release.upstream.repository).path}/releases/tag/"
        require(releasePage.protocol == "https" &&
            releasePage.host == "github.com" &&
            releasePage.path.startsWith(expectedReleasePrefix)) {
            "Untrusted upstream release page"
        }
    }

    private fun replaceAtomically(destination: File, bytes: ByteArray) {
        val temporary = File(destination.parentFile, destination.name + ".partial")
        temporary.writeBytes(bytes)
        Files.move(
            temporary.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private fun downloadImage(release: FirmwareRelease, destination: File) {
        val url = requireNotNull(release.imageUrl) { "No update image URL" }
        val temporary = File(directory, destination.name + ".partial")
        val bytes = downloadBytes(url, MAX_IMAGE_BYTES)
        temporary.outputStream().use { it.write(bytes) }
        require(verifyFile(temporary, release)) { "Downloaded image checksum mismatch" }
        require(temporary.renameTo(destination)) { "Could not retain downloaded image" }
    }

    private fun downloadBytes(initialUrl: String, maximum: Int): ByteArray {
        var next = initialUrl
        repeat(6) {
            val url = URL(next)
            require(url.protocol == "https" && url.host in ALLOWED_HOSTS) {
                "Untrusted firmware URL"
            }
            val connection = openInternetConnection(url)
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/octet-stream, application/json")
            connection.setRequestProperty("User-Agent", "FirmwareController/${appVersionName()}")
            try {
                if (connection.responseCode in 300..399) {
                    next = requireNotNull(connection.getHeaderField("Location")) {
                        "Update redirect has no destination"
                    }
                    return@repeat
                }
                require(connection.responseCode == 200) { "Update server returned ${connection.responseCode}" }
                connection.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= maximum) { "Update file is too large" }
                        output.write(buffer, 0, count)
                    }
                    return output.toByteArray()
                }
            } finally {
                connection.disconnect()
            }
        }
        error("Too many update redirects")
    }

    private fun validateSource(release: FirmwareRelease) {
        val source = URL(release.sourceRepository)
        require(source.protocol == "https" && source.host == "github.com") {
            "Untrusted source repository"
        }
        require(release.sourceCommit.matches(Regex("[0-9a-f]{40}"))) {
            "Invalid source commit"
        }
    }

    private fun validateUpstream(release: FirmwareRelease) {
        val repository = URL(release.upstream.repository)
        require(repository.protocol == "https" && repository.host == "github.com") {
            "Untrusted upstream repository"
        }
        val api = URL(release.upstream.latestReleaseApi)
        val expectedApiPath = "/repos${repository.path}/releases/latest"
        require(api.protocol == "https" && api.host == "api.github.com" && api.path == expectedApiPath) {
            "Untrusted upstream release API"
        }
        require(FirmwareVersion.stableVersion(release.upstream.baselineVersion) != null ||
            release.upstream.baselineVersion.matches(Regex("\\d+\\.\\d+(?:\\.\\d+)?-pre\\d+"))) {
            "Invalid upstream baseline version"
        }
        require(release.upstream.baselineCommit.matches(Regex("[0-9a-f]{40}"))) {
            "Invalid upstream baseline commit"
        }
    }

    private fun openInternetConnection(url: URL): HttpURLConnection {
        val validatedInternet = connectivityManager.allNetworks.firstOrNull { network ->
            connectivityManager.getNetworkCapabilities(network)?.let { capabilities ->
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } == true
        }
        return ((validatedInternet?.openConnection(url) ?: url.openConnection()) as HttpURLConnection)
    }

    @Suppress("DEPRECATION")
    private fun appVersionName(): String =
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "unknown"

    private fun verifyCatalogSignature(catalogBytes: ByteArray, signatureBytes: ByteArray): Boolean {
        val encoded = Base64.decode(CATALOG_PUBLIC_KEY, Base64.DEFAULT)
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(encoded))
        return Signature.getInstance("SHA256withRSA").run {
            initVerify(publicKey)
            update(catalogBytes)
            verify(signatureBytes)
        }
    }

    private fun storedFile(release: FirmwareRelease): File {
        val safeVersion = release.version.replace(Regex("[^0-9A-Za-z._-]"), "_")
        return File(directory, "${release.kind.name.lowercase()}-$safeVersion.bin")
    }

    private fun verifyFile(file: File, release: FirmwareRelease): Boolean {
        if (!file.isFile || file.length() != release.imageSizeBytes) return false
        if (!isEsp32S3BootImage(file)) return false
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == release.imageSha256
    }

    private fun isEsp32S3BootImage(file: File): Boolean {
        val header = ByteArray(14)
        FileInputStream(file).use { if (it.read(header) != header.size) return false }
        val chipId = (header[12].toInt() and 0xff) or ((header[13].toInt() and 0xff) shl 8)
        return (header[0].toInt() and 0xff) == 0xe9 && chipId == 9
    }
}
