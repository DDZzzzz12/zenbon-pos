/**
 * Time utility functions for consistent timestamp handling across the application.
 *
 * Provides convenient functions for creating timestamps using the configured
 * application timezone. The timezone provider must be registered during
 * server startup via [registerTimeProvider].
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.utils.formatter

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Functional interface for providing application-configured timestamps and timezone.
 */
interface TimeProvider {
    fun now(): LocalDateTime

    fun getTimeZone(): TimeZone

    companion object {
        /**
         * Default provider using system timezone. Replaced during server startup
         * with a provider that reads from ShopInfo configuration.
         */
        val SYSTEM_DEFAULT: TimeProvider =
            object : TimeProvider {
                @OptIn(ExperimentalTime::class)
                override fun now(): LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

                override fun getTimeZone(): TimeZone = TimeZone.currentSystemDefault()
            }
    }
}

private var timeProvider: TimeProvider = TimeProvider.SYSTEM_DEFAULT

/**
 * Registers a custom time provider (typically backed by TimezoneConfig).
 *
 * Called once during server startup after database and TimezoneConfig are initialized.
 */
fun registerTimeProvider(provider: TimeProvider) {
    timeProvider = provider
}

/**
 * Get current timestamp using application's configured timezone.
 *
 * This is the preferred way to get timestamps for database operations
 * like creating bills, orders, cancellations, etc.
 *
 * @return Current timestamp in application timezone
 */
fun currentTimeStamp(): LocalDateTime = timeProvider.now()

@OptIn(ExperimentalTime::class)
fun currentInstant(): Instant = Clock.System.now()

fun getTimeZone(): TimeZone = timeProvider.getTimeZone()

fun currentDate(): LocalDate = currentTimeStamp().date
