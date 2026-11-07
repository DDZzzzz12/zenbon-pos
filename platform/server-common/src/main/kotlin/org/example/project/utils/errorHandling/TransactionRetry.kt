/**
 * Transaction retry utilities for handling serialization failures and deadlocks.
 *
 * Provides automatic retry logic for database operations that fail due to
 * concurrent transaction conflicts (SERIALIZABLE isolation) or deadlocks.
 * Does NOT retry business logic errors or unique constraint violations.
 *
 * Throws-style only — the legacy Result-based `execute`/`withRetry` was
 * removed in Stage C cleanup; use [withRetryThrows] / [executeThrowing].
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.utils.errorHandling

import kotlinx.coroutines.delay
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.postgresql.util.PSQLException
import org.slf4j.LoggerFactory
import java.sql.SQLException
import kotlin.math.pow

/**
 * Configuration for transaction retry behavior.
 */
data class RetryConfig(
    val maxAttempts: Int = 3,
    val initialDelayMs: Long = 50L,
    val maxDelayMs: Long = 1000L,
    val backoffMultiplier: Double = 2.0,
) {
    companion object {
        val DEFAULT = RetryConfig()

        val AGGRESSIVE =
            RetryConfig(
                maxAttempts = 5,
                initialDelayMs = 25L,
                maxDelayMs = 500L,
            )

        val CONSERVATIVE =
            RetryConfig(
                maxAttempts = 2,
                initialDelayMs = 100L,
                maxDelayMs = 500L,
            )
    }
}

/**
 * Retry handler for database transactions.
 *
 * Automatically retries operations that fail due to serialization conflicts
 * or deadlocks (PostgreSQL SQLSTATE 40001, 40P01), with exponential backoff.
 * Does not retry unique-constraint, foreign-key, or check-constraint violations.
 */
object TransactionRetry {
    private val logger = LoggerFactory.getLogger(TransactionRetry::class.java)

    /**
     * Execute a database operation with automatic retry on retryable database
     * exceptions. Non-retryable exceptions (including [org.example.project.
     * infrastructure.errors.PosException]) propagate immediately.
     */
    suspend fun <T> executeThrowing(
        config: RetryConfig = RetryConfig.DEFAULT,
        operationName: String = "database operation",
        block: suspend () -> T,
    ): T {
        var attempt = 0
        var lastException: Throwable? = null

        while (attempt < config.maxAttempts) {
            attempt++
            try {
                val value = block()
                if (attempt > 1) {
                    logger.info("$operationName succeeded on attempt $attempt")
                }
                return value
            } catch (e: Exception) {
                if (!isRetryableException(e)) {
                    throw e
                }
                // INFO, not WARN: a conflict that the next attempt resolves is ordinary contention
                // (real-TSE QA rt14). Exhausting the attempts is still logged at ERROR below.
                logger.info("$operationName hit a retryable conflict on attempt $attempt: ${e.message}")
                lastException = e
            }

            if (attempt >= config.maxAttempts) {
                logger.error("$operationName failed after $attempt attempts")
                break
            }

            val delay = calculateDelay(attempt, config)
            logger.debug("Retrying $operationName in ${delay}ms (attempt ${attempt + 1}/${config.maxAttempts})")
            delay(delay)
        }

        throw lastException ?: RuntimeException("$operationName failed after retries")
    }

    /**
     * Retryable exceptions:
     * - PostgreSQL serialization failures (SQLSTATE 40001)
     * - Deadlock detection (SQLSTATE 40P01)
     */
    private fun isRetryableException(exception: Throwable): Boolean {
        val cause =
            when (exception) {
                is ExposedSQLException -> exception.cause
                else -> exception
            }

        if (cause is PSQLException) {
            val sqlState = cause.sqlState
            return when (sqlState) {
                "40001" -> true // Serialization failure
                "40P01" -> true // Deadlock detected
                "23505" -> {
                    logger.error("Unique constraint violation detected - THIS INDICATES A BUG: ${cause.message}")
                    false
                }
                "23503" -> false // Foreign key violation
                "23514" -> false // Check constraint violation
                else -> false
            }
        }

        if (cause is SQLException) {
            val message = cause.message?.lowercase() ?: ""
            return when {
                "could not serialize" in message -> true
                "deadlock" in message -> true
                // H2's serialization failure (used by the test engine) surfaces as
                // "Concurrent update in table ...": a lost write-write race that is safe
                // to retry, same as Postgres SQLSTATE 40001.
                "concurrent update" in message -> true
                else -> false
            }
        }

        return false
    }

    /**
     * Exponential backoff with 10% jitter, capped at [RetryConfig.maxDelayMs].
     */
    private fun calculateDelay(
        attempt: Int,
        config: RetryConfig,
    ): Long {
        val exponentialDelay = config.initialDelayMs * config.backoffMultiplier.pow((attempt - 1).toDouble())
        val jitter = (Math.random() * config.initialDelayMs * 0.1).toLong()
        return minOf((exponentialDelay + jitter).toLong(), config.maxDelayMs)
    }
}

/**
 * Retries on retryable database exceptions (PostgreSQL serialization failures,
 * deadlocks). Non-retryable exceptions (including [org.example.project.
 * infrastructure.errors.PosException]) propagate immediately.
 */
suspend fun <T> withRetryThrows(
    operationName: String,
    config: RetryConfig = RetryConfig.DEFAULT,
    block: suspend () -> T,
): T = TransactionRetry.executeThrowing(config, operationName, block)
