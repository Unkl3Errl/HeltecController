package com.unkl3errl.helteccontroller.bruce

import android.net.Network
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class HttpResult(val status: Int, val body: String, val location: String? = null)

class BruceApiException(
    val status: Int,
    message: String,
) : Exception(message)

class BruceApiClient {
    @Volatile
    var network: Network? = null

    @Volatile
    private var baseUrl: String = "http://172.0.0.1"

    @Volatile
    private var sessionCookie: String? = null

    val isAuthenticated: Boolean
        get() = sessionCookie != null

    fun configure(url: String) {
        baseUrl = url.trim().trimEnd('/').ifBlank { "http://172.0.0.1" }
    }

    fun displayUrl(): String = baseUrl

    fun login(username: String, password: String): Boolean {
        sessionCookie = null
        val result = request(
            method = "POST",
            path = "/login",
            form = mapOf("username" to username, "password" to password),
            authenticated = false,
        )
        return result.status == 302 && result.location == "/" && sessionCookie != null
    }

    fun logout() {
        if (sessionCookie != null) {
            runCatching { request("GET", "/logout") }
        }
        sessionCookie = null
    }

    fun probeWebUi(): HttpResult = request(
        method = "GET",
        path = "/",
        authenticated = false,
    )

    fun getJson(path: String): JSONObject = JSONObject(requireSuccess(request("GET", path)).body)

    fun getText(path: String): String = requireSuccess(request("GET", path)).body

    fun getBytes(path: String): ByteArray {
        val connection = open(path, "GET", "application/octet-stream")
        return try {
            val status = connection.responseCode
            if (status !in 200..299) throw responseException(connection, status)
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    fun postForm(path: String, values: Map<String, String>): JSONObject =
        JSONObject(requireSuccess(request("POST", path, values)).body)

    fun post(path: String, values: Map<String, String>): HttpResult =
        requireSuccess(request("POST", path, values))

    fun postText(path: String, values: Map<String, String>): String =
        requireSuccess(request("POST", path, values)).body

    fun downloadFieldLog(fileName: String, destination: OutputStream): Long =
        download(fieldLogDownloadPath(fileName), "application/x-ndjson", destination)

    fun downloadDeviceFile(path: String, destination: OutputStream): Long =
        download(deviceFileDownloadPath(path), "application/octet-stream", destination)

    private fun download(path: String, accept: String, destination: OutputStream): Long {
        val connection = open(path, "GET", accept, 30_000)

        return try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw responseException(connection, status)
            }

            var copied = 0L
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            connection.inputStream.use { input ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    destination.write(buffer, 0, count)
                    copied += count
                }
            }
            destination.flush()
            copied
        } finally {
            connection.disconnect()
        }
    }

    private fun requireSuccess(result: HttpResult): HttpResult {
        if (result.status in 200..299) return result
        if (result.status == 401) sessionCookie = null
        throw BruceApiException(result.status, errorDetail(result.status, result.body))
    }

    private fun request(
        method: String,
        path: String,
        form: Map<String, String>? = null,
        authenticated: Boolean = true,
    ): HttpResult {
        val connection = open(path, method, "application/json")
        if (!authenticated) connection.setRequestProperty("Cookie", "")

        if (form != null) {
            val encoded = form.entries.joinToString("&") { (key, value) ->
                "${encode(key)}=${encode(value)}"
            }.toByteArray(StandardCharsets.UTF_8)
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setFixedLengthStreamingMode(encoded.size)
            connection.outputStream.use { it.write(encoded) }
        }

        return try {
            val status = connection.responseCode
            val setCookie = connection.getHeaderField("Set-Cookie")
            if (!setCookie.isNullOrBlank()) {
                val cookie = setCookie.substringBefore(';')
                if (cookie.startsWith("BRUCESESSION=") && cookie != "BRUCESESSION=0") {
                    sessionCookie = cookie
                }
            }
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val body = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            HttpResult(status, body, connection.getHeaderField("Location"))
        } finally {
            connection.disconnect()
        }
    }

    private fun open(
        path: String,
        method: String,
        accept: String,
        readTimeout: Int = 7_000,
    ): HttpURLConnection {
        val url = URL(baseUrl + path)
        return ((network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 5_000
            this.readTimeout = readTimeout
            requestMethod = method
            setRequestProperty("Accept", accept)
            sessionCookie?.let { setRequestProperty("Cookie", it) }
        }
    }

    private fun responseException(connection: HttpURLConnection, status: Int): BruceApiException {
        val body = connection.errorStream
            ?.bufferedReader()
            ?.use(BufferedReader::readText)
            .orEmpty()
        if (status == 401) sessionCookie = null
        return BruceApiException(status, errorDetail(status, body))
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}

internal fun fieldLogDownloadPath(fileName: String): String =
    "/api/heltec/fieldlog/download?name=" +
        URLEncoder.encode(fileName, StandardCharsets.UTF_8.name())

internal fun deviceFileDownloadPath(path: String): String =
    "/file?fs=LittleFS&action=download&name=" +
        URLEncoder.encode(path, StandardCharsets.UTF_8.name())

internal fun encodeQuery(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name())

private fun errorDetail(status: Int, body: String): String =
    runCatching { JSONObject(body).optString("error") }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: body.take(180).ifBlank { "HTTP $status" }
