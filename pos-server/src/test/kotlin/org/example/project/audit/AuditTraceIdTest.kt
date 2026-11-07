package org.example.project.audit

import kotlinx.coroutines.runBlocking
import org.example.project.features.audit.database.tables.TransactionAuditLogTable
import org.example.project.infrastructure.plugins.MAX_TRACE_ID_LENGTH
import org.example.project.infrastructure.plugins.TRACE_ID_MDC_KEY
import org.example.project.testutils.TestDatabase
import org.example.project.utils.errorHandling.ExceptionConverter
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * An audit row must carry the request's trace id, so one failure can be matched across the three
 * places it is recorded: the operator's error dialog, the server log line, and this row.
 *
 * MDC is thread-local and the audit write re-enters through `runBlocking`, so the id is captured at
 * the top of `catchingThrows` on the request thread, never inside the audit transaction.
 */
@DisplayName("Audit rows carry the request trace id")
class AuditTraceIdTest {
    @BeforeEach
    fun setUp() = TestDatabase.setup()

    /**
     * `MDC.remove` is load-bearing, not tidiness. MDC is thread-local and JUnit reuses the thread,
     * so without it [traceIdIsCaptured]'s id leaks into [noTraceIdIsNull] — which then asserts null
     * against a row carrying the other test's id and fails, or worse passes under a reordering.
     * Gradle runs previously-failed tests FIRST, so the order is not stable enough to rely on.
     */
    @AfterEach
    fun tearDown() {
        MDC.remove(TRACE_ID_MDC_KEY)
        TestDatabase.teardown()
    }

    private fun lastRow() =
        transaction {
            TransactionAuditLogTable
                .selectAll()
                .orderBy(TransactionAuditLogTable.id to SortOrder.DESC)
                .limit(1)
                .single()
        }

    @Test
    @DisplayName("the trace id in MDC lands on the row")
    fun traceIdIsCaptured() =
        runBlocking {
            MDC.put(TRACE_ID_MDC_KEY, "c6ef2618-4b61-4d53-b4cc-010560a766dc")
            ExceptionConverter.catchingThrows(
                context = "test",
                employeeId = "tester",
                operation = "CREATE_BILL",
                entityType = "BILL",
                block = { Unit },
            )
            assertEquals("c6ef2618-4b61-4d53-b4cc-010560a766dc", lastRow()[TransactionAuditLogTable.traceId])
        }

    @Test
    @DisplayName("work with no request behind it writes null, never a placeholder")
    fun noTraceIdIsNull() =
        runBlocking {
            ExceptionConverter.catchingThrows(
                context = "test",
                employeeId = "tester",
                operation = "CREATE_BILL",
                entityType = "BILL",
                block = { Unit },
            )
            assertNull(lastRow()[TransactionAuditLogTable.traceId])
        }

    /**
     * The other half of the `TraceIdConfig` length bound.
     *
     * That bound stops anything longer than the column reaching MDC; this pins that everything it
     * DOES admit fits. Without the pair, a widened header or a narrowed column silently resumes
     * losing rows — the audit write is inside a swallow-and-log catch, so an overflow costs the row
     * and reports nothing.
     */
    @Test
    @DisplayName("the longest id the header bound admits still lands on the row intact")
    fun widestAdmissibleTraceIdFits() =
        runBlocking {
            val widest = "z".repeat(MAX_TRACE_ID_LENGTH)
            MDC.put(TRACE_ID_MDC_KEY, widest)
            ExceptionConverter.catchingThrows(
                context = "test",
                employeeId = "tester",
                operation = "CREATE_BILL",
                entityType = "BILL",
                block = { Unit },
            )
            // .single() inside lastRow() is what notices a row lost to an overflow.
            assertEquals(widest, lastRow()[TransactionAuditLogTable.traceId])
        }

    @Test
    @DisplayName("a FAILED operation carries the trace id too — that is the row an operator traces")
    fun traceIdIsCapturedOnTheErrorPath() =
        runBlocking {
            MDC.put(TRACE_ID_MDC_KEY, "8f2b4a10-77c1-4a9e-9d33-51b0a2c7e004")
            runCatching {
                ExceptionConverter.catchingThrows(
                    context = "test",
                    employeeId = "tester",
                    operation = "CREATE_BILL",
                    entityType = "BILL",
                    block = { error("boom") },
                )
            }
            val row = lastRow()
            // Pins that this is the ERROR row and not a success one that happened to be written.
            assertEquals("FAILED", row[TransactionAuditLogTable.entityId])
            assertEquals("8f2b4a10-77c1-4a9e-9d33-51b0a2c7e004", row[TransactionAuditLogTable.traceId])
        }
}
