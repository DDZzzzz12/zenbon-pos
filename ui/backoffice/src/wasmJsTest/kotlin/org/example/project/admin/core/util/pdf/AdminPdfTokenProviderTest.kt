package org.example.project.admin.core.util.pdf

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.example.project.admin.core.network.AdminHttpCall
import org.example.project.core.auth.TokenStore
import org.example.project.core.data.database.KeyValueStore
import org.example.project.core.data.remote.impl.KtorAuthApiClient
import org.example.project.core.network.createPosHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [AdminPdfTokenProvider] against the app's real HTTP client: a forced refresh must go through the one
 * refresh path `createPosHttpClient`'s Auth plugin owns (so concurrent refreshes coalesce and a
 * rejected refresh signs out exactly as everywhere else), never a second hand-written `/auth/refresh`.
 */
class AdminPdfTokenProviderTest {
    private class MapStore : KeyValueStore {
        val map = mutableMapOf<String, String>()

        override fun getString(key: String): String? = map[key]

        override fun setString(
            key: String,
            value: String,
        ) {
            map[key] = value
        }

        override fun remove(key: String) {
            map.remove(key)
        }

        override fun getAll(): Map<String, String> = map.toMap()

        override fun clear() = map.clear()
    }

    private val silentLogger =
        object : Logger {
            override fun log(message: String) = Unit
        }

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val unauthorizedHeaders = headersOf(HttpHeaders.WWWAuthenticate, "Bearer realm=\"pos\"")

    private val refreshResponseJson =
        """
        {
          "token": { "accessToken": "access-2", "refreshToken": "refresh-2", "expiresIn": 3600 },
          "employee": {
            "id": "1", "username": "E1", "employeeNumber": "E1", "firstName": "Ada", "lastName": "Lovelace",
            "role": "MANAGER", "permissions": [], "language": "en"
          }
        }
        """.trimIndent()

    private class Server {
        var refreshCalls = 0
        var validateCalls = 0
        val requests = mutableListOf<String>()
    }

    private fun storeWith(
        access: String?,
        refresh: String?,
    ) = TokenStore(MapStore()).apply {
        accessToken = access
        refreshToken = refresh
    }

    /** The app's client: `access-1` is expired server-side, `access-2` is what a refresh hands out. */
    private fun providerFor(
        store: TokenStore,
        server: Server,
        refreshStatus: HttpStatusCode = HttpStatusCode.OK,
        onUnauthorized: () -> Unit = {},
    ): AdminPdfTokenProvider {
        val engine =
            MockEngine { request ->
                server.requests += request.url.encodedPath
                when {
                    request.url.encodedPath.endsWith("/auth/refresh") -> {
                        server.refreshCalls++
                        if (refreshStatus == HttpStatusCode.OK) {
                            respond(refreshResponseJson, HttpStatusCode.OK, jsonHeaders)
                        } else {
                            respond("", refreshStatus, jsonHeaders)
                        }
                    }
                    request.url.encodedPath.endsWith("/auth/validate") -> {
                        server.validateCalls++
                        if (request.headers[HttpHeaders.Authorization] == "Bearer access-2") {
                            respond("""{"valid":true,"employeeNumber":"E1"}""", HttpStatusCode.OK, jsonHeaders)
                        } else {
                            respond("", HttpStatusCode.Unauthorized, unauthorizedHeaders)
                        }
                    }
                    else -> respond("", HttpStatusCode.NotFound)
                }
            }
        val client =
            createPosHttpClient(
                engine = engine,
                tokenStore = store,
                onUnauthorized = onUnauthorized,
                baseUrlResolver = { "/" },
                logger = silentLogger,
                logLevel = LogLevel.NONE,
            )
        return AdminPdfTokenProvider(store, KtorAuthApiClient(AdminHttpCall(client), client))
    }

    @Test
    fun without_force_it_returns_the_stored_access_token_and_sends_nothing() =
        runTest {
            val store = storeWith("access-1", "refresh-1")
            val server = Server()

            assertEquals("access-1", providerFor(store, server).token(forceRefresh = false))
            assertTrue(server.requests.isEmpty(), "reading the token must not touch the network: ${server.requests}")
        }

    @Test
    fun a_forced_refresh_runs_the_client_refresh_once_and_returns_the_new_token() =
        runTest {
            val store = storeWith("access-1", "refresh-1")
            val server = Server()

            val token = providerFor(store, server).token(forceRefresh = true)

            assertEquals("access-2", token)
            assertEquals(1, server.refreshCalls, "exactly one refresh, through the Auth plugin")
            assertEquals("access-2", store.accessToken)
            assertEquals("refresh-2", store.refreshToken, "the rotated refresh token is stored")
        }

    @Test
    fun signed_out_it_returns_null_and_sends_nothing() =
        runTest {
            val store = storeWith(access = null, refresh = null)
            val server = Server()
            val provider = providerFor(store, server)

            assertNull(provider.token(forceRefresh = false))
            assertNull(provider.token(forceRefresh = true))
            assertTrue(server.requests.isEmpty(), "no session, nothing to refresh: ${server.requests}")
        }

    @Test
    fun a_rejected_refresh_signs_out_and_returns_null() =
        runTest {
            val store = storeWith("access-1", "refresh-1")
            val server = Server()
            var signedOut = false
            val provider =
                providerFor(store, server, refreshStatus = HttpStatusCode.Unauthorized) {
                    signedOut = true
                    store.clear()
                }

            assertNull(provider.token(forceRefresh = true))
            assertTrue(signedOut, "a rejected refresh ends the session, as on every other request")
            assertEquals(1, server.refreshCalls)
        }

    @Test
    fun a_refresh_that_cannot_reach_the_server_returns_null_and_keeps_the_session() =
        runTest {
            val store = storeWith("access-1", "refresh-1")
            val server = Server()
            var signedOut = false
            val provider =
                providerFor(store, server, refreshStatus = HttpStatusCode.BadGateway) { signedOut = true }

            assertNull(provider.token(forceRefresh = true), "no fresh token was obtained")
            assertEquals(false, signedOut, "a proxy hiccup is no verdict on the session")
            assertEquals("refresh-1", store.refreshToken)
        }
}
