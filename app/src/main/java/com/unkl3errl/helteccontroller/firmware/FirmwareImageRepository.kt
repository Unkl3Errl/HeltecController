package com.unkl3errl.helteccontroller.firmware

import android.content.Context
import android.util.Base64
import com.unkl3errl.helteccontroller.detection.FirmwareKind
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.Executors

class FirmwareImageRepository(context: Context) {
    interface Listener {
        fun onCatalogChanged(catalog: FirmwareCatalog)
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
        private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
        private val ALLOWED_HOSTS = setOf(
            "github.com",
            "raw.githubusercontent.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
        )
    }

    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val directory = File(appContext.filesDir, "firmware-images")

    @Volatile
    var catalog: FirmwareCatalog? = null
        private set

    fun initialize(listener: Listener) {
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
                catalog = bundled
                listener.onCatalogChanged(bundled)
                listener.onCatalogStatus("Offline firmware images are ready")
            }.onFailure { error ->
                listener.onCatalogStatus(
                    "Bundled firmware verification failed: ${error.message ?: error.javaClass.simpleName}",
                )
                return@execute
            }
            refreshRemote(listener)
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
            File(directory, "catalog.json").writeText(text)
            catalog = remote
            listener.onCatalogChanged(remote)
            listener.onCatalogStatus("Firmware catalog is current · ${remote.generatedAt}")
        }.onFailure {
            // Offline use is expected; a verified bundled catalog remains fully flashable.
            listener.onCatalogStatus("Using verified offline firmware images")
        }
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
            val connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/octet-stream, application/json")
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
