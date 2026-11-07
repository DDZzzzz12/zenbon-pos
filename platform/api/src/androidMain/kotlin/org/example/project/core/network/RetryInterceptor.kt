/**
 * OkHttp retry interceptor for Android platforms.
 *
 * Provides automatic retry with exponential backoff for network failures,
 * handling Doze mode recovery, network switching, and mobile connectivity issues.
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
 * Mobile-specific scenarios handled:
 * - Android Doze mode (screen off standby)
 * - Airplane mode toggle
 * - Network switching (WiFi to Mobile data)
 * - Temporary network outages
 * - Cell tower handoffs
 *
 * Uses exponential backoff with longer delays for mobile networks.
 *
 * @property maxRetries Maximum number of retry attempts (default: 3)
 */
class RetryInterceptor(
    private val maxRetries: Int = 3,
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

                // Exponential backoff: 100ms, 300ms, 900ms
                // Longer delays for mobile networks
                val delayMs = (100.0 * 3.0.pow(attempt.toDouble())).toLong()
                Thread.sleep(delayMs)
            }
        }

        // Should never reach here, but handle it anyway
        throw lastException ?: IOException("Request failed after $maxRetries retries")
    }
}
