package org.example.project.core.config

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.utils.dbQuery
import org.example.project.utils.formatter.TimeProvider
import org.example.project.utils.formatter.registerTimeProvider
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Timezone configuration service for consistent timestamp handling.
 *
 * Provides centralized timezone management by checking ShopInfo table first,
 * then falling back to system timezone. Used for creating consistent timestamps
 * across orders, bills, and other time-sensitive operations.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */

private val logger = LoggerFactory.getLogger(TimezoneConfig::class.java)

/**
 * Service for managing application timezone configuration.
 *
 * Determines the correct timezone to use by checking ShopInfo configuration
 * first, then falling back to system timezone if no shop configuration exists.
 */
object TimezoneConfig {
    private var cachedTimeZone: TimeZone? = null

    /**
     * Get the configured timezone for the application.
     *
     * Priority order:
     * 1. ShopInfo.shopTimezone (if exists and valid)
     * 2. System default timezone
     *
     * @return TimeZone to use for application timestamps
     */
    fun getApplicationTimeZone(): TimeZone {
        // Return cached timezone if available
        cachedTimeZone?.let { return it }

        return runBlocking { timeZone() }
    }

    private suspend fun timeZone(): TimeZone =
        dbQuery {
            // Try to get timezone from ShopInfo table
            val shopTimezone =
                try {
                    ShopInfo.Companion
                        .all()
                        .firstOrNull()
                        ?.shopTimezone
                } catch (e: ExposedSQLException) {
                    // 1. Catch the specific exception type thrown by Exposed/PostgreSQL or H2
                    val message = e.cause?.message ?: ""
                    if (message.contains("relation \"shop_info\" does not exist") ||
                        message.contains("Table \"SHOP_INFO\" not found")
                    ) {
                        // 2. Log a warning instead of an error, as this is expected during initial setup
                        logger.warn("ShopInfo table does not exist. Returning null for timezone during initial startup.")
                        // 3. Return null when the table is missing
                        null
                    } else {
                        // 4. Re-throw other, unexpected database errors (e.g., connection failure)
                        logger.error("Unexpected database error while fetching timezone.", e)
                        throw e
                    }
                } catch (e: Exception) {
                    // Catch any other unexpected exceptions (non-database errors)
                    logger.error("Unexpected error in getTimezoneFromDb.", e)
                    null
                }
            val timeZone =
                if (!shopTimezone.isNullOrBlank()) {
                    try {
                        TimeZone.of(shopTimezone)
                    } catch (e: Exception) {
                        // If shop timezone is invalid, log and fall back to system
                        println("Invalid shop timezone '$shopTimezone', using system timezone")
                        TimeZone.currentSystemDefault()
                    }
                } else {
                    // No shop timezone configured, use system default
                    TimeZone.currentSystemDefault()
                }

            // Cache the timezone for future use
            cachedTimeZone = timeZone
            timeZone
        }

    /**
     * Get current timestamp in the application's configured timezone.
     *
     * @return LocalDateTime in the application timezone
     */
    @OptIn(ExperimentalTime::class)
    fun now(): LocalDateTime {
        val timeZone = getApplicationTimeZone()
        return Clock.System.now().toLocalDateTime(timeZone)
    }

    /**
     * Clear the cached timezone (useful when ShopInfo is updated).
     *
     * Call this when shop timezone configuration changes to force
     * re-reading from the database.
     */
    fun clearCache() {
        cachedTimeZone = null
    }

    /**
     * Registers this TimezoneConfig as the application's [TimeProvider].
     *
     * Called once during server startup so that platform/server-common's
     * [currentTimeStamp] and [getTimeZone] use shop-configured timezone.
     */
    fun registerAsTimeProvider() {
        registerTimeProvider(
            object : TimeProvider {
                override fun now(): LocalDateTime = this@TimezoneConfig.now()

                override fun getTimeZone(): TimeZone = getApplicationTimeZone()
            },
        )
    }
}
