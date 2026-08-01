package com.unkl3errl.helteccontroller.bruce

import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BruceApiClientTest {
    @Test
    fun fieldLogDownloadPathEncodesTheFileNameAsOneQueryValue() {
        assertEquals(
            "/api/heltec/fieldlog/download?name=session-000003-000.ndjson",
            fieldLogDownloadPath("session-000003-000.ndjson"),
        )
        assertEquals(
            "/api/heltec/fieldlog/download?name=name%26action%3Ddelete.ndjson",
            fieldLogDownloadPath("name&action=delete.ndjson"),
        )
    }

    @Test
    fun deviceFileDownloadPathCannotInjectAnotherAction() {
        assertEquals(
            "/file?fs=LittleFS&action=download&name=%2Flogs%2Ftest.txt",
            deviceFileDownloadPath("/logs/test.txt"),
        )
        assertEquals(
            "/file?fs=LittleFS&action=download&name=%2Fx%26action%3Ddelete",
            deviceFileDownloadPath("/x&action=delete"),
        )
    }

    @Test
    fun authenticationIsClearedWhenTheWebUiOriginChanges() {
        TestLoginServer().use { server ->
            val client = BruceApiClient()
            val loopbackUrl = "http://127.0.0.1:${server.port}"
            client.configure(loopbackUrl)
            assertTrue(client.login("admin", "bruce"))

            client.configure("$loopbackUrl/")
            assertTrue("normalizing the same URL must retain its session", client.isAuthenticated)

            client.configure("http://localhost:${server.port}")
            assertFalse("a different WebUI origin must not inherit the session", client.isAuthenticated)
        }
    }

    private class TestLoginServer : AutoCloseable {
        private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port: Int = socket.localPort
        private val worker = thread(name = "BruceApiClientTestServer", isDaemon = true) {
            socket.accept().use { connection ->
                val reader = connection.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) Unit
                connection.getOutputStream().use { output ->
                    output.write(
                        (
                            "HTTP/1.1 302 Found\r\n" +
                                "Location: /\r\n" +
                                "Set-Cookie: BRUCESESSION=test-session; Path=/\r\n" +
                                "Content-Length: 0\r\n" +
                                "Connection: close\r\n\r\n"
                        ).toByteArray(),
                    )
                }
            }
        }

        override fun close() {
            runCatching { socket.close() }
            worker.join(2_000L)
        }
    }
}
