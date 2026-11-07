package org.example.project.core.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.request.get
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.example.project.core.auth.TokenStore
import org.example.project.core.utils.AuthTransport
import org.example.project.testutils.FakeKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PosHttpClientCookieTransportTest {
    private val silentLogger =
        object : Logger {
            override fun log(message: String) = Unit
        }

    /** A refresh answer under cookie transport: no refreshToken in the body. */
    private val cookieRefreshJson =
        """
        {
          "token": { "accessToken": "access-2", "expiresIn": 900 },
          "employee": {
            "id": "1", "username": "E1", "employeeNumber": "E1", "firstName": "Ada", "lastName": "Lovelace",
            "role": "MANAGER", "permissions": [], "language": "en", "capabilities": ["CATALOG_MANAGE"]
          }
        }
        """.trimIndent()

    /** A refresh answer that leaks a body refresh token even though the transport is cookie. */
    private val cookieRefreshWithLeakedBodyTokenJson =
        """
        {
          "token": { "accessToken": "access-2", "refreshToken": "leaked", "expiresIn": 900 },
          "employee": {
            "id": "1", "username": "E1", "employeeNumber": "E1", "firstName": "Ada", "lastName": "Lovelace",
            "role": "MANAGER", "permissions": [], "language": "en", "capabilities": ["CATALOG_MANAGE"]
          }
        }
        """.trimIndent()

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val unauthorizedHeaders = headersOf(HttpHeaders.WWWAuthenticate, "Bearer realm=\"pos\"")

    private class Calls {
        var refresh = 0
        var refreshHadHeader = false
        var refreshBodyLength: Long? = -1
    }

    /**
     * Protected routes accept only `Bearer access-2`; the refresh endpoint answers [refreshStatus].
     *
     * @param protectedUnauthorizedHeaders headers the protected route's 401 carries — the real
     *   pos-server sends none on capability-protected routes, so a test can pass [headersOf] with
     *   no arguments to reproduce that.
     */
    private fun engine(
        calls: Calls,
        refreshStatus: HttpStatusCode = HttpStatusCode.OK,
        protectedUnauthorizedHeaders: Headers = unauthorizedHeaders,
    ) = MockEngine { request ->
        if (request.url.encodedPath.endsWith("/auth/refresh")) {
            calls.refresh++
            calls.refreshHadHeader = request.headers[AuthTransport.HEADER] == AuthTransport.COOKIE
            calls.refreshBodyLength = request.body.contentLength
            if (refreshStatus == HttpStatusCode.OK) {
                respond(cookieRefreshJson, HttpStatusCode.OK, jsonHeaders)
            } else {
                respond("", refreshStatus, unauthorizedHeaders)
            }
        } else if (request.headers[HttpHeaders.Authorization] == "Bearer access-2") {
            respond("""{"ok":true}""", HttpStatusCode.OK, jsonHeaders)
        } else {
            respond("", HttpStatusCode.Unauthorized, protectedUnauthorizedHeaders)
        }
    }

    private fun client(
        store: TokenStore,
        engine: MockEngine,
        onUnauthorized: () -> Unit,
    ) = createPosHttpClient(
        engine = engine,
        tokenStore = store,
        onUnauthorized = onUnauthorized,
        baseUrlResolver = { "/" },
        logger = silentLogger,
        logLevel = LogLevel.NONE,
        refreshTransport = RefreshTransport.COOKIE,
    )

    @Test
    fun an_expired_access_token_is_renewed_through_the_cookie_and_no_refresh_token_is_stored() =
        runTest {
            val store = TokenStore(FakeKeyValueStore()).apply { accessToken = "access-1" }
            val calls = Calls()
            var signedOut = false

            val response = client(store, engine(calls)) { signedOut = true }.get("/api/products")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(1, calls.refresh)
            assertTrue(calls.refreshHadHeader, "the refresh must announce cookie transport")
            assertEquals(0L, calls.refreshBodyLength, "the refresh must carry no body")
            assertEquals("access-2", store.accessToken)
            assertNull(store.refreshToken)
            assertEquals(setOf("CATALOG_MANAGE"), store.capabilities)
            assertFalse(signedOut)
        }

    @Test
    fun a_page_load_with_an_empty_store_resumes_the_session_from_the_cookie() =
        runTest {
            val store = TokenStore(FakeKeyValueStore())
            val calls = Calls()

            val response = client(store, engine(calls)) {}.get("/api/auth/validate")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(1, calls.refresh)
            assertEquals("access-2", store.accessToken)
        }

    @Test
    fun a_refused_cookie_refresh_signs_out() =
        runTest {
            val store = TokenStore(FakeKeyValueStore())
            var signedOut = false
            val calls = Calls()

            val response = client(store, engine(calls, HttpStatusCode.Unauthorized)) { signedOut = true }.get("/api/auth/validate")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(signedOut)
            assertEquals(1, calls.refresh)
        }

    @Test
    fun a_header_less_401_still_resumes_from_the_cookie() =
        runTest {
            // The real pos-server sends no WWW-Authenticate on capability-protected routes — the
            // refresh must not depend on that header being present to fire.
            val store = TokenStore(FakeKeyValueStore())
            val calls = Calls()

            val response =
                client(store, engine(calls, protectedUnauthorizedHeaders = headersOf())) {}
                    .get("/api/products")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(1, calls.refresh)
            assertEquals("access-2", store.accessToken)
        }

    @Test
    fun every_request_under_cookie_transport_carries_the_transport_header() =
        runTest {
            // Login relies on this too: without the header the server never sets the cookie.
            val store = TokenStore(FakeKeyValueStore()).apply { accessToken = "access-2" }
            var seenHeader: String? = null

            val engine =
                MockEngine { request ->
                    seenHeader = request.headers[AuthTransport.HEADER]
                    respond("""{"ok":true}""", HttpStatusCode.OK, jsonHeaders)
                }

            val response = client(store, engine) {}.get("/api/products")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(AuthTransport.COOKIE, seenHeader)
        }

    @Test
    fun a_body_refresh_token_under_cookie_transport_is_refused_and_not_stored() =
        runTest {
            // A stripped X-Auth-Transport header does not explain this: the server answers a
            // non-200 to a headerless /auth/refresh, it never silently falls back to body
            // transport. A 200 carrying a refresh token here means the SERVER put one in a body
            // while we asked for cookie transport — a server-side contract violation, leaking a
            // refresh token into JS-readable JSON on what was supposed to be a cookie-only session.
            val store = TokenStore(FakeKeyValueStore()).apply { accessToken = "access-1" }
            var signedOut = false

            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/auth/refresh")) {
                        respond(cookieRefreshWithLeakedBodyTokenJson, HttpStatusCode.OK, jsonHeaders)
                    } else {
                        respond("", HttpStatusCode.Unauthorized, unauthorizedHeaders)
                    }
                }

            client(store, engine) { signedOut = true }.get("/api/products")

            assertTrue(signedOut, "a leaked body refresh token under cookie transport must sign out")
            assertNull(store.refreshToken, "the leaked refresh token must never be stored")
            assertEquals("access-1", store.accessToken, "nothing from the broken answer may be stored")
        }
}
