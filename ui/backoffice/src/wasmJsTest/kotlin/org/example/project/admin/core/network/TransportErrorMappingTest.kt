package org.example.project.admin.core.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.request.get
import kotlinx.coroutines.test.runTest
import org.example.project.admin.core.data.LocalStorageKeyValueStore
import org.example.project.core.auth.TokenStore
import org.example.project.core.network.createPosHttpClient
import org.example.project.core.network.error.NetworkApiException
import org.example.project.core.network.error.apiCall
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.error.UiError
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A failed browser `fetch` reaches Ktor as `kotlin.Error("Fail to fetch")`, which is not an
 * `Exception` — before the mapping installed by `createPosHttpClient` it slipped past both
 * `apiCall` and `runCatchingApi` and killed the calling coroutine instead of surfacing an
 * offline state. These tests fail the engine the same way and assert the app-facing result.
 */
class TransportErrorMappingTest {
    private val silentLogger =
        object : Logger {
            override fun log(message: String) = Unit
        }

    private fun clientThatFailsToFetch() =
        createPosHttpClient(
            engine = MockEngine { throw Error("Fail to fetch") },
            tokenStore = TokenStore(LocalStorageKeyValueStore()),
            onUnauthorized = {},
            baseUrlResolver = { "/" },
            logger = silentLogger,
            logLevel = LogLevel.NONE,
        )

    @Test
    fun a_failed_fetch_surfaces_as_NetworkApiException() =
        runTest {
            val client = clientThatFailsToFetch()

            assertFailsWith<NetworkApiException> {
                client.apiCall<Unit> { get("/api/setup/status") }
            }
        }

    @Test
    fun a_failed_fetch_maps_to_UiError_Network() =
        runTest {
            val client = clientThatFailsToFetch()
            var seen: UiError? = null

            runCatchingApi { client.apiCall<Unit> { get("/api/setup/status") } }
                .onError { seen = it }

            assertTrue(seen is UiError.Network, "expected UiError.Network, got $seen")
        }
}
