package org.example.project.audit

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.setup.LogOperation
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.features.audit.data.repository.EntryLogRepositoryImpl
import org.example.project.features.audit.database.tables.EntryLogTable
import org.example.project.testutils.TestDatabase
import org.example.project.utils.dbQuery
import org.jetbrains.exposed.v1.jdbc.insert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Regression guard for the clock-skew root bug.
 *
 * The old timestamp-based sync path filtered `WHERE created_at > :lastSyncTime`.
 * A client whose clock ran ahead of the server wrote a cursor that was "in the future"
 * from the server's perspective, causing rows stamped during the skew window to be
 * permanently excluded — a silent data loss.
 *
 * The new path selects by [EntryLogTable.id] (autoincrement sequence), not by
 * `created_at`. This test proves that a row whose `created_at` is ~1 day in the
 * past is still delivered by [EntryLogRepositoryImpl.getChangesSince], because the
 * filter is `id > sinceSeq`, not `created_at > cursor`.
 */
@DisplayName("Clock-skew regression: getChangesSince selects by id, not by timestamp")
class ClockSkewRegressionTest {
    private val repository = EntryLogRepositoryImpl()

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun tearDown() = TestDatabase.teardown()

    /**
     * Insert a row with an explicit (past) [createdAt] and return its generated id.
     * Uses a raw table insert (same pattern as [EntryLogRetentionTest]) so we can
     * set `created_at` to an arbitrary timestamp, simulating clock skew.
     */
    private suspend fun insertWithPastTimestamp(
        type: ChangeEntityType,
        operation: LogOperation,
        entityId: String,
        createdAt: LocalDateTime,
    ): Long =
        dbQuery {
            val result =
                EntryLogTable.insert {
                    it[EntryLogTable.type] = type
                    it[EntryLogTable.operation] = operation
                    it[EntryLogTable.entityId] = entityId
                    it[EntryLogTable.entityName] = "$type-$entityId"
                    it[EntryLogTable.createdAt] = createdAt
                }
            result[EntryLogTable.id].value
        }

    @Test
    fun `row with created_at in the past is still returned by getChangesSince`() =
        runBlocking {
            // Simulate a row whose created_at is 1 day in the past (clock-skew scenario).
            // Under the OLD timestamp-based query this row would have been skipped if the
            // client's lastSyncCursor was >= yesterday — because the server would filter
            // `created_at > cursor` and this row would appear "already seen" or "before cursor".
            val pastTimestamp = LocalDateTime(2026, 6, 22, 0, 0, 0) // 1 day before test date 2026-06-23
            val insertedId =
                insertWithPastTimestamp(
                    type = ChangeEntityType.PRODUCT,
                    operation = LogOperation.UPDATE,
                    entityId = "42",
                    createdAt = pastTimestamp,
                )

            // Call getChangesSince with sinceSeq = insertedId - 1 (so the row is in range).
            val changes = dbQuery { repository.getChangesSince(sinceSeq = insertedId - 1, limit = 10) }

            // The row MUST be returned — selection is by id (sequence), not by created_at.
            assertEquals(1, changes.size, "exactly one row should be returned")
            val row = changes.first()
            assertEquals("42", row.entityId, "the expected entity id must match")
            assertEquals(ChangeEntityType.PRODUCT, row.type)
            assertEquals(LogOperation.UPDATE, row.operation)
            assertTrue(row.id.value >= insertedId, "returned row id must be >= inserted id")

            // Sanity: a sinceSeq equal to the row's id excludes it (exclusive boundary).
            val empty = dbQuery { repository.getChangesSince(sinceSeq = insertedId, limit = 10) }
            assertEquals(0, empty.size, "sinceSeq equal to insertedId must exclude the row (exclusive)")
        }
}
