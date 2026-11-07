package org.example.project.features.salesinvoice

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.salesinvoice.RevenueOrigin
import org.example.project.core.domain.enums.salesinvoice.SalesInvoiceStatus
import org.example.project.features.allocation.data.repository.AllocationQueueRepositoryImpl
import org.example.project.features.salesinvoice.data.repository.SalesInvoiceRepositoryImpl
import org.example.project.features.salesinvoice.database.tables.SalesInvoiceTable
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * `SalesInvoiceResidual` — the single definition of what an outgoing invoice still owes.
 *
 * Two things are proven here and they are different. The first is the **cascade itself**: which of
 * `open_amount` / `amount_due` / `tax_inclusive_total` decides, and that the fallback stops at the
 * first column that is *stated*, even when what it states is zero. The second is that the two
 * callers that used to spell the predicate out by hand — `SalesInvoiceRepositoryImpl.list` and
 * `AllocationQueueRepositoryImpl.openSalesInvoices` — now return **the same set** for the same
 * fixture. That equality is the regression guard: on the creditor side the identical duplication
 * drifted and left two screens disagreeing about the same seven invoices.
 *
 * The settled-to-zero row is the load-bearing one, and it is the case a bare `open_amount > 0`
 * cannot express: that column is NULL until the allocation writer first touches the invoice, so
 * reading it alone drops every untouched invoice while the totals merely look small. It is **not**
 * a case `Coalesce(open, due, gross) > 0` gets wrong — the suite was deliberately run against that
 * form and stayed green, because SQL `COALESCE` falls through NULLs only and stops at a stated zero
 * exactly as the explicit cascade does. `InvoiceResidual`'s KDoc claims the two differ; it is wrong
 * on that one point.
 */
@DisplayName("Sales invoice residual")
class SalesInvoiceResidualTest {
    private val invoices = SalesInvoiceRepositoryImpl()
    private val queue = AllocationQueueRepositoryImpl()

    private val stamp = LocalDateTime.parse("2026-03-01T10:00")

    /**
     * Inserted through the table rather than the entity so `open_amount` and `amount_due` can each
     * be left genuinely NULL — the state every invoice is in until the allocation writer touches it.
     */
    private fun invoice(
        number: String,
        status: SalesInvoiceStatus = SalesInvoiceStatus.ISSUED,
        open: String? = null,
        due: String? = null,
        gross: String? = null,
    ): Long =
        transaction {
            SalesInvoiceTable
                .insert {
                    it[SalesInvoiceTable.shopId] = ShopInfo.all().first().id
                    it[SalesInvoiceTable.invoiceNumber] = number
                    it[SalesInvoiceTable.status] = status
                    it[SalesInvoiceTable.revenueOrigin] = RevenueOrigin.STANDALONE
                    it[SalesInvoiceTable.buyerName] = "Musterfirma GmbH"
                    it[SalesInvoiceTable.issueDate] = stamp
                    it[SalesInvoiceTable.serviceDate] = stamp
                    it[SalesInvoiceTable.createdAt] = stamp
                    it[SalesInvoiceTable.openAmount] = open?.let(::BigDecimal)
                    it[SalesInvoiceTable.amountDue] = due?.let(::BigDecimal)
                    it[SalesInvoiceTable.taxInclusiveTotal] = gross?.let(::BigDecimal)
                }[SalesInvoiceTable.id]
                .value
        }

    /** The Rechnungsübersicht's answer: `list(open = true)`, ids only. */
    private fun listOpen(): List<Long> =
        transaction {
            invoices
                .list(
                    status = null,
                    invoiceTypeCode = null,
                    open = true,
                    overdue = false,
                    debtorId = null,
                    limit = 100,
                    offset = 0,
                ).first
                .map { it.id.value }
                .sorted()
        }

    /** The Zuordnung queue's answer to the same question. */
    private fun queueOpen(): List<Long> =
        transaction {
            queue
                .openSalesInvoices(debtorId = null, overdueAsOf = null, bookedFrom = null, limit = 100, offset = 0)
                .map { it.id.value }
                .sorted()
        }

    /**
     * Every branch of the cascade in one fixture, so the assertion is over a *set* and a row that
     * wrongly qualifies is as visible as one that wrongly does not.
     */
    @Test
    fun `the cascade picks the first stated column, zero included`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val positiveResidual = invoice("AR-01", open = "119.0000", due = "119.0000", gross = "119.0000")
            // Settled: `open_amount` is stated and it is zero. The cascade must stop here.
            invoice("AR-02", open = "0.0000", due = "119.0000", gross = "119.0000")
            val fallsBackToDue = invoice("AR-03", open = null, due = "80.0000", gross = "119.0000")
            // `amount_due` stated as zero — the same rule one column further down.
            invoice("AR-04", open = null, due = "0.0000", gross = "119.0000")
            val fallsBackToGross = invoice("AR-05", open = null, due = null, gross = "119.0000")
            invoice("AR-06", open = null, due = null, gross = "0.0000")
            // Nothing stated at all: nothing tells us anything is owed, and listing an unknown as a
            // debt is worse than omitting it.
            invoice("AR-07", open = null, due = null, gross = null)

            assertEquals(
                listOf(positiveResidual, fallsBackToDue, fallsBackToGross).sorted(),
                listOpen(),
                "a stated zero closes the invoice; only a NULL falls through to the next column",
            )
        }
    }

    /**
     * Pinned on its own so a failure names the case rather than a set difference: a stated zero
     * closes the invoice, whatever the columns behind it still claim.
     */
    @Test
    fun `an invoice settled to an open amount of zero is closed, not fully unpaid`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            invoice("AR-SETTLED", open = "0.0000", due = "119.0000", gross = "119.0000")

            assertEquals(emptyList(), listOpen(), "falling through the stated zero would resurrect it at its gross")
            assertEquals(emptyList(), queueOpen())
        }
    }

    /** The lifecycle gate is a separate question from the residual, and both callers apply it. */
    @Test
    fun `a draft and a cancellation never qualify, however much they state`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            invoice("AR-DRAFT", status = SalesInvoiceStatus.DRAFT, open = "119.0000")
            invoice("AR-STORNO", status = SalesInvoiceStatus.CANCELLED, open = "119.0000")
            val issued = invoice("AR-ISSUED", open = "119.0000")

            assertEquals(listOf(issued), listOpen(), "a draft has consumed no number; a cancellation was reversed")
            assertEquals(listOf(issued), queueOpen())
        }
    }

    /**
     * The actual regression guard: the Rechnungsübersicht and the Zuordnung queue agree, row for
     * row, over a fixture that exercises every branch. Before the extraction this held only because
     * two hand-written predicates happened to be equivalent — they differed in parenthesisation, and
     * nothing checked that they stayed in step.
     */
    @Test
    fun `the list and the allocation queue return the same invoices`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            invoice("AR-01", open = "119.0000", due = "119.0000", gross = "119.0000")
            invoice("AR-02", open = "0.0000", due = "119.0000", gross = "119.0000")
            invoice("AR-03", open = null, due = "80.0000", gross = "119.0000")
            invoice("AR-04", open = null, due = "0.0000", gross = "119.0000")
            invoice("AR-05", open = null, due = null, gross = "119.0000")
            invoice("AR-06", open = null, due = null, gross = "0.0000")
            invoice("AR-07", open = null, due = null, gross = null)
            invoice("AR-08", status = SalesInvoiceStatus.DRAFT, open = "119.0000")
            invoice("AR-09", status = SalesInvoiceStatus.CANCELLED, open = null, due = null, gross = "119.0000")

            val fromList = listOpen()
            assertEquals(fromList, queueOpen(), "one definition, so the two screens cannot disagree")
            assertEquals(
                fromList.size.toLong(),
                transaction { queue.countOpenSalesInvoices(debtorId = null, overdueAsOf = null, bookedFrom = null) },
                "and the count is built from the same condition as the page",
            )
        }
    }
}
