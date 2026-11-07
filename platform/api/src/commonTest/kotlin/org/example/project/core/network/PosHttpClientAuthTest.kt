package org.example.project.core.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import org.example.project.core.auth.TokenStore
import org.example.project.core.utils.AuthTransport
import org.example.project.testutils.FakeKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PosHttpClientAuthTest {
    private val silentLogger =
        object : Logger {
            override fun log(message: String) = Unit
        }

    private val refreshResponseJson =
        """
        {
          "token": {
            "accessToken": "access-2",
            "refreshToken": "refresh-2",
            "expiresIn": 3600
          },
          "employee": {
            "id": "1",
            "username": "E1",
            "employeeNumber": "E1",
            "firstName": "Ada",
            "lastName": "Lovelace",
            "role": "MANAGER",
            "permissions": [],
            "language": "en"
          }
        }
        """.trimIndent()

    /** The same response from a server that reports capabilities — i.e. every current server. */
    private val refreshResponseWithCapabilitiesJson =
        refreshResponseJson.replace(
            """"language": "en"""",
            """"language": "en",
            "capabilities": ["ORDER_READ", "ORDER_WRITE"]""",
        )

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val unauthorizedHeaders = headersOf(HttpHeaders.WWWAuthenticate, "Bearer realm=\"pos\"")

    private fun tokenStoreWith(
        access: String?,
        refresh: String?,
    ) = TokenStore(FakeKeyValueStore()).apply {
        accessToken = access
        refreshToken = refresh
    }

    @Test
    fun a_401_triggers_a_refresh_then_retries_and_rotates_the_stored_tokens() =
        runTest {
            val store = tokenStoreWith("access-1", "refresh-1")
            var signedOut = false
            var protectedCalls = 0

            val engine =
                MockEngine { request ->
                    when {
                        request.url.encodedPath.endsWith("/auth/refresh") ->
                            respond(refreshResponseJson, HttpStatusCode.OK, jsonHeaders)

                        else -> {
                            protectedCalls++
                            if (protectedCalls == 1) {
                                respond("", HttpStatusCode.Unauthorized, unauthorizedHeaders)
                            } else {
                                respond("""{"ok":true}""", HttpStatusCode.OK, jsonHeaders)
                            }
                        }
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

            val response = client.get("/api/products")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("access-2", store.accessToken)
            assertEquals("refresh-2", store.refreshToken)
            assertEquals("MANAGER", store.role)
            assertFalse(signedOut, "a recoverable 401 must not sign the user out")
        }

    @Test
    fun a_rejected_refresh_signs_the_user_out() =
        runTest {
            val store = tokenStoreWith("access-1", "refresh-1")
            var signedOut = false

            val engine =
                MockEngine { request ->
                    when {
                        request.url.encodedPath.endsWith("/auth/refresh") ->
                            respond("", HttpStatusCode.Unauthorized)

                        else -> respond("", HttpStatusCode.Unauthorized, unauthorizedHeaders)
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

            client.get("/api/products")

            assertTrue(signedOut, "a genuinely failed refresh must sign the user out")
        }

    @Test
    fun a_refresh_that_never_reaches_the_server_does_not_sign_the_user_out() =
        runTest {
            val store = tokenStoreWith("access-1", "refresh-1")
            var signedOut = false

            val engine =
                MockEngine { request ->
                    when {
                        // pos-server restarting under a running till: the socket is simply refused.
                        request.url.encodedPath.endsWith("/auth/refresh") -> throw IOException("connection refused")

                        else -> respond("", HttpStatusCode.Unauthorized, unauthorizedHeaders)
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

            client.get("/api/products")

            assertFalse(signedOut, "an unreachable server is not a rejected session")
            assertEquals("refresh-1", store.refreshToken, "and the session's tokens survive it")
        }

    @Test
    fun a_server_error_on_refresh_does_not_sign_the_user_out() =
        runTest {
            val store = tokenStoreWith("access-1", "refresh-1")
            var signedOut = false

            val engine =
                MockEngine { request ->
                    when {
                        request.url.encodedPath.endsWith("/auth/refresh") ->
                            respond("", HttpStatusCode.InternalServerError)

                        else -> respond("", HttpStatusCode.Unauthorized, unauthorizedHeaders)
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

            client.get("/api/products")

            assertFalse(signedOut, "a 500 is the server failing, not the session ending")
        }

    @Test
    fun no_refresh_token_signs_out_without_attempting_a_refresh() =
        runTest {
            val store = tokenStoreWith("access-1", refresh = null)
            var signedOut = false
            var refreshAttempts = 0

            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/auth/refresh")) refreshAttempts++
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

            client.get("/api/products")

            assertTrue(signedOut)
            assertEquals(0, refreshAttempts, "an unrecoverable session must not call /auth/refresh")
        }

    @Test
    fun a_401_with_no_session_does_not_sign_out() =
        runTest {
            // The launch gate validates its device token (X-Device-Token) before any employee logs in, so
            // there is no JWT session at all. A 401 there is a stale-device-token signal for the caller to
            // handle (clear token → re-pair) — NOT a session-expiry. Firing the cold-reset onUnauthorized
            // here hijacks that 401 and loops the launch gate on its "checking" screen.
            val store = tokenStoreWith(access = null, refresh = null)
            var signedOut = false
            var refreshAttempts = 0

            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/auth/refresh")) refreshAttempts++
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

            client.get("/api/v1/terminals/me-by-device")

            assertFalse(signedOut, "a 401 with no session must not cold-reset the launch gate")
            assertEquals(0, refreshAttempts, "with no session there is nothing to refresh")
        }

    @Test
    fun a_cancelled_refresh_propagates_and_does_not_sign_the_user_out() =
        runTest {
            val store = tokenStoreWith("access-1", "refresh-1")
            var signedOut = false

            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/auth/refresh")) {
                        // Simulates a CancellationException surfacing from inside the refresh call
                        // itself (e.g. the HTTP client/engine being closed). This guards that
                        // such an exception is rethrown rather than swallowed and treated as a
                        // failed refresh — which would sign the user out on routine navigation.
                        // With nonCancellableRefresh = true, cancelling the CALLER (see
                        // a_refresh_survives_the_caller_being_cancelled below) no longer reaches
                        // this path at all — the refresh itself keeps running to completion.
                        throw CancellationException("navigated away")
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

            assertFailsWith<CancellationException> { client.get("/api/products") }
            assertFalse(signedOut, "cancellation is not an auth failure")
            Unit
        }

    @Test
    fun a_refresh_survives_the_caller_being_cancelled() =
        runTest {
            // The screen that triggered the refresh can die (e.g. a ViewModel scope cancelled by
            // navigation) while the refresh it kicked off is still in flight. nonCancellableRefresh
            // = true is what keeps the refresh running to completion regardless — this test proves
            // that end state: the rotated tokens land in the store and the caller's cancellation is
            // NOT mistaken for a failed refresh.
            val store = tokenStoreWith("access-1", "refresh-1")
            var signedOut = false
            val enteredRefresh = CompletableDeferred<Unit>()
            val releaseRefresh = CompletableDeferred<Unit>()

            val engine =
                MockEngine { request ->
                    when {
                        request.url.encodedPath.endsWith("/auth/refresh") -> {
                            // Signal that the refresh is in flight, then hold the response until the
                            // test has cancelled the caller — proving the refresh keeps going anyway.
                            enteredRefresh.complete(Unit)
                            releaseRefresh.await()
                            respond(refreshResponseJson, HttpStatusCode.OK, jsonHeaders)
                        }

                        request.headers[HttpHeaders.Authorization] == "Bearer access-2" ->
                            respond("""{"ok":true}""", HttpStatusCode.OK, jsonHeaders)

                        else -> respond("", HttpStatusCode.Unauthorized, unauthorizedHeaders)
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

            val callerJob = launch { client.get("/api/products") }

            enteredRefresh.await()
            callerJob.cancel()
            assertTrue(callerJob.isCancelled)
            releaseRefresh.complete(Unit)
            callerJob.join()

            assertEquals("access-2", store.accessToken, "the refresh must finish rotating tokens despite the caller dying")
            assertEquals("refresh-2", store.refreshToken)
            assertFalse(signedOut, "a caller cancelling mid-refresh is not a failed refresh")
        }

    @Test
    fun login_and_public_health_do_not_carry_the_bearer_but_tse_health_does() =
        runTest {
            val store = tokenStoreWith("access-1", "refresh-1")
            val seenAuthHeaders = mutableMapOf<String, String?>()

            val engine =
                MockEngine { request ->
                    seenAuthHeaders[request.url.encodedPath] = request.headers[HttpHeaders.Authorization]
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

            client.get("/api/auth/login")
            client.get("/health")
            client.get("/api/tse/health")
            client.get("/api/products")

            assertEquals(null, seenAuthHeaders["/api/auth/login"])
            assertEquals(null, seenAuthHeaders["/health"])
            assertEquals("Bearer access-1", seenAuthHeaders["/api/tse/health"])
            assertEquals("Bearer access-1", seenAuthHeaders["/api/products"])
        }

    // FO-36: before the fix the count was 2. Ktor's Auth plugin retries any 401 + WWW-Authenticate:
    // Bearer once (refreshTokenIfNeeded returns true without calling refreshTokens when the request
    // was never stamped with a token version, i.e. was not sent preemptively - which login is not,
    // see sendWithoutRequest). PosHttpClient's reAuthorizeOnResponse now excludes the login path.
    @Test
    fun loginRequestIsSentOnce_whenSignedOutAndTheServerAnswers401() =
        runTest {
            // The UI review saw two POST /api/auth/login for one bad-credentials submit. This pins how
            // many requests actually leave the client.
            val store = tokenStoreWith(access = null, refresh = null)
            var loginRequests = 0
            var refreshRequests = 0
            var signedOut = false

            val engine =
                MockEngine { request ->
                    when {
                        request.url.encodedPath.endsWith("/auth/login") -> loginRequests++
                        request.url.encodedPath.endsWith("/auth/refresh") -> refreshRequests++
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

            client.post("/api/auth/login")

            assertEquals(1, loginRequests, "one submit must be one login POST")
            assertEquals(0, refreshRequests)
            assertFalse(signedOut)
        }

    @Test
    fun a_successful_refresh_stores_the_capability_set_and_announces_it() =
        runTest {
            // Both halves matter, and only the first was true when this was written: the refresh
            // wrote TokenStore and told nobody, so composeApp's cached set and adminApp's memoised
            // sidebar both kept the login-time answer for the whole session. A group change then
            // waited for logout — the "revocation waits for token expiry" complaint defect D5 was
            // about, reintroduced one layer up.
            val store = tokenStoreWith("access-1", "refresh-1")
            var announced = 0

            val engine =
                MockEngine { request ->
                    when {
                        request.url.encodedPath.endsWith("/auth/refresh") ->
                            respond(refreshResponseWithCapabilitiesJson, HttpStatusCode.OK, jsonHeaders)

                        request.headers[HttpHeaders.Authorization] == "Bearer access-1" ->
                            respond("", HttpStatusCode.Unauthorized, unauthorizedHeaders)

                        else -> respond("""{"ok":true}""", HttpStatusCode.OK, jsonHeaders)
                    }
                }

            val client =
                createPosHttpClient(
                    engine = engine,
                    tokenStore = store,
                    onUnauthorized = { },
                    onTokensRefreshed = { announced++ },
                    baseUrlResolver = { "/" },
                    logger = silentLogger,
                    logLevel = LogLevel.NONE,
                )

            assertEquals(HttpStatusCode.OK, client.get("/api/products").status)

            assertEquals(setOf("ORDER_READ", "ORDER_WRITE"), store.capabilities)
            assertEquals(1, announced, "a client that caches the resolved set has to be told")
        }

    @Test
    fun a_body_refresh_answer_without_a_refresh_token_signs_out_and_keeps_the_old_session() =
        runTest {
            val store = tokenStoreWith("access-1", "refresh-1")
            var signedOut = false
            val noRefreshTokenJson = refreshResponseJson.replace(""""refreshToken": "refresh-2",""", "")
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/auth/refresh")) {
                        respond(noRefreshTokenJson, HttpStatusCode.OK, jsonHeaders)
                    } else {
                        respond("", HttpStatusCode.Unauthorized, unauthorizedHeaders)
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

            client.get("/api/products")

            assertTrue(signedOut, "a refresh answer that cannot renew again must sign out")
            assertEquals("access-1", store.accessToken, "nothing from the broken answer may be stored")
        }

    @Test
    fun a_refresh_from_a_server_that_reports_nothing_leaves_the_set_unknown() =
        runTest {
            // An older server. Null must mean UNKNOWN, so the client falls back to role gating —
            // reading it as an empty grant would hide every control and look like a broken app.
            val store = tokenStoreWith("access-1", "refresh-1")
            store.capabilities = setOf("ORDER_READ")

            val engine =
                MockEngine { request ->
                    when {
                        request.url.encodedPath.endsWith("/auth/refresh") ->
                            respond(refreshResponseJson, HttpStatusCode.OK, jsonHeaders)

                        request.headers[HttpHeaders.Authorization] == "Bearer access-1" ->
                            respond("", HttpStatusCode.Unauthorized, unauthorizedHeaders)

                        else -> respond("""{"ok":true}""", HttpStatusCode.OK, jsonHeaders)
                    }
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

            assertEquals(HttpStatusCode.OK, client.get("/api/products").status)
            assertEquals(null, store.capabilities, "an unreported set is unknown, not empty")
        }

    @Test
    fun a_device_credential_only_request_carries_no_bearer_and_its_401_is_handed_back_untouched() =
        runTest {
            // NEW-1: the launch's device lookup carried the person's (expired) bearer, and the server's
            // session code replaced the device's own verdict. A device-only request must reach the server
            // without the bearer and must not spend or end the person's session on its 401.
            val store = tokenStoreWith("access-1", "refresh-1")
            var signedOut = false
            var refreshAttempts = 0
            val seenAuth = mutableListOf<String?>()

            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/auth/refresh")) refreshAttempts++
                    seenAuth += request.headers[HttpHeaders.Authorization]
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

            val response = client.get("/api/v1/terminals/me-by-device") { onDeviceCredentialOnly() }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(listOf<String?>(null), seenAuth, "sent once, with no bearer, and not retried")
            assertEquals(0, refreshAttempts, "a device verdict must not trigger a refresh")
            assertFalse(signedOut, "a device verdict says nothing about the person's session")
            assertEquals("refresh-1", store.refreshToken)
        }

    @Test
    fun body_transport_never_sends_the_cookie_transport_header() =
        runTest {
            // If composeApp ever sent this header, the server would move the till's refresh
            // token into a cookie OkHttp drops, breaking the till's own renewal.
            val store = tokenStoreWith("access-1", "refresh-1")
            var seenOnProtectedRequest: String? = "unset"
            var seenOnRefreshRequest: String? = "unset"
            var protectedCalls = 0

            val engine =
                MockEngine { request ->
                    when {
                        request.url.encodedPath.endsWith("/auth/refresh") -> {
                            seenOnRefreshRequest = request.headers[AuthTransport.HEADER]
                            respond(refreshResponseJson, HttpStatusCode.OK, jsonHeaders)
                        }

                        else -> {
                            protectedCalls++
                            seenOnProtectedRequest = request.headers[AuthTransport.HEADER]
                            if (protectedCalls == 1) {
                                respond("", HttpStatusCode.Unauthorized, unauthorizedHeaders)
                            } else {
                                respond("""{"ok":true}""", HttpStatusCode.OK, jsonHeaders)
                            }
                        }
                    }
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

            val response = client.get("/api/products")

            assertEquals(HttpStatusCode.OK, response.status)
            assertNull(seenOnProtectedRequest, "body transport must not send X-Auth-Transport on the protected route")
            assertNull(seenOnRefreshRequest, "body transport must not send X-Auth-Transport on the refresh call either")
        }
}
