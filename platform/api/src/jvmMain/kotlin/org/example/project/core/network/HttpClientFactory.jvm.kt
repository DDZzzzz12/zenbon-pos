/**
 * JVM/Desktop platform-specific HTTP client engine factory.
 *
 * Creates an OkHttp-based Ktor engine optimized for desktop environments
 * with longer keep-alive, more retries, and stable connection handling.
 *
 * @since 1.0.0
 */
package org.example.project.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import java.util.concurrent.TimeUnit

/**
 * Create a JVM/Desktop-optimized HTTP client engine.
 *
 * Desktop-specific optimizations:
 * - Larger connection pool for concurrent requests
 * - Longer keep-alive for stable desktop connections
 * - More retry attempts for reliability
 * - Brotli compression support
 *
 * @param config Configuration for the HTTP client
 * @return OkHttp-based HttpClientEngine
 */
actual fun createPlatformEngine(config: HttpClientConfig): HttpClientEngine =
    OkHttp.create {
        this.config {
            // Connection pool optimized for desktop stability
            connectionPool(
                ConnectionPool(
                    maxIdleConnections = config.maxIdleConnections,
                    keepAliveDuration = config.keepAliveDurationMinutes,
                    timeUnit = TimeUnit.MINUTES,
                ),
            )

            // Timeouts from configuration
            connectTimeout(config.connectTimeoutSeconds, TimeUnit.SECONDS)
            readTimeout(config.readTimeoutSeconds, TimeUnit.SECONDS)
            writeTimeout(config.writeTimeoutSeconds, TimeUnit.SECONDS)

            // Enable automatic retry on connection failure
            retryOnConnectionFailure(true)

            // Trust the OS certificate store as well as the bundled runtime's: a shop without a
            // domain runs Caddy's local CA, whose root the operator installs into the OS.
            sslSocketFactory(OsTrustStore.socketFactory, OsTrustStore.trustManager)

            // Add retry interceptor for network resilience
            addInterceptor(RetryInterceptor(maxRetries = config.maxRetries))

            // Add compression and user-agent headers
            addInterceptor { chain ->
                val request =
                    chain
                        .request()
                        .newBuilder()
                        .addHeader("Accept-Encoding", "gzip, deflate, br")
                        .addHeader("User-Agent", config.userAgent)
                        .build()
                chain.proceed(request)
            }
        }
    }

/**
 * Create an HTTP client for internal service-to-service communication.
 *
 * Optimized for JVM server-to-server communication:
 * - OkHttp engine with connection pooling
 * - JSON content negotiation with kotlinx.serialization
 * - Compression support (gzip, deflate, brotli)
 * - Server-optimized timeouts
 * - No authentication (internal service communication)
 *
 * @return HttpClient configured for internal service communication
 */

/**
 * No-op: OkHttp reports transport failures as `java.io.IOException`, which is what
 * `kotlinx.io.IOException` aliases to on the JVM, so `apiCall` already maps them.
 */
internal actual fun HttpClient.installTransportErrorMapping() = Unit

actual fun createInternalServiceClient(): HttpClient {
    val config = HttpClientConfig.SERVER

    return HttpClient(OkHttp) {
        engine { createPlatformEngine(config) }

        // JSON content negotiation
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    encodeDefaults = true
                    prettyPrint = false
                },
            )
        }

        // Compression support
        install(ContentEncoding) {
            gzip()
            deflate()
            identity()
        }

        // Timeouts
        install(HttpTimeout) {
            requestTimeoutMillis = config.readTimeoutSeconds * 1000L
            connectTimeoutMillis = config.connectTimeoutSeconds * 1000L
            socketTimeoutMillis = config.readTimeoutSeconds * 1000L
        }

        // Don't throw exceptions on non-2xx responses
        expectSuccess = false
    }
}
