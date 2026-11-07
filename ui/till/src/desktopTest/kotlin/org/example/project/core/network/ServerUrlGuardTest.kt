package org.example.project.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.example.project.core.device.DeviceTokenProvider
import org.example.project.core.network.error.NetworkApiException
import org.example.project.core.network.error.apiCall
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.error.UiError
import org.example.project.core.utils.ServerUrlVerdict
import org.example.project.testutils.fakes.FakeTokenStore
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The till's transport guard: a plain-HTTP LAN URL is refused before the engine runs, so the device
 * token stamped on every request never reaches the wire. Each client is built the way
 * `NetworkModule` builds it — the shared `createPosHttpClient` plus
 * [installTillClientPlugins] in `extraConfig` — so a passing suite here proves the production
 * install path, not just a guard installed ad hoc for the test.
 */
class ServerUrlGuardTest {
    @BeforeTest
    fun setUp() {
        DeviceTokenProvider.token = "long-lived-device-token"
    }

    @AfterTest
    fun tearDown() {
        DeviceTokenProvider.token = null
    }

    private val noopLogger =
        object : Logger {
            override fun log(message: String) = Unit
        }

    private fun client(
        engine: MockEngine,
        guarded: Boolean = true,
    ): HttpClient =
        createPosHttpClient(
            engine = engine,
            tokenStore = FakeTokenStore.create(),
            onUnauthorized = { },
            baseUrlResolver = { "http://192.168.1.10:8080/" },
            logger = noopLogger,
            logLevel = LogLevel.NONE,
        ) {
            // The exact function NetworkModule installs in production — not a re-declared
            // `install(ServerUrlGuard)` — so this suite exercises the real install path
            // (SSE / ContentEncoding / HttpTimeout included), not a test-only stand-in.
            if (guarded) installTillClientPlugins()
        }

    @Test
    fun `plain http to a LAN host is refused before the engine is called`() =
        runTest {
            val engine = MockEngine { respondOk() }

            val refused =
                assertFailsWith<InsecureServerUrlException> {
                    client(engine).get("http://192.168.1.10:8080/health")
                }

            assertEquals(ServerUrlVerdict.INSECURE_REMOTE, refused.verdict)
            assertEquals(0, engine.requestHistory.size, "the device token must never reach the wire")
        }

    @Test
    fun `https to a LAN host reaches the engine`() =
        runTest {
            val engine = MockEngine { respondOk() }

            client(engine).get("https://kasse.example.de/health")

            assertEquals(listOf("https://kasse.example.de/health"), engine.requestHistory.map { it.url.toString() })
        }

    @Test
    fun `plain http to loopback reaches the engine`() =
        runTest {
            val engine = MockEngine { respondOk() }

            client(engine).get("http://localhost:8080/health")

            assertEquals(listOf("http://localhost:8080/health"), engine.requestHistory.map { it.url.toString() })
        }

    /** Checked per wire attempt, not per call: a redirect to a LAN host over http is refused too. */
    @Test
    fun `a redirect to a plain http LAN host is refused`() =
        runTest {
            val engine =
                MockEngine { request ->
                    if (request.url.host == "localhost") {
                        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "http://192.168.1.10:8080/health"))
                    } else {
                        respondOk()
                    }
                }

            assertFailsWith<InsecureServerUrlException> {
                client(engine).get("http://localhost:8080/health")
            }

            assertEquals(listOf("localhost"), engine.requestHistory.map { it.url.host }, "only the loopback hop was sent")
        }

    @Test
    fun `the refusal reaches a view model as a network error`() =
        runTest {
            val engine = MockEngine { respondOk() }
            val http = client(engine)
            var shown: UiError? = null

            val result =
                runCatchingApi { http.apiCall<Unit> { get("http://192.168.1.10:8080/health") } }
                    .onError { shown = it }

            val failure = assertIs<NetworkApiException>(result.exceptionOrNull())
            assertIs<InsecureServerUrlException>(failure.cause)
            assertIs<UiError.Network>(shown)
            assertEquals(0, engine.requestHistory.size)
        }

    /** The guard is opt-in: adminApp's client (no guard) must keep working over plain http. */
    @Test
    fun `the shared client without the guard still sends plain http`() =
        runTest {
            val engine = MockEngine { respondOk() }

            client(engine, guarded = false).get("http://192.168.1.10:8080/health")

            assertEquals(1, engine.requestHistory.size)
        }

    /**
     * Regression for the untested-install gap: [ServerUrlGuardTest] used to install the guard
     * itself, so a `NetworkModule` edit that dropped `install(ServerUrlGuard)` from production
     * would still leave every test above green. Now both build through
     * [installTillClientPlugins], so this asserts the plugin production actually installs is
     * present on the client — not a stand-in the test declared on its own.
     */
    @Test
    fun `a client built via installTillClientPlugins carries the guard`() =
        runTest {
            val engine = MockEngine { respondOk() }

            assertNotNull(client(engine, guarded = true).pluginOrNull(ServerUrlGuard))
            assertNull(client(engine, guarded = false).pluginOrNull(ServerUrlGuard))
        }
}
