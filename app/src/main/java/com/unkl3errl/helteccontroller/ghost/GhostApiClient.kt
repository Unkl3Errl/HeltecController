package com.unkl3errl.helteccontroller.ghost

import android.net.Network
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

data class GhostHttpResult(val status: Int, val body: String)

class GhostApiException(
    val status: Int,
    message: String,
) : Exception(message)

class GhostApiClient(
    network: Network? = null,
    baseUrl: String = DEFAULT_BASE_URL,
) {
    companion object {
        const val DEFAULT_BASE_URL = "http://192.168.4.1"
    }

    @Volatile
    var network: Network? = network

    @Volatile
    private var baseUrl: String = normalizeBaseUrl(baseUrl)

    fun configure(url: String, network: Network? = this.network) {
        baseUrl = normalizeBaseUrl(url)
        this.network = network
    }

    fun displayUrl(): String = baseUrl

    fun probeWebUi(): GhostHttpResult = requireSuccess(request("GET", "/", "text/html"))

    fun getRoot(): String = probeWebUi().body

    fun getSettings(): JSONObject = getJson("/api/settings")

    fun getLogs(): String = getText("/api/logs", "text/plain")

    fun sendCommand(command: String): GhostHttpResult = postJson(
        path = "/api/command",
        body = JSONObject().put("command", command),
    )

    fun clearLogs(): JSONObject = JSONObject(
        postJson(path = "/api/clear_logs", body = null).body,
    )

    fun getJson(path: String): JSONObject = JSONObject(getText(path, "application/json"))

    fun getText(path: String, accept: String = "text/plain"): String =
        requireSuccess(request("GET", path, accept)).body

    fun postJson(path: String, body: JSONObject?): GhostHttpResult =
        requireSuccess(request("POST", path, "application/json", body?.toString()))

    private fun request(
        method: String,
        path: String,
        accept: String,
        jsonBody: String? = null,
    ): GhostHttpResult {
        val connection = open(path, method, accept)
        if (jsonBody != null) {
            val encoded = jsonBody.toByteArray(StandardCharsets.UTF_8)
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setFixedLengthStreamingMode(encoded.size)
            connection.outputStream.use { it.write(encoded) }
        }

        return try {
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val responseBody = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            GhostHttpResult(status, responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(path: String, method: String, accept: String): HttpURLConnection {
        val normalizedPath = if (path.startsWith('/')) path else "/$path"
        val url = URL(baseUrl + normalizedPath)
        return ((network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 5_000
            readTimeout = 7_000
            requestMethod = method
            setRequestProperty("Accept", accept)
            setRequestProperty("Cache-Control", "no-cache")
        }
    }

    private fun requireSuccess(result: GhostHttpResult): GhostHttpResult {
        if (result.status in 200..299) return result
        throw GhostApiException(result.status, errorDetail(result.status, result.body))
    }
}

private fun normalizeBaseUrl(url: String): String =
    url.trim().trimEnd('/').ifBlank { GhostApiClient.DEFAULT_BASE_URL }

private fun errorDetail(status: Int, body: String): String {
    val jsonMessage = runCatching {
        JSONObject(body).optString("error").ifBlank { JSONObject(body).optString("message") }
    }.getOrNull()
    return jsonMessage?.takeIf { it.isNotBlank() }
        ?: body.take(180).ifBlank { "HTTP $status" }
}
