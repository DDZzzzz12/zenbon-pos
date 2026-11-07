package org.example.project.features.allocation

import kotlinx.datetime.LocalDateTime
import org.example.project.features.allocation.data.repository.AllocationQueueRepositoryImpl
import org.example.project.features.allocation.data.repository.AllocationRepositoryImpl
import org.example.project.features.allocation.data.service.AllocationWriter
import org.example.project.features.allocation.domain.models.AllocationMatchedBy
import org.example.project.features.allocation.domain.models.AllocationSourceKind
import org.example.project.features.allocation.domain.models.AllocationTargetKind
import org.example.project.features.allocation.domain.models.NewAllocation
import org.example.project.features.contract.domain.models.ContractInstalmentStatus
import org.example.project.features.document.domain.models.InvoiceBooking
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The queue's semantics, at the level they are actually decided: SQL.
 *
 * The first test is the important one. Nothing sets `open_amount` on insert — neither `:accounting-banking`'s
 * parser nor `ParsedInvoiceWriter` — so a plain `open_amount > 0` filter would look correct on the
 * day V94 ran and then silently stop listing everything imported afterwards.
 */
@DisplayName("Allocation queue queries")
class AllocationQueueRepositoryTest {
    private val queue = AllocationQueueRepositoryImpl()
    private val writer = AllocationWriter(AllocationRepositoryImpl())

    private fun setup() {
        TestData.createShopInfo()
        TestData.createManager()
    }

    @Test
    fun `a freshly inserted transaction with a null residual is in the queue at its full amount`() {
        testApp(setupData = { setup() }) { _ ->
            val id =
                transaction {
                    AllocationFixtures.newResolvedBankTransaction(BigDecimal("120.5000")).id.value
                }

            transaction {
                val rows = queue.queue(unassignedOnly = true, bookedFrom = null, limit = 50, offset = 0)
                val row = rows.single { it.id.value == id }
                assertEquals(null, row.openAmount, "nothing should have written a residual yet")
                assertEquals(0, BigDecimal("120.5000").compareTo(row.amount))
                assertEquals(1L, queue.countQueue(unassignedOnly = true, bookedFrom = null))
            }
        }
    }

    @Test
    fun `a transaction on an unresolved statement never enters the queue`() {
        testApp(setupData = { setup() }) { _ ->
            transaction { AllocationFixtures.newBankTransaction(BigDecimal("40.0000")) }

            transaction {
                assertTrue(queue.queue(unassignedOnly = true, bookedFrom = null, limit = 50, offset = 0).isEmpty())
                assertEquals(0L, queue.countQueue(unassignedOnly = true, bookedFrom = null))
                assertEquals(0L, queue.countQueue(unassignedOnly = false, bookedFrom = null))
            }
        }
    }

    @Test
    fun `a fully allocated transaction leaves the unassigned queue but stays in ALLE`() {
        testApp(setupData = { setup() }) { _ ->
            val ids =
                transaction {
                    val tx = AllocationFixtures.newResolvedBankTransaction(BigDecimal("100.0000"))
                    val invoice = AllocationFixtures.newInvoice(BigDecimal("100.0000"))
                    writer.allocate(
                        NewAllocation(
                            sourceKind = AllocationSourceKind.BANK_TRANSACTION,
                            targetKind = AllocationTargetKind.INVOICE,
                            amount = BigDecimal("100.0000"),
                            matchedBy = AllocationMatchedBy.MANUAL,
                            bankTransactionId = tx.id.value,
                            invoiceId = invoice.id.value,
                        ),
                    )
                    tx.id.value to invoice.id.value
                }

            transaction {
                assertEquals(0L, queue.countQueue(unassignedOnly = true, bookedFrom = null))
                assertEquals(1L, queue.countQueue(unassignedOnly = false, bookedFrom = null))
                assertEquals(
                    ids.first,
                    queue
                        .queue(unassignedOnly = false, bookedFrom = null, limit = 50, offset = 0)
                        .single()
                        .id.value,
                )
            }
        }
    }

    @Test
    fun `a partly allocated transaction stays in the queue`() {
        testApp(setupData = { setup() }) { _ ->
            transaction {
                val tx = AllocationFixtures.newResolvedBankTransaction(BigDecimal("100.0000"))
                val invoice = AllocationFixtures.newInvoice(BigDecimal("60.0000"))
                writer.allocate(
                    NewAllocation(
                        sourceKind = AllocationSourceKind.BANK_TRANSACTION,
                        targetKind = AllocationTargetKind.INVOICE,
                        amount = BigDecimal("60.0000"),
                        matchedBy = AllocationMatchedBy.MANUAL,
                        bankTransactionId = tx.id.value,
                        invoiceId = invoice.id.value,
                    ),
                )
            }

            transaction {
                val row = queue.queue(unassignedOnly = true, bookedFrom = null, limit = 50, offset = 0).single()
                assertEquals(0, BigDecimal("40.0000").compareTo(row.openAmount))
            }
        }
    }

    @Test
    fun `a freshly parsed invoice with a null residual is an open invoice at its gross`() {
        testApp(setupData = { setup() }) { _ ->
            val id = transaction { AllocationFixtures.newInvoice(BigDecimal("250.0000")).id.value }

            transaction {
                val open = queue.openInvoices(supplierId = null, overdueAsOf = null, bookedFrom = null, limit = 50, offset = 0)
                assertEquals(listOf(id), open.map { it.id.value })
                assertEquals(null, open.single().openAmount, "nothing should have written a residual yet")
                assertEquals(0, BigDecimal("250.0000").compareTo(open.single().taxInclusiveTotal))
                assertEquals(1L, queue.countOpenInvoices(supplierId = null, overdueAsOf = null, bookedFrom = null))
            }
        }
    }

    @Test
    fun `an invoice with no total at all is not an open invoice`() {
        testApp(setupData = { setup() }) { _ ->
            transaction {
                org.example.project.features.document.domain.models.Invoice.new {
                    invoiceNumber = "RE-NO-TOTAL"
                    createdAt = currentTimeStamp()
                }
            }

            transaction {
                assertEquals(0L, queue.countOpenInvoices(supplierId = null, overdueAsOf = null, bookedFrom = null))
            }
        }
    }

    @Test
    fun `an invoice marked paid is not an open invoice`() {
        testApp(setupData = { setup() }) { _ ->
            transaction {
                val invoice = AllocationFixtures.newInvoice(BigDecimal("80.0000"))
                InvoiceBooking.new {
                    this.invoice = invoice
                    paidAt = currentTimeStamp()
                }
            }

            transaction {
                assertEquals(0L, queue.countOpenInvoices(supplierId = null, overdueAsOf = null, bookedFrom = null))
            }
        }
    }

    /**
     * Paging over an undeterministic order can repeat or skip a row, and every fixture invoice here
     * has a NULL `due_date` — so without an explicit NULLS position and a tiebreaker the order is
     * whatever the engine happens to return, and it differs between H2 and Postgres.
     */
    @Test
    fun `open invoices come back due-date first, undated last, then by id`() {
        testApp(setupData = { setup() }) { _ ->
            val expected =
                transaction {
                    val early =
                        AllocationFixtures.newInvoice(BigDecimal("10.0000"), "RE-EARLY").apply {
                            dueDate = LocalDateTime(2026, 1, 5, 0, 0)
                        }
                    val late =
                        AllocationFixtures.newInvoice(BigDecimal("20.0000"), "RE-LATE").apply {
                            dueDate = LocalDateTime(2026, 9, 30, 0, 0)
                        }
                    val undatedFirst = AllocationFixtures.newInvoice(BigDecimal("30.0000"), "RE-UNDATED-1")
                    val undatedSecond = AllocationFixtures.newInvoice(BigDecimal("40.0000"), "RE-UNDATED-2")
                    listOf(early, late, undatedFirst, undatedSecond).map { it.id.value }
                }

            transaction {
                assertEquals(
                    expected,
                    queue.openInvoices(supplierId = null, overdueAsOf = null, bookedFrom = null, limit = 50, offset = 0).map { it.id.value },
                )
            }
        }
    }

    @Test
    fun `an invoice due before the cutoff is overdue`() {
        testApp(setupData = { setup() }) { _ ->
            val id =
                transaction {
                    AllocationFixtures
                        .newInvoice(BigDecimal("100.0000"))
                        .apply { dueDate = LocalDateTime(2026, 3, 1, 0, 0) }
                        .id.value
                }

            transaction {
                val cutoff = LocalDateTime(2026, 3, 15, 0, 0)
                assertEquals(
                    listOf(id),
                    queue
                        .openInvoices(supplierId = null, overdueAsOf = cutoff, bookedFrom = null, limit = 50, offset = 0)
                        .map { it.id.value },
                )
                assertEquals(1L, queue.countOpenInvoices(supplierId = null, overdueAsOf = cutoff, bookedFrom = null))
            }
        }
    }

    @Test
    fun `overdue means due before the start of today - due today and later is not overdue`() {
        testApp(setupData = { setup() }) { _ ->
            val yesterday =
                transaction {
                    AllocationFixtures
                        .newInvoice(BigDecimal("1.0000"), "RE-GESTERN")
                        .apply { dueDate = LocalDateTime(2026, 3, 14, 0, 0) }
                        .id.value
                }
            transaction {
                AllocationFixtures.newInvoice(BigDecimal("1.0000"), "RE-HEUTE").apply { dueDate = LocalDateTime(2026, 3, 15, 0, 0) }
                AllocationFixtures.newInvoice(BigDecimal("1.0000"), "RE-HEUTE-ABEND").apply { dueDate = LocalDateTime(2026, 3, 15, 18, 0) }
                AllocationFixtures.newInvoice(BigDecimal("1.0000"), "RE-MORGEN").apply { dueDate = LocalDateTime(2026, 3, 16, 0, 0) }
            }

            transaction {
                // "now" is mid-afternoon on the 15th: only the invoice due on the 14th is late.
                val now = LocalDateTime(2026, 3, 15, 14, 30)
                assertEquals(1L, queue.countOpenInvoices(supplierId = null, overdueAsOf = now, bookedFrom = null))
                assertEquals(
                    listOf(yesterday),
                    queue.openInvoices(supplierId = null, overdueAsOf = now, bookedFrom = null, limit = 50, offset = 0).map { it.id.value },
                )
                assertEquals(setOf(yesterday), queue.overdueInvoiceIdsAmong(listOf(yesterday), now, null))
                // The very same instant at 00:00 on the due day still does not make it late.
                assertEquals(1L, queue.countOpenInvoices(supplierId = null, overdueAsOf = LocalDateTime(2026, 3, 15, 0, 0), bookedFrom = null))
                assertEquals(0L, queue.countOpenInvoices(supplierId = null, overdueAsOf = LocalDateTime(2026, 3, 14, 23, 59), bookedFrom = null))
                assertEquals(4L, queue.countOpenInvoices(supplierId = null, overdueAsOf = null, bookedFrom = null))
            }
        }
    }

    @Test
    fun `an invoice due after the cutoff is open but not overdue`() {
        testApp(setupData = { setup() }) { _ ->
            transaction {
                AllocationFixtures
                    .newInvoice(BigDecimal("100.0000"))
                    .apply { dueDate = LocalDateTime(2026, 4, 1, 0, 0) }
            }

            transaction {
                val cutoff = LocalDateTime(2026, 3, 15, 0, 0)
                assertEquals(1L, queue.countOpenInvoices(supplierId = null, overdueAsOf = null, bookedFrom = null))
                assertEquals(0L, queue.countOpenInvoices(supplierId = null, overdueAsOf = cutoff, bookedFrom = null))
            }
        }
    }

    @Test
    fun `an invoice with no due date is open but never overdue`() {
        testApp(setupData = { setup() }) { _ ->
            transaction { AllocationFixtures.newInvoice(BigDecimal("100.0000")) }

            transaction {
                assertEquals(1L, queue.countOpenInvoices(supplierId = null, overdueAsOf = null, bookedFrom = null))
                assertEquals(
                    0L,
                    queue.countOpenInvoices(
                        supplierId = null,
                        overdueAsOf = LocalDateTime(2099, 1, 1, 0, 0),
                        bookedFrom = null,
                    ),
                    "an unknown due date must not be reported as late",
                )
            }
        }
    }

    @Test
    fun `a paid invoice is never overdue`() {
        testApp(setupData = { setup() }) { _ ->
            transaction {
                val invoice =
                    AllocationFixtures
                        .newInvoice(BigDecimal("100.0000"))
                        .apply { dueDate = LocalDateTime(2026, 3, 1, 0, 0) }
                InvoiceBooking.new {
                    this.invoice = invoice
                    paidAt = currentTimeStamp()
                }
            }

            transaction {
                assertEquals(
                    0L,
                    queue.countOpenInvoices(
                        supplierId = null,
                        overdueAsOf = LocalDateTime(2026, 3, 15, 0, 0),
                        bookedFrom = null,
                    ),
                )
            }
        }
    }

    @Test
    fun `allocations are looked up for many transactions in one query`() {
        testApp(setupData = { setup() }) { _ ->
            val ids =
                transaction {
                    val first = AllocationFixtures.newResolvedBankTransaction(BigDecimal("100.0000"))
                    val second = AllocationFixtures.newResolvedBankTransaction(BigDecimal("70.0000"))
                    val invoice = AllocationFixtures.newInvoice(BigDecimal("30.0000"))
                    writer.allocate(
                        NewAllocation(
                            sourceKind = AllocationSourceKind.BANK_TRANSACTION,
                            targetKind = AllocationTargetKind.INVOICE,
                            amount = BigDecimal("30.0000"),
                            matchedBy = AllocationMatchedBy.MANUAL,
                            bankTransactionId = first.id.value,
                            invoiceId = invoice.id.value,
                        ),
                    )
                    listOf(first.id.value, second.id.value)
                }

            transaction {
                val byTransaction = queue.allocationsForTransactions(ids)
                assertEquals(setOf(ids.first()), byTransaction.keys)
                assertEquals(1, byTransaction.getValue(ids.first()).size)
            }
        }
    }

    @Test
    fun `an over-allocated invoice is overpaid and is no longer an open invoice`() {
        testApp(setupData = { setup() }) { _ ->
            val id =
                transaction {
                    val tx = AllocationFixtures.newResolvedBankTransaction(BigDecimal("120.0000"))
                    val invoice = AllocationFixtures.newInvoice(BigDecimal("100.0000"))
                    writer.allocate(
                        NewAllocation(
                            sourceKind = AllocationSourceKind.BANK_TRANSACTION,
                            targetKind = AllocationTargetKind.INVOICE,
                            amount = BigDecimal("120.0000"),
                            matchedBy = AllocationMatchedBy.MANUAL,
                            bankTransactionId = tx.id.value,
                            invoiceId = invoice.id.value,
                        ),
                    )
                    invoice.id.value
                }

            transaction {
                val overpaid = queue.overpaidInvoices(bookedFrom = null, limit = 50, offset = 0)
                assertEquals(listOf(id), overpaid.map { it.id.value })
                assertEquals(0, BigDecimal("-20.0000").compareTo(overpaid.single().openAmount))
                assertEquals(1L, queue.countOverpaidInvoices(bookedFrom = null))
                // The point of the bucket: it had disappeared from every other list.
                assertEquals(0L, queue.countOpenInvoices(supplierId = null, overdueAsOf = null, bookedFrom = null))
            }
        }
    }

    @Test
    fun `an invoice with a null residual is not overpaid`() {
        testApp(setupData = { setup() }) { _ ->
            transaction { AllocationFixtures.newInvoice(BigDecimal("250.0000")) }

            transaction {
                // NULL means no allocation has been computed, which is the opposite of overpaid.
                // What is pinned here is that a residual that was never computed is not an
                // overpayment — only what the allocations actually did can put an invoice in
                // this bucket.
                assertTrue(queue.overpaidInvoices(bookedFrom = null, limit = 50, offset = 0).isEmpty())
                assertEquals(0L, queue.countOverpaidInvoices(bookedFrom = null))
            }
        }
    }

    /**
     * Three rules in one fixture, because each of them ships green on its own: the bucket is ordered
     * most-overpaid-first (a NULLS/tiebreaker-free order differs between H2 and Postgres), an
     * ordinary open invoice is not in it at all, and the Buchungsbeginn drops an invoice issued
     * before the cut-off while keeping an undated one — an inverted null rule would otherwise pass.
     */
    @Test
    fun `overpaid invoices come back most overpaid first and honour the Buchungsbeginn`() {
        testApp(setupData = { setup() }) { _ ->
            val ids =
                transaction {
                    // The less overpaid invoice is inserted FIRST, so the expected order below
                    // disagrees with the insertion order rather than restating it. Note this alone
                    // does not make the assertion bite on H2: `open_amount` is indexed, so dropping
                    // the ORDER BY still returns these rows residual-ascending there. The assertion
                    // is against Postgres and against a future unindexed plan.
                    // No issue date at all: we cannot show it is out of scope, so it stays in.
                    val undated = AllocationFixtures.newInvoice(BigDecimal("100.0000"), "RE-OVERPAID-UNDATED")
                    overpay(undated, BigDecimal("120.0000"))
                    val early = AllocationFixtures.newInvoice(BigDecimal("100.0000"), "RE-OVERPAID-EARLY")
                    early.issueDate = LocalDateTime(2026, 1, 10, 0, 0)
                    overpay(early, BigDecimal("150.0000"))
                    // An ordinary open invoice — never part of this bucket.
                    AllocationFixtures.newInvoice(BigDecimal("80.0000"), "RE-STILL-OPEN")
                    early.id.value to undated.id.value
                }

            transaction {
                val all = queue.overpaidInvoices(bookedFrom = null, limit = 50, offset = 0)
                assertEquals(
                    listOf(ids.first, ids.second),
                    all.map { it.id.value },
                    "the more negative residual (-50) must lead the less negative one (-20)",
                )
                assertEquals(0, BigDecimal("-50.0000").compareTo(all.first().openAmount))
                assertEquals(0, BigDecimal("-20.0000").compareTo(all.last().openAmount))
                assertEquals(2L, queue.countOverpaidInvoices(bookedFrom = null))

                val bookedFrom = LocalDateTime(2026, 2, 1, 0, 0)
                val inScope = queue.overpaidInvoices(bookedFrom = bookedFrom, limit = 50, offset = 0)
                assertEquals(
                    listOf(ids.second),
                    inScope.map { it.id.value },
                    "the pre-cutoff invoice drops out, the undated one stays",
                )
                assertEquals(1L, queue.countOverpaidInvoices(bookedFrom = bookedFrom))
            }
        }
    }

    /** Over-allocates [invoice] through the real write path, so the residual is the one production computes. */
    private fun overpay(
        invoice: org.example.project.features.document.domain.models.Invoice,
        amount: BigDecimal,
    ) {
        val tx = AllocationFixtures.newResolvedBankTransaction(amount)
        writer.allocate(
            NewAllocation(
                sourceKind = AllocationSourceKind.BANK_TRANSACTION,
                targetKind = AllocationTargetKind.INVOICE,
                amount = amount,
                matchedBy = AllocationMatchedBy.MANUAL,
                bankTransactionId = tx.id.value,
                invoiceId = invoice.id.value,
            ),
        )
    }

    /**
     * The instalment twin of the first test in this file: nothing writes `open_amount` on insert, so
     * a NULL residual must mean the whole expectation is still open rather than that it is settled.
     */
    @Test
    fun `an instalment with a null residual is open at its expected amount`() {
        testApp(setupData = { setup() }) { _ ->
            val id = transaction { ContractFixtures.newInstalment(expected = BigDecimal("450.0000")).id.value }

            transaction {
                val open = queue.openInstalments(bookedFrom = null, limit = 50, offset = 0)
                assertEquals(listOf(id), open.map { it.id.value })
                assertEquals(null, open.single().openAmount, "nothing should have written a residual yet")
                assertEquals(0, BigDecimal("450.0000").compareTo(open.single().expectedAmount))
                assertEquals(1L, queue.countOpenInstalments(bookedFrom = null))
            }
        }
    }

    @Test
    fun `a settled instalment is not open`() {
        testApp(setupData = { setup() }) { _ ->
            transaction {
                val tx = AllocationFixtures.newResolvedBankTransaction(BigDecimal("450.0000"))
                val instalment = ContractFixtures.newInstalment(expected = BigDecimal("450.0000"))
                writer.allocate(
                    NewAllocation(
                        sourceKind = AllocationSourceKind.BANK_TRANSACTION,
                        targetKind = AllocationTargetKind.CONTRACT_INSTALMENT,
                        amount = BigDecimal("450.0000"),
                        matchedBy = AllocationMatchedBy.MANUAL,
                        bankTransactionId = tx.id.value,
                        contractInstalmentId = instalment.id.value,
                    ),
                )
            }

            transaction {
                assertTrue(queue.openInstalments(bookedFrom = null, limit = 50, offset = 0).isEmpty())
                assertEquals(0L, queue.countOpenInstalments(bookedFrom = null))
            }
        }
    }

    /**
     * The residual is deliberately left positive, so the "still open" half of the predicate passes
     * and only the `WAIVED` clause can keep the row out. A waived instalment is money the operator
     * has decided will never arrive — offering it as a target would invite an assignment that
     * contradicts that decision.
     */
    @Test
    fun `a waived instalment is not offered as a target`() {
        testApp(setupData = { setup() }) { _ ->
            transaction {
                ContractFixtures.newInstalment(expected = BigDecimal("450.0000")).apply {
                    openAmount = BigDecimal("450.0000")
                    status = ContractInstalmentStatus.WAIVED.name
                }
            }

            transaction {
                assertTrue(queue.openInstalments(bookedFrom = null, limit = 50, offset = 0).isEmpty())
                assertEquals(0L, queue.countOpenInstalments(bookedFrom = null))
            }
        }
    }

    /**
     * A variable obligation whose amount nobody has stated yet is not a figure we can offer as a
     * target: with both `expected_amount` and `open_amount` NULL there is nothing to coalesce to,
     * and treating the pair as "open" would list a debt of unknown size on the picker.
     */
    @Test
    fun `an instalment with no expected amount is not offered as a target`() {
        testApp(setupData = { setup() }) { _ ->
            transaction {
                ContractFixtures.newInstalment(expected = null).apply { openAmount = null }
            }

            transaction {
                assertTrue(queue.openInstalments(bookedFrom = null, limit = 50, offset = 0).isEmpty())
                assertEquals(0L, queue.countOpenInstalments(bookedFrom = null))
            }
        }
    }

    @Test
    fun `the Buchungsbeginn filters instalments by due date`() {
        testApp(setupData = { setup() }) { _ ->
            // Unlike the invoice list, there is no undated-row case to make an exception for:
            // `contract_instalment.due_date` is NOT NULL, so the `issue_date IS NULL` escape hatch
            // of `openInvoiceCondition` has no analogue here.
            val expected =
                transaction {
                    ContractFixtures.newInstalment(
                        expected = BigDecimal("100.0000"),
                        dueDate = LocalDateTime(2026, 1, 5, 0, 0),
                        name = "Miete Januar",
                    )
                    ContractFixtures
                        .newInstalment(
                            expected = BigDecimal("100.0000"),
                            dueDate = LocalDateTime(2026, 9, 30, 0, 0),
                            name = "Miete September",
                        ).id.value
                }

            transaction {
                val bookedFrom = LocalDateTime(2026, 2, 1, 0, 0)
                assertEquals(
                    listOf(expected),
                    queue.openInstalments(bookedFrom = bookedFrom, limit = 50, offset = 0).map { it.id.value },
                )
                assertEquals(1L, queue.countOpenInstalments(bookedFrom = bookedFrom))
                assertEquals(2L, queue.countOpenInstalments(bookedFrom = null))
            }
        }
    }

    @Test
    fun `an exactly settled invoice is neither open nor overpaid`() {
        testApp(setupData = { setup() }) { _ ->
            transaction {
                val tx = AllocationFixtures.newResolvedBankTransaction(BigDecimal("100.0000"))
                val invoice = AllocationFixtures.newInvoice(BigDecimal("100.0000"))
                writer.allocate(
                    NewAllocation(
                        sourceKind = AllocationSourceKind.BANK_TRANSACTION,
                        targetKind = AllocationTargetKind.INVOICE,
                        amount = BigDecimal("100.0000"),
                        matchedBy = AllocationMatchedBy.MANUAL,
                        bankTransactionId = tx.id.value,
                        invoiceId = invoice.id.value,
                    ),
                )
            }

            transaction {
                assertEquals(0L, queue.countOpenInvoices(supplierId = null, overdueAsOf = null, bookedFrom = null))
                assertEquals(0L, queue.countOverpaidInvoices(bookedFrom = null), "zero is settled, not overpaid")
            }
        }
    }
}
