package org.example.project.audit

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimePeriod
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.domain.enums.setup.LogOperation
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.features.audit.data.repository.EntryLogRepositoryImpl
import org.example.project.features.audit.data.service.EntryLogRetentionJob
import org.example.project.features.audit.database.tables.EntryLogTable
import org.example.project.testutils.TestDatabase
import org.example.project.utils.dbQuery
import org.jetbrains.exposed.v1.jdbc.insert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Clock

/**
 * TDD test for [EntryLogRetentionJob.pruneOlderThanDays].
 *
 * Seeds two rows — one ~100 days old, one ~10 days old — then calls the job
 * and asserts that only the old row is deleted and the recent one survives.
 *
 * Ages are expressed as offsets from the clock the job itself reads, never as
 * absolute calendar dates: the job prunes relative to *now*, so a fixed date
 * only stays on the intended side of the cutoff until the window slides past
 * it. Offsets state the relationship the assertions actually depend on.
 */
class EntryLogRetentionTest {
    private val repository = EntryLogRepositoryImpl()
    private val retentionJob = EntryLogRetentionJob(repository)

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun tearDown() = TestDatabase.teardown()

    /**
     * A timestamp [days] before now, using the same clock, time zone and
     * calendar arithmetic as [EntryLogRetentionJob.pruneOlderThanDays], so a
     * row seeded at `daysAgo(n)` falls on the expected side of a retention
     * cutoff of `n ± 1` days regardless of when the suite runs.
     */
    private fun daysAgo(days: Int): LocalDateTime {
        val tz = TimeZone.currentSystemDefault()
        return Clock.System
            .now()
            .minus(DateTimePeriod(days = days), tz)
            .toLocalDateTime(tz)
    }

    private suspend fun insertWithCreatedAt(
        type: ChangeEntityType,
        operation: LogOperation,
        entityId: String,
        createdAt: LocalDateTime,
    ) = dbQuery {
        EntryLogTable.insert {
            it[EntryLogTable.type] = type
            it[EntryLogTable.operation] = operation
            it[EntryLogTable.entityId] = entityId
            it[EntryLogTable.entityName] = "$type-$entityId"
            it[EntryLogTable.createdAt] = createdAt
        }
    }

    @Test
    fun `pruneOlderThanDays deletes old rows and keeps recent rows`() =
        runBlocking {
            // Seed one row 100 days ago (older than 90-day retention).
            insertWithCreatedAt(
                type = ChangeEntityType.PRODUCT,
                operation = LogOperation.CREATE,
                entityId = "old-1",
                createdAt = daysAgo(100),
            )
            // Seed one row 10 days ago (within retention window).
            insertWithCreatedAt(
                type = ChangeEntityType.PRODUCT,
                operation = LogOperation.UPDATE,
                entityId = "recent-1",
                createdAt = daysAgo(10),
            )

            val deleted = retentionJob.pruneOlderThanDays(90)

            assertEquals(1, deleted, "exactly one old row should be deleted")

            val remaining = dbQuery { repository.getChangesSince(0, 100) }
            assertEquals(1, remaining.size, "exactly one recent row should remain")
            assertEquals("recent-1", remaining.first().entityId, "the recent row must survive")
        }

    @Test
    fun `pruneOlderThanDays returns zero when there are no old rows`() =
        runBlocking {
            // Only insert a recent row.
            insertWithCreatedAt(
                type = ChangeEntityType.PRODUCT,
                operation = LogOperation.CREATE,
                entityId = "new-1",
                createdAt = daysAgo(3),
            )

            val deleted = retentionJob.pruneOlderThanDays(90)

            assertEquals(0, deleted, "no rows should be deleted when all rows are recent")
            val remaining = dbQuery { repository.getChangesSince(0, 100) }
            assertEquals(1, remaining.size, "the recent row must survive")
        }
}
