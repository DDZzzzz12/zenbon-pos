package org.example.project.core.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.example.project.core.auth.TokenStore
import org.example.project.core.device.DeviceTokenProvider
import org.example.project.testutils.FakeKeyValueStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Renewal for a login-less station (order-station shell, design §5.1). A station holds an access
 * token and NO refresh token; on 401 the client re-exchanges the device token at
 * `POST /api/v1/terminals/session` instead of calling `/api/auth/refresh`. Cold reset fires ONLY
 * when the exchange is refused (409 revoked / 403 deactivated); a network blip leaves the token
 * in place for the next request.
 */
class PosHttpClientStationSessionTest {
    private val silentLogger =
        object : Logger {
            override fun log(message: String) = Unit
        }

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val unauthorizedHeaders = headersOf(HttpHeaders.WWWAuthenticate, "Bearer realm=\"pos\"")

    private val stationSessionJson =
        """
        {"accessToken":"station-2","expiresAt":"2030-01-01T00:00:00Z","terminalId":"TER-007",
         "role":"ORDER_STATION","capabilities":["ORDER_READ","ORDER_WRITE"]}
        """.trimIndent()

    @AfterTest
    fun tearDown() {
        DeviceTokenProvider.reset()
    }

    private fun stationStore() =
        TokenStore(FakeKeyValueStore()).apply {
            accessToken = "station-1"
            refreshToken = null
            stationRole = "ORDER_STATION"
        }

    private fun isExchange(path: String) = path.endsWith("/terminals/session")

    @Test
    fun a_station_sends_its_bearer_although_it_holds_no_refresh_token() =
        runTest {
            // Stated explicitly: this arm is about loadTokens, which never reads the device token —
            // but the holder is process-global, so the precondition must not depend on test order.
            DeviceTokenProvider.token = null
            val store = stationStore()
            var seenAuth: String? = null
            val engine =
                MockEngine { request ->
                    seenAuth = request.headers[HttpHeaders.Authorization]
                    respond("""{"ok":true}""", HttpStatusCode.OK, jsonHeaders)
                }
            val client =
                createPosHttpClient(
                    engine = engine,
                    tokenStore = store,
                    onUnauthorized = { },
                    baseUrlResolver = { "/" },
                    logger = silentLogger,
                    logLevel = LogLevel.NONE,
                )

            client.get("/api/tables")

            assertEquals("Bearer station-1", seenAuth, "loadTokens used to answer null without a refresh token")
        }

    @Test
    fun a_401_re_exchanges_the_device_token_then_retries() =
        runTest {
            DeviceTokenProvider.token = "dev-tok"
            val store = stationStore()
            var signedOut = false
            var announced = 0
            var exchangeCalls = 0
            var exchangeDeviceToken: String? = null
            var refreshCalls = 0

            val engine =
                MockEngine { request ->
                    val path = request.url.encodedPath
                    when {
                        isExchange(path) -> {
                            exchangeCalls++
                            exchangeDeviceToken = request.headers["X-Device-Token"]
                            respond(stationSessionJson, HttpStatusCode.OK, jsonHeaders)
                        }
                        path.endsWith("/auth/refresh") -> {
                            refreshCalls++
                            respond("", HttpStatusCode.Unauthorized)
                        }
                        request.headers[HttpHeaders.Authorization] == "Bearer station-1" ->
                            respond("", HttpStatusCode.Unauthorized, unauthorizedHeaders)
                        else -> respond("""{"ok":true}""", HttpStatusCode.OK, jsonHeaders)
                    }
                }
            val client =
                createPosHttpClient(
                    engine = engine,
                    tokenStore = store,
                    onUnauthorized = { signedOut = true },
                    onTokensRefreshed = { announced++ },
                    baseUrlResolver = { "/" },
                    logger = silentLogger,
                    logLevel = LogLevel.NONE,
                )

            val response = client.get("/api/tables")

            assertEquals(HttpStatusCode.OK, response.status, "the original request is retried under the new token")
            assertEquals(1, exchangeCalls)
            assertEquals("dev-tok", exchangeDeviceToken, "the exchange authenticates with the device token")
            assertEquals(0, refreshCalls, "a station never calls /auth/refresh")
            assertEquals("station-2", store.accessToken)
            assertNull(store.refreshToken)
            assertEquals("ORDER_STATION", store.stationRole)
            assertEquals(setOf("ORDER_READ", "ORDER_WRITE"), store.capabilities)
            assertEquals(1, announced, "the cached capability set has to be told")
            assertFalse(signedOut, "a successful exchange is not a cold reset")
        }

    @Test
    fun a_401_re_exchanges_a_device_token_that_has_to_be_loaded_first() =
        runTest {
            // The boot could not open the sealed token, so none is held; the renewal must load it, not sign out.
            var loads = 0
            DeviceTokenProvider.loader = {
                loads++
                "dev-tok"
            }
            val store = stationStore()
            var signedOut = false
            var exchangeDeviceToken: String? = null
            val engine =
                MockEngine { request ->
                    when {
                        isExchange(request.url.encodedPath) -> {
                            exchangeDeviceToken = request.headers["X-Device-Token"]
                            respond(stationSessionJson, HttpStatusCode.OK, jsonHeaders)
                        }
                        request.headers[HttpHeaders.Authorization] == "Bearer station-1" ->
                            respond("", HttpStatusCode.Unauthorized, unauthorizedHeaders)
                        else -> respond("""{"ok":true}""", HttpStatusCode.OK, jsonHeaders)
                    }
                }
            val client =
                createPosHttpClient(
                    engine = engine,
                    tokenStore = store,
                    onUnauthorized = { signedOut = true },
                    baseUrlResolver = { "/" },
                    logger = silentLogger,
                    logLevel = LogLevel.NONE,
                )

            // The original request carries X-Device-Token itself, so the header plugin loads nothing for it: the load has
            // to come from the renewal's own check, which is what this test pins.
            val response = client.get("/api/tables") { headers.append("X-Device-Token", "dev-tok") }

            assertEquals(HttpStatusCode.OK, response.status, "the original request is retried under the new token")
            assertEquals("dev-tok", exchangeDeviceToken, "the exchange carries the loaded device token")
            assertEquals(1, loads, "the renewal loaded the device token once, and the exchange reused it")
            assertEquals("station-2", store.accessToken)
            assertFalse(signedOut, "a station whose device token was merely not loaded yet is not signed out")
        }

    @Test
    fun a_revoked_station_is_cold_reset_once() =
        runTest {
            DeviceTokenProvider.token = "dev-tok"
            val store = stationStore()
            var signedOut = 0
            var exchangeCalls = 0
            val engine =
                MockEngine { request ->
                    if (isExchange(request.url.encodedPath)) {
                        exchangeCalls++
                        respond("""{"error":"Device not paired"}""", HttpStatusCode.Conflict, jsonHeaders)
                    } else {
                        respond("", HttpStatusCode.Unauthorized, unauthorizedHeaders)
                    }
                }
            val client =
                createPosHttpClient(
                    engine = engine,
                    tokenStore = store,
                    onUnauthorized = { signedOut++ },
                    baseUrlResolver = { "/" },
                    logger = silentLogger,
                    logLevel = LogLevel.NONE,
                )

            val response = client.get("/api/tables")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(1, exchangeCalls)
            assertEquals(1, signedOut, "409 means revoked: back to pairing, exactly once")
        }

    /** E2E-2026-09-18 #15: the server now answers these refusals as problem+json; routing is by status. */
    @Test
    fun a_revoked_station_answered_as_problem_json_is_still_cold_reset_once() =
        runTest {
            DeviceTokenProvider.token = "dev-tok"
            val store = stationStore()
            var signedOut = 0
            var exchangeCalls = 0
            val problemHeaders = headersOf(HttpHeaders.ContentType, "application/problem+json")
            val engine =
                MockEngine { request ->
                    if (isExchange(request.url.encodedPath)) {
                        exchangeCalls++
                        respond(
                            """{"type":"https://pos.example.com/errors/device-not-paired","title":"Device Not Paired",""" +
                                """"status":409,"code":"DEVICE_NOT_PAIRED","detail":"Device not paired","traceId":"t-1"}""",
                            HttpStatusCode.Conflict,
                            problemHeaders,
                        )
                    } else {
                        respond("", HttpStatusCode.Unauthorized, unauthorizedHeaders)
                    }
                }
            val client =
                createPosHttpClient(
                    engine = engine,
                    tokenStore = store,
                    onUnauthorized = { signedOut++ },
                    baseUrlResolver = { "/" },
                    logger = silentLogger,
                    logLevel = LogLevel.NONE,
                )

            val response = client.get("/api/tables")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(1, exchangeCalls)
            assertEquals(1, signedOut, "a problem+json 409 routes exactly like the legacy body")
        }

    @Test
    fun a_deactivated_station_is_cold_reset() =
        runTest {
            DeviceTokenProvider.token = "dev-tok"
            val store = stationStore()
            var signedOut = 0
            var exchangeCalls = 0
            val engine =
                MockEngine { request ->
                    if (isExchange(request.url.encodedPath)) {
                        exchangeCalls++
                        respond("""{"error":"Terminal is deactivated"}""", HttpStatusCode.Forbidden, jsonHeaders)
                    } else {
                        respond("", HttpStatusCode.Unauthorized, unauthorizedHeaders)
                    }
                }
            val client =
                createPosHttpClient(
                    engine = engine,
                    tokenStore = store,
                    onUnauthorized = { signedOut++ },
                    baseUrlResolver = { "/" },
                    logger = silentLogger,
                    logLevel = LogLevel.NONE,
                )

            client.get("/api/tables")

            assertEquals(1, exchangeCalls)
            assertEquals(1, signedOut, "403 means deactivated: back to pairing, exactly once")
        }

    @Test
    fun a_403_browser_station_disabled_does_not_cold_reset_and_keeps_the_device_token() =
        runTest {
            DeviceTokenProvider.token = "dev-tok"
            val store = stationStore()
            var signedOut = 0
            val engine =
                MockEngine { request ->
                    if (isExchange(request.url.encodedPath)) {
                        respond(
                            """{"title":"Forbidden","status":403,"code":"BROWSER_STATION_DISABLED","detail":"disabled"}""",
                            HttpStatusCode.Forbidden,
                            headersOf(HttpHeaders.ContentType, "application/problem+json"),
                        )
                    } else {
                        respond("", HttpStatusCode.Unauthorized, unauthorizedHeaders)
                    }
                }
            val client =
                createPosHttpClient(
                    engine = engine,
                    tokenStore = store,
                    onUnauthorized = { signedOut++ },
                    baseUrlResolver = { "/" },
                    logger = silentLogger,
                    logLevel = LogLevel.NONE,
                )

            client.get("/api/tables")

            assertEquals(0, signedOut, "the shop setting is not a verdict on the device: no cold reset")
            assertEquals("dev-tok", DeviceTokenProvider.token)
        }

    @Test
    fun a_transport_failure_during_the_exchange_keeps_the_token_and_does_not_cold_reset() =
        runTest {
            DeviceTokenProvider.token = "dev-tok"
            val store = stationStore()
            var signedOut = false
            val engine =
                MockEngine { request ->
                    if (isExchange(request.url.encodedPath)) {
                        throw RuntimeException("connection refused")
                    }
                    respond("", HttpStatusCode.Unauthorized, unauthorizedHeaders)
                }
            val client =
                createPosHttpClient(
                    engine = engine,
                    tokenStore = store,
                    onUnauthorized = { signedOut = true },
                    baseUrlResolver = { "/" },
                    logger = silentLogger,
                    logLevel = LogLevel.NONE,
                )

            val response = client.get("/api/tables")

            assertEquals(HttpStatusCode.Unauthorized, response.status, "the request fails; the next one tries again")
            assertFalse(signedOut, "a network blip is not a revocation")
            assertEquals("station-1", store.accessToken, "the token stays in place")
        }

    /**
     * A station whose device token cannot be read right now — the key store was not ready, and the provider is inside
     * its no-retry window — is not cold-reset. The cold reset clears the device token, and a token that is merely
     * unreadable for now would be destroyed with the pairing. The request fails as a network blip does, and a later one
     * renews once the token can be read.
     */
    @Test
    fun a_station_whose_device_token_cannot_be_read_right_now_is_not_cold_reset() =
        runTest {
            var loads = 0
            DeviceTokenProvider.loader = {
                loads++
                null
            }
            val store = stationStore()
            var signedOut = false
            var exchangeCalls = 0
            val engine =
                MockEngine { request ->
                    if (isExchange(request.url.encodedPath)) exchangeCalls++
                    respond("", HttpStatusCode.Unauthorized, unauthorizedHeaders)
                }
            val client =
                createPosHttpClient(
                    engine = engine,
                    tokenStore = store,
                    // In composeApp this is the cold reset, which clears the device token.
                    onUnauthorized = { signedOut = true },
                    baseUrlResolver = { "/" },
                    logger = silentLogger,
                    logLevel = LogLevel.NONE,
                )

            val response = client.get("/api/tables")

            assertEquals(HttpStatusCode.Unauthorized, response.status, "the request fails; a later one tries again")
            assertFalse(signedOut, "a device token that cannot be read right now is not a revocation")
            assertTrue(loads > 0, "the device token was asked for")
            assertEquals(0, exchangeCalls, "nothing to exchange without the device token")
            assertEquals("station-1", store.accessToken, "the station session stays in place")
            assertEquals("ORDER_STATION", store.stationRole)
        }
}
