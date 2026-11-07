/**
 * Platform-specific HTTP client engine factory.
 *
 * Provides expect declarations for creating platform-optimized
 * HTTP client engines with OkHttp on JVM/Android.
 *
 * @since 1.0.0
 */
package org.example.project.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine

/**
 * Create a platform-specific HTTP client engine.
 *
 * Each platform implements this function to provide an optimized
 * HTTP client engine configured with:
 * - Connection pooling
 * - Retry interceptors
 * - Compression support
 * - Platform-appropriate timeouts
 *
 * @param config Configuration for the HTTP client
 * @return Platform-specific HttpClientEngine instance
 */
expect fun createPlatformEngine(config: HttpClientConfig): HttpClientEngine

/**
 * Create an HTTP client for internal service-to-service communication.
 *
 * This client is optimized for internal communication (e.g., TSE server
 * to main server) with:
 * - No authentication required
 * - JSON content negotiation
 * - Compression support
 * - Server-optimized timeouts
 *
 * Each platform implements this function to provide an optimized
 * HTTP client using the platform's preferred engine.
 *
 * @return HttpClient configured for internal service communication
 */
expect fun createInternalServiceClient(): HttpClient

/**
 * Install engine-level transport-failure mapping on this client.
 *
 * `apiCall` turns a [kotlinx.io.IOException] into `NetworkApiException` (and from there
 * `UiError.Network`). The JVM/Android OkHttp engines already throw one, so their actuals are
 * no-ops. The wasm/JS engine instead rejects a failed `fetch` with `kotlin.Error("Fail to
 * fetch")` — the `Error` branch of the hierarchy, which neither `apiCall` nor `runCatchingApi`
 * matches, so an unreachable server killed the calling coroutine instead of surfacing an
 * offline state.
 *
 * Called once by [createPosHttpClient]; it runs after construction because `HttpSend`
 * interceptors are registered on the built client rather than in its config block.
 */
internal expect fun HttpClient.installTransportErrorMapping()
