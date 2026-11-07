package org.example.project.features.matching

import kotlinx.datetime.LocalDateTime
import org.example.project.features.allocation.AllocationFixtures
import org.example.project.features.allocation.data.repository.AllocationRepositoryImpl
import org.example.project.features.allocation.data.service.AllocationWriter
import org.example.project.features.allocation.domain.models.AllocationMatchedBy
import org.example.project.features.allocation.domain.models.AllocationSourceKind
import org.example.project.features.allocation.domain.models.AllocationTargetKind
import org.example.project.features.allocation.domain.models.NewAllocation
import org.example.project.features.allocation.domain.models.PaymentAllocation
import org.example.project.features.bank.database.tables.BankAccountTable
import org.example.project.features.bank.domain.models.BankAccount
import org.example.project.features.document.domain.models.BankStatement
import org.example.project.features.document.domain.models.BankStatementTransaction
import org.example.project.features.document.domain.models.Invoice
import org.example.project.features.matching.data.service.ReversalProcessor
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The sweep that stops a returned direct debit from leaving its invoice marked paid.
 *
 * Residuals are asserted as **amounts**, not row counts: what an operator sees is an open-item list,
 * and an allocation row that vanished without the residual going back up would still show the
 * invoice as settled.
 *
 * Every assertion reads in its own transaction — within one, Exposed's identity map can serve back
 * the instance just written rather than what the column actually stored.
 */
@DisplayName("Reversal processor")
class ReversalProcessorTest {
    private val repository = AllocationRepositoryImpl()
    private val processor = ReversalProcessor(AllocationWriter(repository), repository)

    @Test
    fun `a paired reversal voids every allocation of the entry it reverses and both residuals go back up`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val invoiceA = transaction { AllocationFixtures.newInvoice(BigDecimal("100.0000"), "RE-A").id.value }
            val invoiceB = transaction { AllocationFixtures.newInvoice(BigDecimal("50.0000"), "RE-B").id.value }
            val originalId = transaction { debit(BigDecimal("150.0000"), day = 10, endToEndId = "E2E-1").id.value }

            transaction {
                allocate(originalId, invoiceA, BigDecimal("100.0000"))
                allocate(originalId, invoiceB, BigDecimal("50.0000"))
            }
            transaction {
                assertResidual("0.0000", Invoice.findById(invoiceA)!!.openAmount)
                assertResidual("0.0000", Invoice.findById(invoiceB)!!.openAmount)
            }

            transaction { credit(BigDecimal("150.0000"), day = 12, endToEndId = "E2E-1", reversal = true) }

            assertEquals(2, transaction { processor.sweep() }, "both allocations of the reversed entry are voided")

            transaction {
                assertResidual("100.0000", Invoice.findById(invoiceA)!!.openAmount, "the invoice is open again")
                assertResidual("50.0000", Invoice.findById(invoiceB)!!.openAmount, "the invoice is open again")
                assertResidual(
                    "150.0000",
                    BankStatementTransaction.findById(originalId)!!.openAmount,
                    "the money is unassigned again, so the entry returns to the queue",
                )
            }
        }
    }

    @Test
    fun `a swept reversal is stamped`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val invoiceId = transaction { AllocationFixtures.newInvoice(BigDecimal("100.0000")).id.value }
            val originalId = transaction { debit(BigDecimal("100.0000"), day = 10, endToEndId = "E2E-1").id.value }
            transaction { allocate(originalId, invoiceId, BigDecimal("100.0000")) }
            val reversalId =
                transaction { credit(BigDecimal("100.0000"), day = 12, endToEndId = "E2E-1", reversal = true).id.value }

            transaction { processor.sweep() }

            transaction {
                assertNotNull(BankStatementTransaction.findById(reversalId)!!.reversalProcessedAt)
            }
        }
    }

    /**
     * The case the marker exists for. An operator may decide the *return* was itself the error and
     * re-allocate the original by hand; the next sweep must not undo their work.
     */
    @Test
    fun `a second sweep voids nothing and an allocation made by hand afterwards survives`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val invoiceId = transaction { AllocationFixtures.newInvoice(BigDecimal("100.0000")).id.value }
            val originalId = transaction { debit(BigDecimal("100.0000"), day = 10, endToEndId = "E2E-1").id.value }
            transaction { allocate(originalId, invoiceId, BigDecimal("100.0000")) }
            transaction { credit(BigDecimal("100.0000"), day = 12, endToEndId = "E2E-1", reversal = true) }

            assertEquals(1, transaction { processor.sweep() })

            // The operator disagrees with the return and re-allocates by hand.
            val handMadeId =
                transaction { allocate(originalId, invoiceId, BigDecimal("100.0000")).id.value }

            assertEquals(0, transaction { processor.sweep() }, "the reversal has already been concluded on")

            transaction {
                assertNotNull(
                    PaymentAllocation.findById(handMadeId),
                    "the operator's later decision survives; without the marker it would be undone every night",
                )
                assertResidual("0.0000", Invoice.findById(invoiceId)!!.openAmount, "the invoice is paid again")
            }
        }
    }

    /**
     * No pairing means no voiding — and the sweep still records that it has looked. Re-deciding the
     * same reversal every night would never reach a different answer.
     */
    @Test
    fun `a reversal that pairs with nothing voids nothing and is still stamped`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val invoiceId = transaction { AllocationFixtures.newInvoice(BigDecimal("100.0000")).id.value }
            val originalId = transaction { debit(BigDecimal("100.0000"), day = 10, endToEndId = "E2E-1").id.value }
            transaction { allocate(originalId, invoiceId, BigDecimal("100.0000")) }

            val reversalId =
                transaction {
                    credit(BigDecimal("77.0000"), day = 12, endToEndId = "E2E-UNKNOWN", reversal = true).id.value
                }

            assertEquals(0, transaction { processor.sweep() })

            transaction {
                assertNotNull(
                    BankStatementTransaction.findById(reversalId)!!.reversalProcessedAt,
                    "the sweep has looked and found nothing, which is a conclusion rather than a failure",
                )
                assertResidual("0.0000", Invoice.findById(invoiceId)!!.openAmount, "nothing was un-paid")
            }
        }
    }

    /**
     * 5a's eligibility rule, reused: an unresolved statement has no account, therefore no shop, so
     * nothing that depends on one can be evaluated. It is left **unstamped** so the sweep picks it
     * up once an operator resolves the statement.
     */
    @Test
    fun `a reversal on a statement with no bank account is skipped entirely`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val invoiceId = transaction { AllocationFixtures.newInvoice(BigDecimal("100.0000")).id.value }
            val originalId = transaction { debit(BigDecimal("100.0000"), day = 10, endToEndId = "E2E-1").id.value }
            transaction { allocate(originalId, invoiceId, BigDecimal("100.0000")) }

            val reversalId =
                transaction {
                    credit(
                        BigDecimal("100.0000"),
                        day = 12,
                        endToEndId = "E2E-1",
                        reversal = true,
                        resolved = false,
                    ).id.value
                }

            assertEquals(0, transaction { processor.sweep() })

            transaction {
                assertNull(
                    BankStatementTransaction.findById(reversalId)!!.reversalProcessedAt,
                    "unstamped, so the sweep picks it up once the statement is resolved to an account",
                )
                assertResidual("0.0000", Invoice.findById(invoiceId)!!.openAmount)
            }
        }
    }

    private fun allocate(
        transactionId: Long,
        invoiceId: Long,
        amount: BigDecimal,
    ): PaymentAllocation =
        AllocationWriter(repository).allocate(
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
        endToEndId: String? = null,
    ): BankStatementTransaction = entry(amount, day, "DEBIT", endToEndId, reversal = false, resolved = true)

    private fun credit(
        amount: BigDecimal,
        day: Int,
        endToEndId: String? = null,
        reversal: Boolean = false,
        resolved: Boolean = true,
    ): BankStatementTransaction = entry(amount, day, "CREDIT", endToEndId, reversal, resolved)

    private fun entry(
        amount: BigDecimal,
        day: Int,
        creditDebit: String,
        endToEndId: String?,
        reversal: Boolean,
        resolved: Boolean,
    ): BankStatementTransaction {
        val statement =
            BankStatement.new {
                accountIban = OWN_ACCOUNT_IBAN
                if (resolved) bankAccount = ownAccount()
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

    private fun assertResidual(
        expected: String,
        actual: BigDecimal?,
        message: String? = null,
    ) {
        assertNotNull(actual, message ?: "the residual must have been computed")
        assertEquals(0, BigDecimal(expected).compareTo(actual), "$message: expected $expected but was $actual")
    }

    private companion object {
        const val OWN_ACCOUNT_IBAN = "DE89370400440532013000"
    }
}
