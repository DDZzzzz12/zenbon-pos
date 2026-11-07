package org.example.project.features.issuereport.data.service

import java.time.Duration
import java.time.Instant

/**
 * At most [limit] reports per [window], in memory (resets on restart — an abuse guard, not an
 * audit control). [tryAcquire] returns null when a slot was taken, or the seconds until the oldest
 * attempt leaves the window.
 *
 * The caller acquires immediately before contacting GitHub and never gives a slot back: a timeout or
 * an unreadable answer may still have created the issue, so every attempt that left the server counts.
 */
class IssueRateLimiter(
    private val limit: Int = DEFAULT_LIMIT,
    private val window: Duration = Duration.ofHours(1),
    private val clock: () -> Instant = { Instant.now() },
) {
    private val attempts = ArrayDeque<Instant>()

    @Synchronized
    fun tryAcquire(): Long? {
        val now = clock()
        while (attempts.isNotEmpty() && !attempts.first().plus(window).isAfter(now)) attempts.removeFirst()
        if (attempts.size >= limit) {
            return Duration.between(now, attempts.first().plus(window)).seconds.coerceAtLeast(1)
        }
        attempts.addLast(now)
        return null
    }

    companion object {
        const val DEFAULT_LIMIT = 5
    }
}
