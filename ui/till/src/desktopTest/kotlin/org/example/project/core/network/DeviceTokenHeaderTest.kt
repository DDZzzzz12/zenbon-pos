package org.example.project.core.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.example.project.core.device.DeviceTokenProvider
import org.example.project.testutils.fakes.FakeTokenStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeviceTokenHeaderTest {
    @AfterTest
    fun tearDown() {
        DeviceTokenProvider.reset()
    }

    private val noopLogger =
        object : Logger {
            override fun log(message: String) = Unit
        }

    @Test
    fun `attaches X-Device-Token header when a device token is present`() =
        runTest {
            DeviceTokenProvider.token = "dev-token-abc"
            val captured = StringBuilder()
            val engine =
                MockEngine { request ->
                    captured.append(request.headers["X-Device-Token"] ?: "")
                    respond(content = "", status = HttpStatusCode.OK)
                }
            val client =
                createPosHttpClient(
                    engine = engine,
                    tokenStore = FakeTokenStore.create(),
                    onUnauthorized = { },
                    baseUrlResolver = { "http://localhost:8080/" },
                    logger = noopLogger,
                    logLevel = LogLevel.NONE,
                )
            client.get("http://localhost:8080/api/ping")
            assertEquals("dev-token-abc", captured.toString())
        }

    @Test
    fun `a request sent before the device token is loaded carries the token the loader reads`() =
        runTest {
            // The boot read found nothing (key store not ready), so no token is held — the request loads it.
            DeviceTokenProvider.loader = { "dev-token-loaded" }
            val captured = mutableListOf<String?>()
            val engine =
                MockEngine { request ->
                    captured += request.headers.getAll("X-Device-Token")?.joinToString()
                    respond(content = "", status = HttpStatusCode.OK)
                }
            val client =
                createPosHttpClient(
                    engine = engine,
                    tokenStore = FakeTokenStore.create(),
                    onUnauthorized = { },
                    baseUrlResolver = { "http://localhost:8080/" },
                    logger = noopLogger,
                    logLevel = LogLevel.NONE,
                )
            client.get("http://localhost:8080/api/ping")
            assertEquals(listOf<String?>("dev-token-loaded"), captured, "exactly one X-Device-Token, the loaded one")
        }

    @Test
    fun `omits X-Device-Token header when no device token is present`() =
        runTest {
            DeviceTokenProvider.token = null
            var seen: String? = "sentinel"
            val engine =
                MockEngine { request ->
                    seen = request.headers["X-Device-Token"]
                    respond(content = "", status = HttpStatusCode.OK)
                }
            val client =
                createPosHttpClient(
                    engine = engine,
                    tokenStore = FakeTokenStore.create(),
                    onUnauthorized = { },
                    baseUrlResolver = { "http://localhost:8080/" },
                    logger = noopLogger,
                    logLevel = LogLevel.NONE,
                )
            client.get("http://localhost:8080/api/ping")
            assertNull(seen)
        }
}
