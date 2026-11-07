package org.example.project.features.matching

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import org.example.project.core.domain.dto.audit.TransactionAuditDto
import org.example.project.core.domain.enums.scheduler.ScheduledJobType
import org.example.project.features.allocation.AllocationFixtures
import org.example.project.features.allocation.data.repository.AllocationRepositoryImpl
import org.example.project.features.allocation.data.service.AllocationWriter
import org.example.project.features.allocation.domain.models.AllocationMatchedBy
import org.example.project.features.allocation.domain.models.AllocationSourceKind
import org.example.project.features.allocation.domain.models.AllocationTargetKind
import org.example.project.features.allocation.domain.models.NewAllocation
import org.example.project.features.audit.data.repository.TransactionAuditRepositoryImpl
import org.example.project.features.audit.data.service.TransactionAuditServiceImpl
import org.example.project.features.audit.database.tables.TransactionAuditLogTable
import org.example.project.features.bank.database.tables.BankAccountTable
import org.example.project.features.bank.domain.models.BankAccount
import org.example.project.features.document.domain.models.BankStatement
import org.example.project.features.document.domain.models.BankStatementTransaction
import org.example.project.features.document.domain.models.Invoice
import org.example.project.features.matching.data.service.ReversalProcessor
import org.example.project.features.scheduler.data.service.executors.ReversalJobExecutor
import org.example.project.features.scheduler.domain.service.ScheduledJobExecutor
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The nightly wrapper around [ReversalProcessor]. What it owes the scheduler is its own transaction
 * and a truthful count — including a zero on the second night, once the reversals have been
 * concluded on.
 */
@DisplayName("Reversal sweep job")
class ReversalJobExecutorTest {
    private val repository = AllocationRepositoryImpl()
    private val executor =
        ReversalJobExecutor(
            ReversalProcessor(AllocationWriter(repository), repository),
            TransactionAuditServiceImpl(TransactionAuditRepositoryImpl(), Json),
        )

    @Test
    fun `it is the executor for the reversal sweep job type`() {
        assertEquals(ScheduledJobType.REVERSAL_SWEEP, executor.type)
    }

    /**
     * The engine collects executors with `getAll<ScheduledJobExecutor>()`, which only sees
     * definitions bound to that interface under distinct qualifiers — a plain `singleOf` would
     * compile, run, and never be handed to the scheduler. This asserts the real DI graph.
     */
    @Test
    fun `the scheduler engine collects it`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val collected = GlobalContext.get().getAll<ScheduledJobExecutor>().map { it.type }
            assertTrue(
                ScheduledJobType.REVERSAL_SWEEP in collected,
                "the sweep must be reachable via getAll, otherwise it silently never runs: $collected",
            )
        }
    }

    /** The executor opens its own transaction, so a bare call must not need one around it. */
    @Test
    fun `it opens its own transaction and reports what it voided, then zero on the next night`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val invoiceId = transaction { AllocationFixtures.newInvoice(BigDecimal("100.0000")).id.value }
            val originalId = transaction { debit(BigDecimal("100.0000"), day = 10, endToEndId = "E2E-1").id.value }
            transaction { allocate(originalId, invoiceId, BigDecimal("100.0000")) }
            val reversalId =
                transaction {
                    credit(BigDecimal("100.0000"), day = 12, endToEndId = "E2E-1", reversal = true).id.value
                }

            val first = runBlocking { executor.execute("{}") }
            val second = runBlocking { executor.execute("{}") }

            assertTrue(first.contains("1"), "the one allocation of the reversed entry was voided, reported: $first")
            assertTrue(second.contains("0"), "the reversal has already been concluded on: $second")
            transaction {
                assertNotNull(BankStatementTransaction.findById(reversalId)!!.reversalProcessedAt)
                assertEquals(
                    0,
                    BigDecimal("100.0000").compareTo(Invoice.findById(invoiceId)!!.openAmount),
                    "the invoice is open again",
                )
            }
        }
    }

    /**
     * T5-9: a Rücklastschrift used to delete an operator's allocation with nothing on record but the
     * run's one-line count. Each voided allocation now leaves an audit row naming the invoice that
     * is open again and the reversal that caused it — and only one: the second night voids nothing.
     */
    @Test
    fun `every voided allocation leaves one audit row naming the reversal and the re-opened target`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val invoiceId = transaction { AllocationFixtures.newInvoice(BigDecimal("80.0000")).id.value }
            val originalId = transaction { debit(BigDecimal("80.0000"), day = 10, endToEndId = "E2E-AUDIT").id.value }
            val allocationId = transaction { allocate(originalId, invoiceId, BigDecimal("80.0000")).id.value }
            val reversalId =
                transaction {
                    credit(BigDecimal("80.0000"), day = 12, endToEndId = "E2E-AUDIT", reversal = true).id.value
                }

            runBlocking { executor.execute("{}") }
            runBlocking { executor.execute("{}") }

            transaction {
                val rows =
                    TransactionAuditLogTable
                        .selectAll()
                        .where {
                            TransactionAuditLogTable.operation eq
                                TransactionAuditDto.TransactionOperation.VOID_RETURNED_DEBIT_ALLOCATION.name
                        }.toList()
                assertEquals(1, rows.size, "exactly one row for the one voided allocation, none on the quiet night")
                val row = rows.single()
                assertEquals("payment_allocation", row[TransactionAuditLogTable.entityType])
                assertEquals(allocationId.toString(), row[TransactionAuditLogTable.entityId])
                assertEquals("SYSTEM", row[TransactionAuditLogTable.employeeId])
                assertEquals("INVOICE $invoiceId", row[TransactionAuditLogTable.entityName])
                val reason = requireNotNull(row[TransactionAuditLogTable.reason])
                assertTrue(reversalId.toString() in reason && originalId.toString() in reason, reason)
            }
        }
    }

    private fun allocate(
        transactionId: Long,
        invoiceId: Long,
        amount: BigDecimal,
    ) = AllocationWriter(repository).allocate(
        NewAllocation(
            sourceKind = AllocationSourceKind.BANK_TRANSACTION,
            targetKind = AllocationTargetKind.INVOICE,
            amount = amount,
            matchedBy = AllocationMatchedBy.MANUAL,
            bankTransactionId = transactionId,
            invoiceId = invoiceId,
        ),
    )

    private fun debit(
        amount: BigDecimal,
        day: Int,
        endToEndId: String?,
    ): BankStatementTransaction = entry(amount, day, "DEBIT", endToEndId, reversal = false)

    private fun credit(
        amount: BigDecimal,
        day: Int,
        endToEndId: String?,
        reversal: Boolean,
    ): BankStatementTransaction = entry(amount, day, "CREDIT", endToEndId, reversal)

    private fun entry(
        amount: BigDecimal,
        day: Int,
        creditDebit: String,
        endToEndId: String?,
        reversal: Boolean,
    ): BankStatementTransaction {
        val statement =
            BankStatement.new {
                accountIban = OWN_ACCOUNT_IBAN
                bankAccount = ownAccount()
                createdAt = currentTimeStamp()
            }
        val on = LocalDateTime(2026, 3, day, 0, 0)
        return BankStatementTransaction.new {
            this.statement = statement
            bookingDate = on
            valueDate = on
            this.amount = amount
            this.creditDebit = creditDebit
            this.endToEndId = endToEndId
            counterpartyIban = "DE02120300000000202051"
            reversalIndicator = reversal
        }
    }

    private fun ownAccount(): BankAccount =
        BankAccount.find { BankAccountTable.iban eq OWN_ACCOUNT_IBAN }.firstOrNull()
            ?: BankAccount.new {
                shop = ShopInfo.all().first()
                iban = OWN_ACCOUNT_IBAN
                bankName = "Sparkasse"
                createdAt = currentTimeStamp()
            }

    private companion object {
        const val OWN_ACCOUNT_IBAN = "DE89370400440532013000"
    }
}
