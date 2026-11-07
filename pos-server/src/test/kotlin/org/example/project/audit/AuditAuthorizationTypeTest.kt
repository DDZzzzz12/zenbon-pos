package org.example.project.audit

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.features.audit.database.tables.TransactionAuditLogTable
import org.example.project.features.auth.data.TemporarySessionStore
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.utils.dbQuery
import org.example.project.utils.errorHandling.ExceptionConverter
import org.example.project.utils.formatter.currentInstant
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * `transaction_audit_log` must name the manager who lent the authority.
 *
 * Every audited fiscal action is written by [ExceptionConverter], which used to hardcode
 * `authorization_type = "DIRECT"` with both manager columns NULL. Per the column's own
 * documentation `DIRECT` means the actor held the authority themselves and no manager was
 * involved — so a cashier who voided a bill under a manager's step-up produced a durable record
 * asserting the opposite of what happened.
 *
 * The actor stays the cashier either way (see `TokenClaimsElevation` for why a Kassennachschau must
 * see that a cashier voided the bill with a manager beside them); these tests are about the SECOND
 * name alongside it.
 *
 * The false-positive legs matter as much as the positive one: a `TEMPORARY_SESSION` stamped on an
 * action nobody authorised is exactly as wrong as a missing one, so no-elevation, wrong-employee and
 * expired-session all have to keep saying `DIRECT`.
 */
@DisplayName("Audit rows name the manager behind a step-up")
class AuditAuthorizationTypeTest {
    /**
     * A device id per test.
     *
     * [TemporarySessionStore] is a process-wide `object` backed by a `ConcurrentHashMap` and is NOT
     * cleared between tests, so a session seeded here would otherwise still answer
     * `getSessionByDevice` for whichever class JUnit runs next. Every session created is also torn
     * down in [removeSeededSessions].
     */
    private var device = "AUDIT-AUTH"

    private val seededSessionIds = mutableListOf<String>()

    @BeforeEach
    fun setup(info: TestInfo) {
        device = "AUDIT-AUTH-${info.displayName.hashCode()}"
        TemporarySessionStore.getSessionByDevice(device)?.let { TemporarySessionStore.removeSession(it.sessionId) }
        TestDatabase.setup()
    }

    @AfterEach
    fun removeSeededSessions() {
        seededSessionIds.forEach { TemporarySessionStore.removeSession(it) }
        seededSessionIds.clear()
        TestDatabase.teardown()
    }

    /** Seeds a step-up on [device] lending `BILL_VOID` to [requestingEmployeeId]. */
    private fun lendBillVoidTo(
        requestingEmployeeId: String,
        managerId: String,
        managerName: String = "Test Manager",
        expiresInMinutes: Int = 5,
    ): String {
        val sessionId = "sess-${seededSessionIds.size}-${System.nanoTime()}"
        TemporarySessionStore.addSession(
            TemporarySessionStore.SessionInfo(
                sessionId = sessionId,
                managerId = managerId,
                managerName = managerName,
                managerRole = EmployeeRole.MANAGER,
                deviceId = device,
                requestingEmployeeId = requestingEmployeeId,
                grantedCapabilities = setOf(Capability.BILL_VOID),
                createdAt = currentInstant(),
                expiresAt = currentInstant().plus(expiresInMinutes.minutes),
            ),
        )
        seededSessionIds += sessionId
        return sessionId
    }

    /** Creates a cashier and returns the UUID string the token would carry for them. */
    private fun cashierUuid(username: String) =
        transaction {
            val cashier = TestData.createCashier(username = username, employeeNumber = username)
            cashier.id.value.toString()
        }

    /** Creates a manager and returns the UUID string a step-up would store as `managerId`. */
    private fun managerUuid() =
        transaction {
            val manager = TestData.createManager()
            manager.id.value.toString()
        }

    private suspend fun auditRow(operation: String): ResultRow =
        dbQuery {
            TransactionAuditLogTable
                .selectAll()
                .where { TransactionAuditLogTable.operation eq operation }
                .single()
        }

    /** Runs one audited critical operation as [employeeId] on [device]. */
    private suspend fun performAudited(
        employeeId: String,
        operation: String = "CREATE_BILL",
    ) = ExceptionConverter.catchingThrows(
        context = "test($operation)",
        employeeId = employeeId,
        operation = operation,
        entityType = "BILL",
        deviceId = device,
    ) { "done" }

    @Test
    fun `an action under an active step-up records TEMPORARY_SESSION and the approving manager`() =
        runBlocking {
            val cashier = cashierUuid("cashier")
            val manager = managerUuid()
            lendBillVoidTo(requestingEmployeeId = cashier, managerId = manager, managerName = "Erika Musterfrau")

            performAudited(cashier)

            val row = auditRow("CREATE_BILL")
            assertEquals(
                "TEMPORARY_SESSION",
                row[TransactionAuditLogTable.authorizationType],
                "a step-up was in force, so the row must not claim the cashier held the authority alone",
            )
            assertEquals(manager, row[TransactionAuditLogTable.authorizingManagerId])
            assertEquals("Erika Musterfrau", row[TransactionAuditLogTable.authorizingManagerName])
            assertEquals(
                cashier,
                row[TransactionAuditLogTable.employeeId],
                "the actor stays the cashier — the manager is the SECOND name, not a replacement",
            )
        }

    @Test
    fun `an action with no elevation still records DIRECT and no manager`() =
        runBlocking {
            val cashier = cashierUuid("cashier")

            performAudited(cashier)

            val row = auditRow("CREATE_BILL")
            assertEquals("DIRECT", row[TransactionAuditLogTable.authorizationType])
            assertNull(row[TransactionAuditLogTable.authorizingManagerId], "no manager was involved")
            assertNull(row[TransactionAuditLogTable.authorizingManagerName], "no manager was involved")
        }

    @Test
    fun `an elevation granted to a different cashier on the same till does not taint this row`() =
        runBlocking {
            val first = cashierUuid("cashier")
            val second = cashierUuid("second")
            lendBillVoidTo(requestingEmployeeId = first, managerId = managerUuid())

            // Same device, different person — the session belongs to a person at a station, and the
            // authorization gate honours it for that person only. The audit row must agree.
            performAudited(second)

            val row = auditRow("CREATE_BILL")
            assertEquals("DIRECT", row[TransactionAuditLogTable.authorizationType])
            assertNull(row[TransactionAuditLogTable.authorizingManagerId])
            assertNull(row[TransactionAuditLogTable.authorizingManagerName])
        }

    @Test
    fun `an expired elevation records DIRECT`() =
        runBlocking {
            val cashier = cashierUuid("cashier")
            lendBillVoidTo(
                requestingEmployeeId = cashier,
                managerId = managerUuid(),
                expiresInMinutes = -1,
            )

            performAudited(cashier)

            val row = auditRow("CREATE_BILL")
            assertEquals(
                "DIRECT",
                row[TransactionAuditLogTable.authorizationType],
                "an expired session lends nothing, so it must not be recorded as authorisation",
            )
            assertNull(row[TransactionAuditLogTable.authorizingManagerId])
            assertNull(row[TransactionAuditLogTable.authorizingManagerName])
        }

    @Test
    fun `a FAILED action under a step-up records the elevation too`() =
        runBlocking {
            val cashier = cashierUuid("cashier")
            val manager = managerUuid()
            lendBillVoidTo(requestingEmployeeId = cashier, managerId = manager, managerName = "Erika Musterfrau")

            val thrown =
                runCatching {
                    ExceptionConverter.catchingThrows(
                        context = "test(DELETE_BILL)",
                        employeeId = cashier,
                        operation = "DELETE_BILL",
                        entityType = "BILL",
                        deviceId = device,
                    ) { throw IllegalStateException("boom") }
                }
            assertTrue(thrown.isFailure, "catchingThrows must propagate the exception")

            // An auditor asking "what did this manager authorise" must see the attempts, not only
            // the successes.
            val row = auditRow("DELETE_BILL")
            assertEquals("TEMPORARY_SESSION", row[TransactionAuditLogTable.authorizationType])
            assertEquals(manager, row[TransactionAuditLogTable.authorizingManagerId])
            assertEquals("Erika Musterfrau", row[TransactionAuditLogTable.authorizingManagerName])
            assertEquals("FAILED", row[TransactionAuditLogTable.entityId])
        }
}
