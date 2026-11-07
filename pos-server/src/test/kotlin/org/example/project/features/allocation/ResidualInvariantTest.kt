package org.example.project.features.allocation

import org.example.project.features.allocation.data.repository.AllocationRepositoryImpl
import org.example.project.features.allocation.data.service.AllocationWriter
import org.example.project.features.allocation.data.service.residualOf
import org.example.project.features.allocation.database.tables.PaymentAllocationTable
import org.example.project.features.allocation.domain.models.AllocationMatchedBy
import org.example.project.features.allocation.domain.models.AllocationSourceKind
import org.example.project.features.allocation.domain.models.AllocationTargetKind
import org.example.project.features.allocation.domain.models.NewAllocation
import org.example.project.features.contract.domain.models.ContractInstalment
import org.example.project.features.document.domain.models.BankStatementTransaction
import org.example.project.features.document.domain.models.Invoice
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The drift guard, and the reason `open_amount` is allowed to be a persisted derivation at all.
 *
 * This is **not** a second copy of `AllocationWriterTest`. That one proves the writer is right for
 * the cases it was handed; this one runs an arbitrary mixed sequence, then recomputes *every*
 * residual in the database from the allocation rows themselves — through the DSL, not through the
 * repository the writer used — and asserts the stored value matches. Stored state going quietly
 * wrong over a sequence of edits is what actually degrades in production; nothing else here catches
 * it.
 */
@DisplayName("every stored residual still equals a from-scratch recomputation")
class ResidualInvariantTest {
    private val writer = AllocationWriter(AllocationRepositoryImpl())

    @Test
    fun `a mixed batch of allocations leaves no residual drifted`() {
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) {
            val ids = transaction { seedFixtures() }

            // A deliberately untidy sequence: a split invoice, a full settlement, a document-less
            // fee, a cash-paid invoice whose amount_due differs from its gross, and one undo.
            transaction { writer.allocate(bankToInvoice(ids.tx1, ids.invoiceA, BigDecimal("40.00"))) }
            transaction { writer.allocate(bankToInvoice(ids.tx1, ids.invoiceA, BigDecimal("60.00"))) }
            val undone =
                transaction {
                    writer.allocate(bankToInvoice(ids.tx2, ids.invoiceB, BigDecimal("250.00"))).id.value
                }
            transaction {
                writer.allocate(
                    NewAllocation(
                        sourceKind = AllocationSourceKind.BANK_TRANSACTION,
                        targetKind = AllocationTargetKind.FEE,
                        amount = BigDecimal("15.00"),
                        matchedBy = AllocationMatchedBy.MANUAL,
                        bankTransactionId = ids.tx3,
                        accountNo = "6855",
                        bookingText = "Kontoführungsgebühr",
                    ),
                )
            }
            transaction {
                writer.allocate(
                    NewAllocation(
                        sourceKind = AllocationSourceKind.CASH_BOOK_ENTRY,
                        targetKind = AllocationTargetKind.INVOICE,
                        amount = BigDecimal("55.00"),
                        matchedBy = AllocationMatchedBy.MANUAL,
                        kassenbuchHeaderId = ids.cashHeader,
                        invoiceId = ids.invoiceC,
                    ),
                )
            }
            transaction {
                writer.allocate(
                    NewAllocation(
                        sourceKind = AllocationSourceKind.BANK_TRANSACTION,
                        targetKind = AllocationTargetKind.CONTRACT_INSTALMENT,
                        amount = BigDecimal("70.00"),
                        matchedBy = AllocationMatchedBy.MANUAL,
                        bankTransactionId = ids.tx3,
                        contractInstalmentId = ids.instalment,
                    ),
                )
            }
            transaction { writer.remove(undone) }

            transaction { assertNoResidualHasDrifted() }
        }
    }

    /**
     * Recomputes both residuals for every row from the allocation table and compares with what is
     * stored. Reads the amounts through the DSL so a bug in the repository's sums cannot make the
     * comparison agree with itself.
     */
    private fun assertNoResidualHasDrifted() {
        var compared = 0

        Invoice.all().forEach { invoice ->
            val expected =
                residualOf(
                    invoice.amountDue ?: invoice.taxInclusiveTotal,
                    amountsFor(PaymentAllocationTable.invoiceId eq invoice.id.value),
                )
            assertEquals(expected, invoice.openAmount, "invoice ${invoice.id.value} residual drifted")
            compared++
        }

        BankStatementTransaction.all().forEach { bankTransaction ->
            val expected =
                residualOf(
                    bankTransaction.amount,
                    amountsFor(PaymentAllocationTable.bankTransactionId eq bankTransaction.id.value),
                )
            assertEquals(expected, bankTransaction.openAmount, "transaction ${bankTransaction.id.value} residual drifted")
            compared++
        }

        ContractInstalment.all().forEach { instalment ->
            val expected =
                residualOf(
                    instalment.expectedAmount,
                    amountsFor(PaymentAllocationTable.contractInstalmentId eq instalment.id.value),
                )
            assertEquals(expected, instalment.openAmount, "instalment ${instalment.id.value} residual drifted")
            compared++
        }

        // Without this the whole assertion would pass vacuously on an empty database.
        assertEquals(
            7,
            compared,
            "the batch must have touched three invoices, three transactions and one instalment",
        )
        assertTrue(
            PaymentAllocationTable.selectAll().count() == 5L,
            "five allocations must survive the removal",
        )
    }

    private fun amountsFor(condition: Op<Boolean>): List<BigDecimal> =
        PaymentAllocationTable
            .select(PaymentAllocationTable.amount)
            .where(condition)
            .map { it[PaymentAllocationTable.amount] }

    private fun seedFixtures(): Fixtures =
        Fixtures(
            invoiceA = AllocationFixtures.newInvoice(BigDecimal("100.0000"), "RE-A").id.value,
            invoiceB = AllocationFixtures.newInvoice(BigDecimal("250.0000"), "RE-B").id.value,
            invoiceC =
                AllocationFixtures
                    .newInvoiceWithAmountDue(BigDecimal("60.0000"), BigDecimal("55.0000"), "RE-C")
                    .id.value,
            tx1 = AllocationFixtures.newBankTransaction(BigDecimal("100.0000")).id.value,
            tx2 = AllocationFixtures.newBankTransaction(BigDecimal("250.0000")).id.value,
            tx3 = AllocationFixtures.newBankTransaction(BigDecimal("40.0000")).id.value,
            cashHeader = AllocationFixtures.newKassenbuchHeader(BigDecimal("55.0000")).id.value,
            instalment = ContractFixtures.newInstalment(BigDecimal("120.0000")).id.value,
        )

    private fun bankToInvoice(
        transactionId: Long,
        invoiceId: Long,
        amount: BigDecimal,
    ) = NewAllocation(
        sourceKind = AllocationSourceKind.BANK_TRANSACTION,
        targetKind = AllocationTargetKind.INVOICE,
        amount = amount,
        matchedBy = AllocationMatchedBy.MANUAL,
        bankTransactionId = transactionId,
        invoiceId = invoiceId,
    )

    private data class Fixtures(
        val invoiceA: Long,
        val invoiceB: Long,
        val invoiceC: Long,
        val tx1: Long,
        val tx2: Long,
        val tx3: Long,
        val cashHeader: Long,
        val instalment: Long,
    )
}
