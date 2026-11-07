package org.example.project.features.issuereport

import org.example.project.features.issuereport.data.service.IssueRateLimiter
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("IssueRateLimiter")
class IssueRateLimiterTest {
    @Test
    fun `allows the limit then refuses with a retry-after`() {
        var now = Instant.parse("2026-10-10T12:00:00Z")
        val limiter = IssueRateLimiter(limit = 5, window = Duration.ofHours(1), clock = { now })
        repeat(5) { assertNull(limiter.tryAcquire()) }
        val retry = limiter.tryAcquire()
        assertTrue(retry != null && retry > 0)
    }

    @Test
    fun `frees a slot when the oldest attempt leaves the window`() {
        var now = Instant.parse("2026-10-10T12:00:00Z")
        val limiter = IssueRateLimiter(limit = 2, window = Duration.ofHours(1), clock = { now })
        assertNull(limiter.tryAcquire())
        assertNull(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire() != null)
        now = now.plus(Duration.ofMinutes(61))
        assertNull(limiter.tryAcquire())
    }

    @Test
    fun `retry-after counts down to the oldest attempt expiring`() {
        var now = Instant.parse("2026-10-10T12:00:00Z")
        val limiter = IssueRateLimiter(limit = 1, window = Duration.ofHours(1), clock = { now })
        assertNull(limiter.tryAcquire())
        now = now.plus(Duration.ofMinutes(10))
        assertEquals(50 * 60L, limiter.tryAcquire())
    }
}
