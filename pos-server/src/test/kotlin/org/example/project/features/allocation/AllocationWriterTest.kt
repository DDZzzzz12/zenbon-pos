package org.example.project.features.allocation

import org.example.project.features.allocation.data.repository.AllocationRepositoryImpl
import org.example.project.features.allocation.data.service.AllocationWriter
import org.example.project.features.allocation.domain.models.AllocationMatchedBy
import org.example.project.features.allocation.domain.models.AllocationSourceKind
import org.example.project.features.allocation.domain.models.AllocationTargetKind
import org.example.project.features.allocation.domain.models.NewAllocation
import org.example.project.features.document.domain.models.BankStatementTransaction
import org.example.project.features.document.domain.models.Invoice
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The writer's contract: an allocation and the residuals it changes are written together.
 *
 * Every assertion reads in its **own** transaction — within one, Exposed's identity map can serve
 * back the instance just written rather than what the column actually stored.
 */
@DisplayName("AllocationWriter writes an allocation and both residuals")
class AllocationWriterTest {
    private val writer = AllocationWriter(AllocationRepositoryImpl())

    @Test
    fun `allocating part of a transaction to an invoice reduces both residuals`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val invoiceId = transaction { AllocationFixtures.newInvoice(BigDecimal("100.0000")).id.value }
            val transactionId = transaction { AllocationFixtures.newBankTransaction(BigDecimal("100.0000")).id.value }

            transaction {
                writer.allocate(bankToInvoice(transactionId, invoiceId, BigDecimal("40.00")))
            }

            transaction {
                assertResidual("60.0000", Invoice.findById(invoiceId)!!.openAmount)
                assertResidual("60.0000", BankStatementTransaction.findById(transactionId)!!.openAmount)
            }
        }
    }

    /** The split case: one invoice settled out of two different transactions. */
    @Test
    fun `two allocations from two transactions both reduce the same invoice`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val invoiceId = transaction { AllocationFixtures.newInvoice(BigDecimal("100.0000")).id.value }
            val firstId = transaction { AllocationFixtures.newBankTransaction(BigDecimal("70.0000")).id.value }
            val secondId = transaction { AllocationFixtures.newBankTransaction(BigDecimal("30.0000")).id.value }

            transaction { writer.allocate(bankToInvoice(firstId, invoiceId, BigDecimal("70.00"))) }
            transaction { writer.allocate(bankToInvoice(secondId, invoiceId, BigDecimal("30.00"))) }

            transaction {
                assertResidual("0.0000", Invoice.findById(invoiceId)!!.openAmount)
                assertResidual("0.0000", BankStatementTransaction.findById(firstId)!!.openAmount)
                assertResidual("0.0000", BankStatementTransaction.findById(secondId)!!.openAmount)
            }
        }
    }

    /**
     * The invoice paid out of the till. Without a typed cash-book *source* it would stay open
     * forever, because no bank transaction will ever reference it.
     */
    @Test
    fun `a cash-book source reduces an invoice residual exactly as a bank transaction does`() {
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) {
            val invoiceId = transaction { AllocationFixtures.newInvoice(BigDecimal("42.5000")).id.value }
            val headerId = transaction { AllocationFixtures.newKassenbuchHeader(BigDecimal("42.5000")).id.value }

            transaction {
                writer.allocate(
                    NewAllocation(
                        sourceKind = AllocationSourceKind.CASH_BOOK_ENTRY,
                        targetKind = AllocationTargetKind.INVOICE,
                        amount = BigDecimal("42.50"),
                        matchedBy = AllocationMatchedBy.MANUAL,
                        kassenbuchHeaderId = headerId,
                        invoiceId = invoiceId,
                    ),
                )
            }

            transaction {
                assertResidual("0.0000", Invoice.findById(invoiceId)!!.openAmount)
            }
        }
    }

    @Test
    fun `removing an allocation restores both residuals`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val invoiceId = transaction { AllocationFixtures.newInvoice(BigDecimal("100.0000")).id.value }
            val transactionId = transaction { AllocationFixtures.newBankTransaction(BigDecimal("100.0000")).id.value }

            val allocationId =
                transaction {
                    writer.allocate(bankToInvoice(transactionId, invoiceId, BigDecimal("40.00"))).id.value
                }

            transaction { writer.remove(allocationId) }

            transaction {
                assertResidual("100.0000", Invoice.findById(invoiceId)!!.openAmount)
                assertResidual("100.0000", BankStatementTransaction.findById(transactionId)!!.openAmount)
            }
        }
    }

    /** A bank fee has no document behind it, but the money still left the account. */
    @Test
    fun `a target with no residual still reduces the source transaction`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val transactionId = transaction { AllocationFixtures.newBankTransaction(BigDecimal("9.9900")).id.value }

            transaction {
                writer.allocate(
                    NewAllocation(
                        sourceKind = AllocationSourceKind.BANK_TRANSACTION,
                        targetKind = AllocationTargetKind.FEE,
                        amount = BigDecimal("9.99"),
                        matchedBy = AllocationMatchedBy.MANUAL,
                        bankTransactionId = transactionId,
                        accountNo = "6855",
                        bookingText = "Kontoführungsgebühr",
                    ),
                )
            }

            transaction {
                assertResidual("0.0000", BankStatementTransaction.findById(transactionId)!!.openAmount)
            }
        }
    }

    /**
     * A source id that does not match the declared kind is rejected up front. Writing the row anyway
     * would leave an allocation whose money came from nowhere, and a residual nobody recomputes.
     */
    @Test
    fun `a source kind without its id is rejected before anything is written`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val invoiceId = transaction { AllocationFixtures.newInvoice(BigDecimal("100.0000")).id.value }

            transaction {
                assertFailsWith<IllegalArgumentException> {
                    writer.allocate(
                        NewAllocation(
                            sourceKind = AllocationSourceKind.BANK_TRANSACTION,
                            targetKind = AllocationTargetKind.INVOICE,
                            amount = BigDecimal("40.00"),
                            matchedBy = AllocationMatchedBy.MANUAL,
                            invoiceId = invoiceId,
                        ),
                    )
                }
            }

            transaction {
                assertNull(Invoice.findById(invoiceId)!!.openAmount, "nothing may be written for a rejected allocation")
            }
        }
    }

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

    /** `BigDecimal.equals` is scale-sensitive, and the residual's scale is pinned by the calculator. */
    private fun assertResidual(
        expected: String,
        actual: BigDecimal?,
    ) = assertEquals(BigDecimal(expected), actual)
}
