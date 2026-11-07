package org.example.project.core.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.example.project.core.auth.TokenStore
import org.example.project.core.data.database.KeyValueStore
import org.example.project.core.device.DeviceTokenProvider
import org.example.project.testutils.FakeKeyValueStore
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The HTTP client reads and writes the token store on the dispatcher it is given, never on the caller's.
 *
 * composeApp seals the tokens, so opening or sealing one can block on the Android Keystore or the desktop keychain
 * for seconds, and the requests that trigger `loadTokens` and a refresh are often launched from the UI thread.
 * JVM-only because it tells the storage dispatcher apart by the thread it runs on.
 */
class PosHttpClientStorageDispatcherTest {
    private val storage =
        Executors
            .newSingleThreadExecutor { runnable -> Thread(runnable, STORAGE_THREAD).apply { isDaemon = true } }
            .asCoroutineDispatcher()

    private val silentLogger =
        object : Logger {
            override fun log(message: String) = Unit
        }

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val unauthorizedHeaders = headersOf(HttpHeaders.WWWAuthenticate, "Bearer realm=\"pos\"")

    @AfterTest
    fun tearDown() {
        storage.close()
        DeviceTokenProvider.reset()
    }

    /** A [KeyValueStore] that records every read, write and removal with the thread it ran on. */
    private class ThreadRecordingStore : KeyValueStore {
        private val delegate = FakeKeyValueStore()
        private val records = mutableListOf<Pair<String, String>>()

        /** Every access since the last [reset], as `get:<key>`, `set:<key>` or `remove:<key>`. */
        val accesses: List<String>
            get() = synchronized(records) { records.map { it.first } }

        /** The accesses since the last [reset] that did not run on the storage thread. */
        val offStorageThread: List<String>
            get() = synchronized(records) { records.filterNot { it.second == STORAGE_THREAD }.map { it.first } }

        fun reset() {
            synchronized(records) { records.clear() }
        }

        override fun getString(key: String): String? {
            record("get", key)
            return delegate.getString(key)
        }

        override fun setString(
            key: String,
            value: String,
        ) {
            record("set", key)
            delegate.setString(key, value)
        }

        override fun remove(key: String) {
            record("remove", key)
            delegate.remove(key)
        }

        override fun getAll(): Map<String, String> = delegate.getAll()

        override fun clear() = delegate.clear()

        private fun record(
            operation: String,
            key: String,
        ) {
            synchronized(records) { records += "$operation:$key" to Thread.currentThread().name }
        }
    }

    private fun client(
        store: TokenStore,
        engine: MockEngine,
        refreshTransport: RefreshTransport = RefreshTransport.BODY,
    ) = createPosHttpClient(
        engine = engine,
        tokenStore = store,
        onUnauthorized = { },
        baseUrlResolver = { "/" },
        logger = silentLogger,
        logLevel = LogLevel.NONE,
        refreshTransport = refreshTransport,
        storageDispatcher = storage,
    )

    /** Protected routes accept only `Bearer [acceptedBearer]`; [renewalPath] answers [renewalJson]. */
    private fun engine(
        renewalPath: String,
        renewalJson: String,
        acceptedBearer: String,
    ) = MockEngine { request ->
        when {
            request.url.encodedPath.endsWith(renewalPath) -> respond(renewalJson, HttpStatusCode.OK, jsonHeaders)
            request.headers[HttpHeaders.Authorization] == "Bearer $acceptedBearer" ->
                respond("""{"ok":true}""", HttpStatusCode.OK, jsonHeaders)
            else -> respond("", HttpStatusCode.Unauthorized, unauthorizedHeaders)
        }
    }

    @Test
    fun a_refresh_loads_reads_and_stores_the_tokens_on_the_storage_dispatcher() =
        runTest {
            val disk = ThreadRecordingStore()
            val store =
                TokenStore(disk).apply {
                    accessToken = "access-1"
                    refreshToken = "refresh-1"
                }
            disk.reset()

            val response = client(store, engine("/auth/refresh", BODY_REFRESH_JSON, "access-2")).get("/api/products")
            val accesses = disk.accesses
            val offThread = disk.offStorageThread

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(
                accesses.containsAll(listOf("get:jwt_access_token", "get:jwt_refresh_token", "set:jwt_refresh_token")),
                "expected the bearer load, the refresh-token read and the rotated-token write, got $accesses",
            )
            assertEquals(emptyList(), offThread, "a token was read or written on the caller's thread")
        }

    @Test
    fun a_station_exchange_reads_and_stores_the_session_on_the_storage_dispatcher() =
        runTest {
            DeviceTokenProvider.loader = { "dev-tok" }
            val disk = ThreadRecordingStore()
            val store =
                TokenStore(disk).apply {
                    accessToken = "station-1"
                    stationRole = "ORDER_STATION"
                }
            disk.reset()

            val response =
                client(store, engine("/terminals/session", STATION_SESSION_JSON, "station-2")).get("/api/tables")
            val accesses = disk.accesses
            val offThread = disk.offStorageThread

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(
                accesses.containsAll(listOf("get:jwt_station_role", "set:jwt_access_token", "set:jwt_station_role")),
                "expected the station check and the stored session, got $accesses",
            )
            assertEquals(emptyList(), offThread, "a token was read or written on the caller's thread")
        }

    @Test
    fun a_cookie_refresh_stores_the_session_on_the_storage_dispatcher() =
        runTest {
            val disk = ThreadRecordingStore()
            val store = TokenStore(disk).apply { accessToken = "access-1" }
            disk.reset()

            val response =
                client(store, engine("/auth/refresh", COOKIE_REFRESH_JSON, "access-2"), RefreshTransport.COOKIE)
                    .get("/api/products")
            val accesses = disk.accesses
            val offThread = disk.offStorageThread

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue("set:jwt_access_token" in accesses, "expected the renewed token to be stored, got $accesses")
            assertEquals(emptyList(), offThread, "a token was read or written on the caller's thread")
        }

    private companion object {
        const val STORAGE_THREAD = "token-storage"

        val BODY_REFRESH_JSON =
            """
            {
              "token": { "accessToken": "access-2", "refreshToken": "refresh-2", "expiresIn": 3600 },
              "employee": {
                "id": "1", "username": "E1", "employeeNumber": "E1", "firstName": "Ada", "lastName": "Lovelace",
                "role": "MANAGER", "permissions": [], "language": "en"
              }
            }
            """.trimIndent()

        val COOKIE_REFRESH_JSON =
            """
            {
              "token": { "accessToken": "access-2", "expiresIn": 900 },
              "employee": {
                "id": "1", "username": "E1", "employeeNumber": "E1", "firstName": "Ada", "lastName": "Lovelace",
                "role": "MANAGER", "permissions": [], "language": "en", "capabilities": ["CATALOG_MANAGE"]
              }
            }
            """.trimIndent()

        val STATION_SESSION_JSON =
            """
            {"accessToken":"station-2","expiresAt":"2030-01-01T00:00:00Z","terminalId":"TER-007",
             "role":"ORDER_STATION","capabilities":["ORDER_READ","ORDER_WRITE"]}
            """.trimIndent()
    }
}
