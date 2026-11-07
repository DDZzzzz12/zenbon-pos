/**
 * HTTP client configuration for shared networking.
 *
 * Provides configurable settings for connection pools, timeouts, retry logic,
 * and user agent identification across all platforms.
 *
 * @since 1.0.0
 */
package org.example.project.core.network

/**
 * Configuration for HTTP client instances.
 *
 * Platform-specific implementations use these settings to configure
 * OkHttp connection pools and Ktor client behavior.
 *
 * @property maxIdleConnections Maximum number of idle connections in the pool
 * @property keepAliveDurationMinutes Duration to keep idle connections alive (minutes)
 * @property connectTimeoutSeconds Connection timeout (seconds)
 * @property readTimeoutSeconds Read timeout (seconds)
 * @property writeTimeoutSeconds Write timeout (seconds)
 * @property maxRetries Maximum number of retry attempts for failed requests
 * @property userAgent User-Agent header value for requests
 * @property enableCompression Whether to request compressed responses
 */
data class HttpClientConfig(
    val maxIdleConnections: Int = 10,
    val keepAliveDurationMinutes: Long = 5,
    val connectTimeoutSeconds: Long = 15,
    val readTimeoutSeconds: Long = 60,
    val writeTimeoutSeconds: Long = 60,
    val maxRetries: Int = 3,
    val userAgent: String = "POS-Client/1.0",
    val enableCompression: Boolean = true,
) {
    companion object {
        /**
         * Default configuration for Android mobile clients.
         *
         * Optimized for mobile networks with:
         * - Shorter keep-alive for battery optimization
         * - Fewer retries to preserve bandwidth
         * - Mobile-appropriate user agent
         */
        val ANDROID =
            HttpClientConfig(
                maxIdleConnections = 10,
                keepAliveDurationMinutes = 5,
                connectTimeoutSeconds = 15,
                readTimeoutSeconds = 60,
                writeTimeoutSeconds = 60,
                maxRetries = 3,
                userAgent = "POS-Android/1.0",
                enableCompression = true,
            )

        /**
         * Default configuration for desktop/JVM clients.
         *
         * Optimized for stable desktop connections with:
         * - Longer keep-alive for persistent connections
         * - More retries for reliability
         * - Desktop-appropriate user agent
         */
        val DESKTOP =
            HttpClientConfig(
                maxIdleConnections = 20,
                keepAliveDurationMinutes = 10,
                connectTimeoutSeconds = 15,
                readTimeoutSeconds = 60,
                writeTimeoutSeconds = 60,
                maxRetries = 5,
                userAgent = "POS-Desktop/1.0",
                enableCompression = true,
            )

        /**
         * Configuration for server-to-server communication.
         *
         * Optimized for internal service calls with:
         * - Larger connection pool for concurrent requests
         * - Longer keep-alive for stable connections
         * - More retries for reliability
         */
        val SERVER =
            HttpClientConfig(
                maxIdleConnections = 30,
                keepAliveDurationMinutes = 15,
                connectTimeoutSeconds = 10,
                readTimeoutSeconds = 120,
                writeTimeoutSeconds = 120,
                maxRetries = 5,
                userAgent = "POS-Server/1.0",
                enableCompression = true,
            )

        /**
         * Configuration for the browser (wasmJs) client.
         *
         * Every field here is inert: the JS/Fetch engine ignores pooling, timeouts, and
         * retries because the browser owns them, and `User-Agent` is a forbidden header that
         * the browser will not let a page set. Present so callers can name their platform
         * instead of borrowing DESKTOP's numbers.
         */
        val WASM =
            HttpClientConfig(
                maxIdleConnections = 10,
                keepAliveDurationMinutes = 5,
                connectTimeoutSeconds = 15,
                readTimeoutSeconds = 60,
                writeTimeoutSeconds = 60,
                maxRetries = 3,
                userAgent = "POS-Admin-Web/1.0",
                enableCompression = true,
            )
    }
}
