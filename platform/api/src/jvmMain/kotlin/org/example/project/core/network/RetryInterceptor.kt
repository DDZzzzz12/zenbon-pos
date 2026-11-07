/**
 * OkHttp retry interceptor for JVM/Desktop platforms.
 *
 * Provides automatic retry with exponential backoff for network failures,
 * handling WiFi drops, server restarts, and temporary network outages.
 *
 * @since 1.0.0
 */
package org.example.project.core.network

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlin.math.pow

/**
 * Retry interceptor for automatic reconnection after network failures.
 *
 * Desktop-specific scenarios handled:
 * - WiFi interference or signal drops
 * - Server restarts during updates
 * - Router/switch reboots
 * - Network cable disconnections
 * - ISP temporary outages
 *
 * Uses exponential backoff to avoid overwhelming the server during outages.
 *
 * @property maxRetries Maximum number of retry attempts (default: 5)
 */
class RetryInterceptor(
    private val maxRetries: Int = 5,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var response: Response? = null
        var lastException: IOException? = null

        // Retry loop with exponential backoff
        for (attempt in 0..maxRetries) {
            try {
                // Close previous failed response
                response?.close()

                // Make the request
                response = chain.proceed(request)

                // Success or final attempt - return the response
                if (response.isSuccessful || attempt == maxRetries) {
                    return response
                }

                // Non-2xx response but not a network error - don't retry
                return response
            } catch (e: IOException) {
                lastException = e

                // Don't retry on final attempt
                if (attempt == maxRetries) {
                    throw e
                }

                // Check if this is a retryable error
                val isRetryable =
                    when (e) {
                        is SocketTimeoutException -> true // Timeout - retry
                        is SocketException -> true // Connection reset - retry
                        else -> e.message?.contains("failed to connect", ignoreCase = true) == true
                    }

                if (!isRetryable) {
                    throw e
                }

                // Exponential backoff: 100ms, 200ms, 400ms, 800ms, 1600ms
                // Faster than mobile since desktop network should recover quicker
                val delayMs = (100.0 * 2.0.pow(attempt.toDouble())).toLong()
                Thread.sleep(delayMs)
            }
        }

        // Should never reach here, but handle it anyway
        throw lastException ?: IOException("Request failed after $maxRetries retries")
    }
}
