package org.example.project.audit

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.audit.TransactionAuditDto
import org.example.project.features.audit.data.repository.TransactionAuditRepositoryImpl
import org.example.project.testutils.TestDatabase
import org.example.project.utils.dbQuery
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Verifies global search, amount range, and authorization-type filters. */
class TransactionAuditFilterTest {
    private val repository = TransactionAuditRepositoryImpl()

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun tearDown() = TestDatabase.teardown()

    @Suppress("LongParameterList")
    private suspend fun insertAudit(
        transactionId: String,
        entityName: String? = null,
        entityId: String = "e-1",
        reason: String? = null,
        changesSummary: String? = null,
        employeeNumber: String = "1001",
        authorizationType: String = "DIRECT",
        amountImpact: Double? = null,
    ) = dbQuery {
        repository.create(
            transactionId = transactionId,
            employeeId = "emp-1",
            employeeName = null,
            employeeNumber = employeeNumber,
            authorizationType = authorizationType,
            authorizingManagerId = null,
            authorizingManagerName = null,
            operation = "CREATE_ORDER",
            entityType = "ORDER",
            entityId = entityId,
            entityName = entityName,
            beforeState = null,
            afterState = null,
            changesSummary = changesSummary,
            deviceId = null,
            ipAddress = null,
            reason = reason,
            amountImpact = amountImpact,
            currencyCode = "EUR",
            metadata = null,
            timestamp = "2026-06-01T10:00:00",
            fiscalRecordId = null,
        )
    }

    private suspend fun count(query: TransactionAuditDto.PaginatedTransactionAuditQueryDto): Long = dbQuery { repository.findPaginated(query).first }

    @Test
    fun `search matches entityName case-insensitively`() =
        runBlocking {
            insertAudit("t-1", entityName = "Cappuccino Order")
            insertAudit("t-2", entityName = "Espresso Order")
            assertEquals(1L, count(TransactionAuditDto.PaginatedTransactionAuditQueryDto(search = "cappu")))
        }

    @Test
    fun `search matches transactionId, reason, changesSummary, employeeNumber`() =
        runBlocking {
            insertAudit("TXN-ABC", entityName = "A")
            insertAudit("t-2", entityName = "B", reason = "manager void")
            insertAudit("t-3", entityName = "C", changesSummary = "price lowered")
            insertAudit("t-4", entityName = "D", employeeNumber = "9999")
            assertEquals(1L, count(TransactionAuditDto.PaginatedTransactionAuditQueryDto(search = "abc")))
            assertEquals(1L, count(TransactionAuditDto.PaginatedTransactionAuditQueryDto(search = "void")))
            assertEquals(1L, count(TransactionAuditDto.PaginatedTransactionAuditQueryDto(search = "lowered")))
            assertEquals(1L, count(TransactionAuditDto.PaginatedTransactionAuditQueryDto(search = "9999")))
        }

    @Test
    fun `amount range is inclusive and excludes null amounts`() =
        runBlocking {
            insertAudit("t-10", amountImpact = 10.0)
            insertAudit("t-50", amountImpact = 50.0)
            insertAudit("t-100", amountImpact = 100.0)
            insertAudit("t-null", amountImpact = null)
            assertEquals(2L, count(TransactionAuditDto.PaginatedTransactionAuditQueryDto(amountMin = "50.0")))
            assertEquals(2L, count(TransactionAuditDto.PaginatedTransactionAuditQueryDto(amountMax = "50.0")))
            assertEquals(1L, count(TransactionAuditDto.PaginatedTransactionAuditQueryDto(amountMin = "40.0", amountMax = "60.0")))
        }

    @Test
    fun `authorizationType matches exactly`() =
        runBlocking {
            insertAudit("t-1", authorizationType = "DIRECT")
            insertAudit("t-2", authorizationType = "TEMPORARY_SESSION")
            assertEquals(1L, count(TransactionAuditDto.PaginatedTransactionAuditQueryDto(authorizationType = "DIRECT")))
        }
}
