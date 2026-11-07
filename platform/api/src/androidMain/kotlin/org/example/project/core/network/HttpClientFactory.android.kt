/**
 * Android platform-specific HTTP client engine factory.
 *
 * Creates an OkHttp-based Ktor engine optimized for mobile environments
 * with battery-conscious keep-alive, mobile network handling, and
 * Doze mode recovery.
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
 * Create an Android-optimized HTTP client engine.
 *
 * Mobile-specific optimizations:
 * - Shorter keep-alive for battery optimization
 * - Fewer retries to preserve bandwidth
 * - Doze mode recovery handling
 * - Mobile network switching support
 *
 * @param config Configuration for the HTTP client
 * @return OkHttp-based HttpClientEngine
 */
actual fun createPlatformEngine(config: HttpClientConfig): HttpClientEngine =
    OkHttp.create {
        this.config {
            // Connection pool optimized for mobile battery
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

            // Enable automatic retry on connection failure (handles Doze mode)
            retryOnConnectionFailure(true)

            // The platform's trust (system roots and user-installed CAs per the network security
            // config) plus the root pinned at pairing, so a till needs no CA installed on the device.
            sslSocketFactory(PinnedTls.socketFactory, PinnedTls.trustManager)

            // Add retry interceptor for mobile network resilience
            addInterceptor(RetryInterceptor(maxRetries = config.maxRetries))

            // Add compression and user-agent headers
            addInterceptor { chain ->
                val request =
                    chain
                        .request()
                        .newBuilder()
                        .addHeader("Accept-Encoding", "gzip, deflate")
                        .addHeader("User-Agent", config.userAgent)
                        .build()
                chain.proceed(request)
            }
        }
    }

/**
 * Create an HTTP client for internal service-to-service communication.
 *
 * Optimized for Android service-to-server communication:
 * - OkHttp engine with connection pooling
 * - JSON content negotiation with kotlinx.serialization
 * - Compression support (gzip, deflate)
 * - Mobile-optimized timeouts
 * - No authentication (internal service communication)
 *
 * Note: On Android, this is typically used for app-to-local-server
 * communication in scenarios where the device runs both client and server.
 *
 * @return HttpClient configured for internal service communication
 */

/**
 * No-op: OkHttp reports transport failures as `java.io.IOException`, which is what
 * `kotlinx.io.IOException` aliases to on Android, so `apiCall` already maps them.
 */
internal actual fun HttpClient.installTransportErrorMapping() = Unit

actual fun createInternalServiceClient(): HttpClient {
    val config = HttpClientConfig.ANDROID

    return HttpClient(OkHttp) {
        engine {
            createPlatformEngine(config)
        }

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
