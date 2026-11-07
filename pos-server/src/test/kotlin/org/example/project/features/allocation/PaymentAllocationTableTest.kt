package org.example.project.features.allocation

import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.features.allocation.database.tables.PaymentAllocationTable
import org.example.project.features.allocation.domain.models.AllocationMatchedBy
import org.example.project.features.allocation.domain.models.AllocationSourceKind
import org.example.project.features.allocation.domain.models.AllocationTargetKind
import org.example.project.features.allocation.domain.models.PaymentAllocation
import org.example.project.features.document.domain.models.BankStatement
import org.example.project.features.document.domain.models.BankStatementTransaction
import org.example.project.features.document.domain.models.Invoice
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.matching.data.service.MatchSignal
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestData
import org.example.project.testutils.atStoredPrecision
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Every assertion reads in its **own** transaction: within one, Exposed's identity map can serve
 * back the instance just written rather than what the column actually stored, so a column that
 * never persisted would still assert green.
 */
@DisplayName("payment_allocation and the two residual columns")
class PaymentAllocationTableTest {
    @Test
    fun `an allocation persists with a bank-transaction source and an invoice target`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val invoiceId = transaction { newInvoice(BigDecimal("119.0000")).id.value }
            val transactionId = transaction { newBankTransaction(BigDecimal("119.0000")).id.value }

            val allocationId =
                transaction {
                    PaymentAllocation
                        .new {
                            sourceKind = AllocationSourceKind.BANK_TRANSACTION.name
                            bankTransaction = BankStatementTransaction.findById(transactionId)
                            targetKind = AllocationTargetKind.INVOICE.name
                            invoice = Invoice.findById(invoiceId)
                            amount = BigDecimal("119.0000")
                            matchedBy = AllocationMatchedBy.MANUAL.name
                            createdBy = "admin"
                        }.id.value
                }

            transaction {
                val reloaded = PaymentAllocation.findById(allocationId)!!
                assertEquals(AllocationSourceKind.BANK_TRANSACTION.name, reloaded.sourceKind)
                assertEquals(transactionId, reloaded.bankTransaction?.id?.value)
                assertEquals(AllocationTargetKind.INVOICE.name, reloaded.targetKind)
                assertEquals(invoiceId, reloaded.invoice?.id?.value)
                assertEquals(0, BigDecimal("119.0000").compareTo(reloaded.amount))
                assertEquals("admin", reloaded.createdBy)
                assertNotNull(reloaded.createdAt, "createdAt must be filled by the column's clientDefault")

                // The other target columns: null on an INVOICE allocation, because exactly one of
                // them is ever set and the kind says which.
                assertNull(reloaded.contractInstalment)
                assertNull(reloaded.kassenbuchHeader)
                assertNull(reloaded.accountNo)
                assertNull(reloaded.ruleId)
            }
        }
    }

    /** The cash-paid invoice: without a typed cash-book *source* it stays unpaid forever. */
    @Test
    fun `an allocation persists with a cash-book source and an invoice target`() {
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) {
            val invoiceId = transaction { newInvoice(BigDecimal("42.5000")).id.value }
            val headerId = transaction { newKassenbuchHeader(BigDecimal("42.5000")).id.value }

            val allocationId =
                transaction {
                    PaymentAllocation
                        .new {
                            sourceKind = AllocationSourceKind.CASH_BOOK_ENTRY.name
                            kassenbuchHeader = KassenbuchHeader.findById(headerId)
                            targetKind = AllocationTargetKind.INVOICE.name
                            invoice = Invoice.findById(invoiceId)
                            amount = BigDecimal("42.5000")
                            matchedBy = AllocationMatchedBy.MANUAL.name
                        }.id.value
                }

            transaction {
                val reloaded = PaymentAllocation.findById(allocationId)!!
                assertEquals(AllocationSourceKind.CASH_BOOK_ENTRY.name, reloaded.sourceKind)
                assertEquals(headerId, reloaded.kassenbuchHeader?.id?.value)
                assertNull(reloaded.bankTransaction)
                assertEquals(invoiceId, reloaded.invoice?.id?.value)
            }
        }
    }

    /**
     * `matched_by` carries no DB default: how an allocation came about is never guessed, it is
     * stated by whoever wrote the row.
     */
    @Test
    fun `matchedBy is required, has no implicit default and round-trips`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            assertNull(PaymentAllocationTable.matchedBy.defaultValueFun, "matched_by must have no default")

            val transactionId = transaction { newBankTransaction(BigDecimal("9.9900")).id.value }
            val allocationId =
                transaction {
                    PaymentAllocation
                        .new {
                            sourceKind = AllocationSourceKind.BANK_TRANSACTION.name
                            bankTransaction = BankStatementTransaction.findById(transactionId)
                            targetKind = AllocationTargetKind.FEE.name
                            amount = BigDecimal("9.9900")
                            accountNo = "6855"
                            bookingText = "Kontoführungsgebühr"
                            matchedBy = AllocationMatchedBy.SUGGESTION_ACCEPTED.name
                            confidence = BigDecimal("0.8750")
                        }.id.value
                }

            transaction {
                val reloaded = PaymentAllocation.findById(allocationId)!!
                assertEquals(AllocationMatchedBy.SUGGESTION_ACCEPTED.name, reloaded.matchedBy)
                assertEquals(AllocationTargetKind.FEE.name, reloaded.targetKind)
                assertEquals("6855", reloaded.accountNo)
                assertEquals("Kontoführungsgebühr", reloaded.bookingText)
                assertEquals(0, BigDecimal("0.8750").compareTo(reloaded.confidence!!))
                assertNull(reloaded.invoice)
            }
        }
    }

    /**
     * `open_amount` is nullable and starts null on a freshly written row — this slice adds the
     * column only; [org.example.project.features.allocation.data.service.AllocationWriter] fills it.
     * Null is "no residual known yet", distinct from zero, which means settled.
     */
    @Test
    fun `openAmount is nullable and starts null on both invoice and bank_statement_transaction`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val invoiceId = transaction { newInvoice(BigDecimal("119.0000")).id.value }
            val transactionId = transaction { newBankTransaction(BigDecimal("119.0000")).id.value }

            transaction {
                assertNull(Invoice.findById(invoiceId)!!.openAmount)
                assertNull(BankStatementTransaction.findById(transactionId)!!.openAmount)
            }

            transaction {
                Invoice.findById(invoiceId)!!.openAmount = BigDecimal("19.0000")
                BankStatementTransaction.findById(transactionId)!!.openAmount = BigDecimal.ZERO
            }

            transaction {
                assertEquals(0, BigDecimal("19.0000").compareTo(Invoice.findById(invoiceId)!!.openAmount!!))
                assertEquals(0, BigDecimal.ZERO.compareTo(BankStatementTransaction.findById(transactionId)!!.openAmount!!))
            }
        }
    }

    /**
     * `matched_signal` is what makes `matched_by` answer a useful question: not "are suggestions
     * used" but "which of the seven signals earn their place". Null on a manual allocation, and
     * `rule_id`/`confidence` stay reserved rather than being overloaded with it.
     */
    @Test
    fun `matchedSignal is nullable, starts null and round-trips`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val transactionId = transaction { newBankTransaction(BigDecimal("119.0000")).id.value }
            val allocationId =
                transaction {
                    PaymentAllocation
                        .new {
                            sourceKind = AllocationSourceKind.BANK_TRANSACTION.name
                            bankTransaction = BankStatementTransaction.findById(transactionId)
                            targetKind = AllocationTargetKind.FEE.name
                            amount = BigDecimal("119.0000")
                            matchedBy = AllocationMatchedBy.MANUAL.name
                        }.id.value
                }

            transaction {
                val reloaded = PaymentAllocation.findById(allocationId)!!
                assertNull(reloaded.matchedSignal, "a manual allocation claims no signal")
                assertNull(reloaded.ruleId, "rule_id stays reserved for a future auto-rule engine")
            }

            transaction {
                PaymentAllocation.findById(allocationId)!!.matchedSignal = MatchSignal.STRUCTURED_REFERENCE.name
            }

            transaction {
                assertEquals(
                    MatchSignal.STRUCTURED_REFERENCE.name,
                    PaymentAllocation.findById(allocationId)!!.matchedSignal,
                )
            }
        }
    }

    /**
     * The marker exists so a human's later decision survives. Voiding looks naturally idempotent —
     * after one sweep the original has no allocations — but an operator may decide the *return* was
     * the error and re-allocate by hand, and without this column the next sweep would undo their
     * work again, silently, forever.
     */
    @Test
    fun `reversalProcessedAt is nullable, starts null and round-trips`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val transactionId = transaction { newBankTransaction(BigDecimal("119.0000")).id.value }

            transaction {
                assertNull(
                    BankStatementTransaction.findById(transactionId)!!.reversalProcessedAt,
                    "an entry the sweep has never looked at is unstamped",
                )
            }

            // atStoredPrecision: the column holds microseconds, the Linux clock offers nanoseconds.
            val stampedAt = currentTimeStamp().atStoredPrecision()
            transaction { BankStatementTransaction.findById(transactionId)!!.reversalProcessedAt = stampedAt }

            transaction {
                assertEquals(stampedAt, BankStatementTransaction.findById(transactionId)!!.reversalProcessedAt)
            }
        }
    }

    private fun newInvoice(gross: BigDecimal): Invoice =
        Invoice.new {
            invoiceNumber = "RE-2026-0001"
            taxInclusiveTotal = gross
            createdAt = currentTimeStamp()
        }

    private fun newBankTransaction(value: BigDecimal): BankStatementTransaction {
        val statement =
            BankStatement.new {
                accountIban = "DE89370400440532013000"
                createdAt = currentTimeStamp()
            }
        return BankStatementTransaction.new {
            this.statement = statement
            bookingDate = currentTimeStamp()
            valueDate = currentTimeStamp()
            amount = value
            creditDebit = "DEBIT"
        }
    }

    private fun newKassenbuchHeader(total: BigDecimal): KassenbuchHeader {
        val shop = ShopInfo.all().first()
        return KassenbuchHeader.new {
            this.shop = shop
            cashBook = CashBookRepositoryImpl().fiscalBookFor(shop)
            employee = Employee.all().first()
            entryType = KassenbuchEntryType.CASH_EXPENSE
            belegNr = "2026-08-0001"
            belegDatum = "13.08.2026"
            belegtext = "Lieferantenrechnung bar bezahlt"
            waehrung = "EUR"
            totalAmount = total
            currentBalance = total
            createdAt = currentTimeStamp()
        }
    }
}
