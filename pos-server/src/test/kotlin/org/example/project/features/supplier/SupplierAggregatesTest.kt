package org.example.project.features.supplier

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.features.allocation.domain.models.PaymentAllocation
import org.example.project.features.document.database.tables.InvoiceBookingTable
import org.example.project.features.document.domain.models.Invoice
import org.example.project.features.supplier.data.repository.SupplierRepositoryImpl
import org.example.project.features.supplier.data.service.SupplierServiceImpl
import org.example.project.features.supplier.domain.models.Supplier
import org.example.project.features.supplier.domain.repository.SupplierSort
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * The two computed aggregates on the supplier list/detail response: open total (residual we still
 * owe) and year-to-date spend (allocated to this supplier's invoices this calendar year). Exact
 * strings, per the money rule — a one-cent drift is a real defect here.
 */
@DisplayName("Supplier spend aggregates")
class SupplierAggregatesTest {
    private val repository = SupplierRepositoryImpl()
    private val service = SupplierServiceImpl(repository, ShopConfigurationManager)

    private fun setup() {
        TestData.createShopInfo()
        TestData.createManager()
    }

    /** An invoice belonging to [supplier] with an explicit residual. */
    private fun invoiceFor(
        supplier: Supplier,
        gross: String,
        open: String?,
    ): Invoice =
        Invoice.new {
            this.supplier = supplier
            taxInclusiveTotal = BigDecimal(gross)
            openAmount = open?.let { BigDecimal(it) }
            createdAt = currentTimeStamp()
        }

    private fun allocate(
        invoice: Invoice,
        amount: String,
        at: LocalDateTime,
    ) {
        PaymentAllocation.new {
            this.invoice = invoice
            sourceKind = "BANK_TRANSACTION"
            targetKind = "INVOICE"
            this.amount = BigDecimal(amount)
            matchedBy = "MANUAL"
            createdAt = at
        }
    }

    @Test
    fun `open total sums only the positive residuals of the supplier's own invoices`() {
        testApp(setupData = { setup() }) { _ ->
            val supplierId =
                transaction {
                    val acme = repository.create(name = "Acme GmbH", vatId = "DE100000001")
                    val other = repository.create(name = "Andere GmbH", vatId = "DE100000002")
                    invoiceFor(acme, gross = "100.00", open = "100.0000") // counts
                    invoiceFor(acme, gross = "200.00", open = "0.0000") // paid off, excluded
                    invoiceFor(acme, gross = "50.00", open = null) // counts at its gross — see below
                    invoiceFor(other, gross = "999.00", open = "999.0000") // another supplier, excluded
                    acme.id.value
                }

            val dto = runBlocking { service.get(supplierId) }

            assertEquals("150.00", dto.openTotal)
        }
    }

    /**
     * The case the screen actually shows, and the one this file used to assert backwards.
     *
     * Nothing writes `open_amount` on insert, so **every** invoice the e-invoice parser creates has
     * it NULL — which is the normal path, not an edge case. This test previously expected such an
     * invoice to be excluded, so the aggregate reported €0,00 for a supplier with seven unpaid
     * invoices and the whole suite stayed green. Found by seeding real XRechnung uploads and noticing
     * the Rechnungen queue and the Lieferanten column disagreed about the same rows.
     */
    @Test
    fun `an invoice the parser created counts at its gross, because open_amount is never written on insert`() {
        testApp(setupData = { setup() }) { _ ->
            val supplierId =
                transaction {
                    val metro = repository.create(name = "Metro AG", vatId = "DE123456789")
                    invoiceFor(metro, gross = "2380.00", open = null)
                    invoiceFor(metro, gross = "1547.00", open = null)
                    metro.id.value
                }

            val dto = runBlocking { service.get(supplierId) }

            assertEquals("3927.00", dto.openTotal)
        }
    }

    /** `amount_due` sits between the two: stated by the supplier, so preferred over the gross. */
    @Test
    fun `amount due is preferred over the gross when open amount is absent`() {
        testApp(setupData = { setup() }) { _ ->
            val supplierId =
                transaction {
                    val acme = repository.create(name = "Acme GmbH", vatId = "DE100000001")
                    Invoice.new {
                        supplier = acme
                        taxInclusiveTotal = BigDecimal("500.00")
                        amountDue = BigDecimal("300.00")
                        openAmount = null
                        createdAt = currentTimeStamp()
                    }
                    acme.id.value
                }

            val dto = runBlocking { service.get(supplierId) }

            assertEquals("300.00", dto.openTotal)
        }
    }

    /**
     * Paying an invoice must take it out of the total even when no one cleared `open_amount`.
     *
     * Without the booking gate the fallback would keep reporting the full gross forever, which is
     * worse than the bug it replaces: an unpaid invoice read as zero is a figure too low, a paid one
     * read as its gross is a supplier chased for money already sent.
     */
    @Test
    fun `an invoice booked as paid drops out even with no residual written`() {
        testApp(setupData = { setup() }) { _ ->
            val supplierId =
                transaction {
                    val acme = repository.create(name = "Acme GmbH", vatId = "DE100000001")
                    val paid = invoiceFor(acme, gross = "400.00", open = null)
                    val unpaid = invoiceFor(acme, gross = "120.00", open = null)
                    InvoiceBookingTable.insert {
                        it[invoiceId] = paid.id
                        it[paidAt] = currentTimeStamp()
                    }
                    // A booking row that records no payment must NOT exclude the invoice.
                    InvoiceBookingTable.insert {
                        it[invoiceId] = unpaid.id
                        it[accountNo] = "3400"
                    }
                    acme.id.value
                }

            val dto = runBlocking { service.get(supplierId) }

            assertEquals("120.00", dto.openTotal)
        }
    }

    @Test
    fun `ytd spend sums this year's allocations to the supplier and ignores last year's`() {
        testApp(setupData = { setup() }) { _ ->
            val now = currentTimeStamp()
            val supplierId =
                transaction {
                    val acme = repository.create(name = "Acme GmbH", vatId = "DE100000001")
                    val thisYear = invoiceFor(acme, gross = "300.00", open = "100.0000")
                    val alsoThisYear = invoiceFor(acme, gross = "60.00", open = "0.0000")
                    val old = invoiceFor(acme, gross = "500.00", open = "0.0000")
                    allocate(thisYear, "200.00", LocalDateTime(now.year, 2, 1, 0, 0))
                    allocate(alsoThisYear, "60.00", LocalDateTime(now.year, 3, 1, 0, 0))
                    allocate(old, "500.00", LocalDateTime(now.year - 1, 6, 1, 0, 0)) // last year, excluded
                    acme.id.value
                }

            val dto = runBlocking { service.get(supplierId) }

            assertEquals("260.00", dto.ytdSpend)
            // Residual is unaffected by the year window: 100 open on the first invoice.
            assertEquals("100.00", dto.openTotal)
        }
    }

    @Test
    fun `a supplier with no invoices reports zero on both aggregates`() {
        testApp(setupData = { setup() }) { _ ->
            val supplierId = transaction { repository.create(name = "Leer GmbH").id.value }

            val dto = runBlocking { service.get(supplierId) }

            assertEquals("0.00", dto.openTotal)
            assertEquals("0.00", dto.ytdSpend)
        }
    }

    @Test
    fun `the list endpoint carries each supplier's own aggregates`() {
        testApp(setupData = { setup() }) { _ ->
            transaction {
                val acme = repository.create(name = "Acme GmbH", vatId = "DE100000001")
                val empty = repository.create(name = "Leer GmbH", vatId = "DE100000002")
                invoiceFor(acme, gross = "100.00", open = "100.0000")
                    .also { allocate(it, "40.00", currentTimeStamp()) }
                empty // no invoices
            }

            val list =
                runBlocking {
                    service.list(
                        provisionalOnly = false,
                        search = null,
                        sort = SupplierSort.NAME,
                        ascending = true,
                        limit = 200,
                        offset = 0,
                    )
                }

            val acme = list.items.first { it.name == "Acme GmbH" }
            val empty = list.items.first { it.name == "Leer GmbH" }
            assertEquals("100.00", acme.openTotal)
            assertEquals("40.00", acme.ytdSpend)
            assertEquals("0.00", empty.openTotal)
            assertEquals("0.00", empty.ytdSpend)
        }
    }
}
