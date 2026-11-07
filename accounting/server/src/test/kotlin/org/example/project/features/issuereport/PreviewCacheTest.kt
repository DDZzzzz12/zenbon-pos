package org.example.project.features.issuereport

import org.example.project.features.issuereport.data.service.PreviewCache
import org.example.project.features.issuereport.domain.models.ReportSnapshot
import org.example.project.features.issuereport.domain.models.ReportTarget
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("PreviewCache")
class PreviewCacheTest {
    private var now = Instant.parse("2026-10-11T12:00:00Z")
    private val target = ReportTarget("DDZzzzz12", "POS-KMP", "https://api.github.com", isPublic = false)

    private fun snapshot(
        employeeId: String = "7",
        body: String = "body",
    ) = ReportSnapshot(
        employeeId = employeeId,
        title = "T",
        body = body,
        target = target,
        hasLogExcerpt = false,
        createdAt = now,
    )

    private fun cache(maxEntries: Int = PreviewCache.DEFAULT_MAX_ENTRIES) = PreviewCache(maxEntries = maxEntries, clock = { now })

    @Test
    fun `a stored snapshot is returned once to the employee who created it`() {
        val cache = cache()
        val id = cache.put(snapshot())
        assertEquals("body", cache.take(id, "7")?.body)
        assertNull(cache.take(id, "7"), "a preview is single use")
    }

    @Test
    fun `another employee cannot take the snapshot and does not burn it`() {
        val cache = cache()
        val id = cache.put(snapshot(employeeId = "7"))
        assertNull(cache.take(id, "8"))
        assertNotNull(cache.take(id, "7"), "a failed foreign lookup must not consume the owner's preview")
    }

    @Test
    fun `an unknown id is null`() {
        assertNull(cache().take("does-not-exist", "7"))
    }

    @Test
    fun `a snapshot expires after fifteen minutes`() {
        val cache = cache()
        val fresh = cache.put(snapshot())
        val stale = cache.put(snapshot())
        now = now.plus(Duration.ofMinutes(15)).minusSeconds(1)
        assertNotNull(cache.take(fresh, "7"))
        now = now.plusSeconds(1)
        assertNull(cache.take(stale, "7"))
    }

    @Test
    fun `restore puts a taken snapshot back with its original age`() {
        val cache = cache()
        val id = cache.put(snapshot())
        val taken = assertNotNull(cache.take(id, "7"))
        now = now.plus(Duration.ofMinutes(10))
        cache.restore(id, taken)
        assertNotNull(cache.take(id, "7"))
        cache.restore(id, taken)
        now = now.plus(Duration.ofMinutes(5))
        assertNull(cache.take(id, "7"), "restoring must not extend the time to live")
    }

    @Test
    fun `the cache holds at most maxEntries and evicts the oldest`() {
        val cache = cache(maxEntries = 20)
        val ids = (1..21).map { cache.put(snapshot(body = "b$it")) }
        assertEquals(20, cache.size())
        assertNull(cache.take(ids.first(), "7"), "the oldest entry is evicted")
        assertEquals("b21", cache.take(ids.last(), "7")?.body)
    }

    @Test
    fun `ids are 128-bit random and unique`() {
        val cache = cache()
        val ids = (1..50).map { cache.put(snapshot()) }.toSet()
        assertEquals(50, ids.size)
        for (id in ids) {
            assertTrue(Regex("^[0-9a-f]{32}$").matches(id), id)
        }
        assertNotEquals(ids.first(), ids.last())
    }
}
