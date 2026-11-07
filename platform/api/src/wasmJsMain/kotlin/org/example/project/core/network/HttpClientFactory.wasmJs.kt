/**
 * WASM/JS platform-specific HTTP client engine factory.
 *
 * Creates a JS-based Ktor engine for browser environments.
 * Connection pooling and retry logic are handled by the browser.
 *
 * @since 1.0.0
 */
package org.example.project.core.network

import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.engine.js.*
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.plugin
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.serialization.json.Json

/**
 * Create a WASM/JS HTTP client engine.
 *
 * Browser-specific behavior:
 * - Connection pooling managed by the browser
 * - Config parameters (timeouts, pool size) are ignored
 * - Uses the Fetch API under the hood
 *
 * @param config Configuration for the HTTP client (mostly ignored in browser)
 * @return JS-based HttpClientEngine
 */
actual fun createPlatformEngine(config: HttpClientConfig): HttpClientEngine = Js.create {}

/**
 * Wrap engine failures as [IOException] so the shared error layer recognises them.
 *
 * The browser rejects `fetch` with `kotlin.Error("Fail to fetch")` for DNS failures, CORS
 * rejections, connection refused and offline alike. `Error` sits on the non-`Exception` branch
 * of the hierarchy, so `apiCall`'s `IOException` catch and `runCatchingApi` both miss it and the
 * failure escapes to the caller's coroutine scope as an uncaught crash.
 *
 * The catch-all is deliberate, and safe only at this depth: the interceptor wraps nothing but
 * the engine send, so a throwable crossing this boundary is a transport failure by construction.
 * [CancellationException] is rethrown first — on wasm it descends from `IllegalStateException`,
 * and a request cancelled by navigation must not be reported as a network error.
 */
internal actual fun HttpClient.installTransportErrorMapping() {
    plugin(HttpSend).intercept { request ->
        try {
            execute(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw IOException("Network request failed: ${e.message}", e)
        }
    }
}

/**
 * Create an HTTP client for internal service communication.
 *
 * In the browser context, this is used for admin API calls
 * to the POS server. Uses the JS/Fetch engine with JSON
 * content negotiation.
 *
 * @return HttpClient configured with JS engine and JSON support
 */
actual fun createInternalServiceClient(): HttpClient =
    HttpClient(Js) {
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    encodeDefaults = true
                },
            )
        }
    }
