package org.example.project.features.maintenance

import io.ktor.http.parametersOf
import org.example.project.core.error.PosErrorCode
import org.example.project.features.maintenance.data.logs.LogLevel
import org.example.project.features.maintenance.data.logs.LogQuery
import org.example.project.features.maintenance.data.logs.LogSourceKind
import org.example.project.infrastructure.errors.PosException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LogQueryTest {
    private val now = Instant.parse("2026-10-09T12:00:00Z")

    private fun parse(vararg pairs: Pair<String, String>) =
        LogQuery.fromParameters(parametersOf(*pairs.groupBy({ it.first }, { it.second }).map { (k, v) -> k to v }.toTypedArray()), now)

    @Test
    fun `no parameters means the last 24 hours, every source, limit 100`() {
        val query = parse()
        assertEquals(now.minusSeconds(24 * 3600), query.from)
        assertNull(query.to)
        assertEquals(LogSourceKind.entries.toSet(), query.sources)
        assertEquals(100, query.limit)
        assertEquals(LogLevel.TRACE, query.minLevel)
    }

    @Test
    fun `a trace id removes the default window - the whole retention is searched`() {
        val query = parse("traceId" to "abc-123")
        assertEquals("abc-123", query.traceId)
        assertNull(query.from)
    }

    @Test
    fun `an explicit from wins over the default`() {
        assertEquals(Instant.parse("2026-10-01T00:00:00Z"), parse("from" to "2026-10-01T00:00:00Z").from)
    }

    @Test
    fun `source can repeat or be comma separated`() {
        assertEquals(setOf(LogSourceKind.ERROR, LogSourceKind.REJECTED), parse("source" to "error", "source" to "rejected").sources)
        assertEquals(setOf(LogSourceKind.ERROR, LogSourceKind.CLIENT_ERROR), parse("source" to "error,client-error").sources)
    }

    @Test
    fun `bad input is a 400 VALIDATION_FAILED`() {
        listOf(
            "level" to "LOUD",
            "source" to "nope",
            "status" to "99",
            "status" to "abc",
            "code" to "lower_case",
            "limit" to "0",
            "limit" to "201",
            "from" to "yesterday",
            "traceId" to "x".repeat(65),
            "q" to "x".repeat(201),
        ).forEach { (name, value) ->
            val failure = assertFailsWith<PosException>("$name=$value") { parse(name to value) }
            assertEquals(PosErrorCode.VALIDATION_FAILED, failure.code, "$name=$value")
        }
    }

    @Test
    fun `from after to is rejected`() {
        assertFailsWith<PosException> { parse("from" to "2026-10-09T10:00:00Z", "to" to "2026-10-09T09:00:00Z") }
    }

    @Test
    fun `activeFilterNames lists what was asked, never the values`() {
        assertEquals(listOf("traceId", "level"), parse("traceId" to "abc", "level" to "ERROR").activeFilterNames())
    }
}
