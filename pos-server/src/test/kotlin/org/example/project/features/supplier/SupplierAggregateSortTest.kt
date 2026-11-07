package org.example.project.features.supplier

import kotlinx.datetime.LocalDateTime
import org.example.project.features.document.domain.models.Invoice
import org.example.project.features.supplier.data.repository.SupplierRepositoryImpl
import org.example.project.features.supplier.domain.models.Supplier
import org.example.project.features.supplier.domain.repository.SupplierSort
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

private val YEAR_START = LocalDateTime(2026, 1, 1, 0, 0)

/**
 * Ordering the creditor list by the two **aggregate** columns.
 *
 * These are not stored on `supplier`: the open total is a sum over that supplier's unpaid invoices,
 * and the year-to-date spend a sum over the allocations against them. Ordering by either therefore
 * has to happen in the query — sorting the fetched page would order one page and claim to have
 * ordered the list, which is the same defect the search and the other sorts avoid.
 */
class SupplierAggregateSortTest {
    private val repo = SupplierRepositoryImpl()

    private fun supplierWithOpenInvoice(
        name: String,
        open: String?,
    ): Supplier {
        val supplier = repo.create(name = name, vatId = null)
        if (open != null) {
            Invoice.new {
                this.supplier = supplier
                invoiceNumber = "RE-$name"
                openAmount = BigDecimal(open)
                createdAt = currentTimeStamp()
            }
        }
        return supplier
    }

    @Test
    fun `open total orders ascending and descending`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                supplierWithOpenInvoice("Aldi", open = "300.00")
                supplierWithOpenInvoice("Metro", open = "100.00")
                supplierWithOpenInvoice("Zeta", open = "200.00")

                assertEquals(
                    listOf("Metro", "Zeta", "Aldi"),
                    repo.list(sort = SupplierSort.OPEN_TOTAL, ascending = true, ytdSince = YEAR_START).map { it.partner.name },
                )
                assertEquals(
                    listOf("Aldi", "Zeta", "Metro"),
                    repo.list(sort = SupplierSort.OPEN_TOTAL, ascending = false, ytdSince = YEAR_START).map { it.partner.name },
                )
            }
        }
    }

    /**
     * A supplier we owe nothing sorts as zero, not as absent.
     *
     * A `SUM` over no rows is SQL NULL, and a NULL sorting to whichever end the engine prefers would
     * put the suppliers with nothing outstanding at the top of a list ordered by what is outstanding.
     */
    @Test
    fun `a supplier with no open invoice counts as zero rather than sorting arbitrarily`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                supplierWithOpenInvoice("Hat offen", open = "50.00")
                supplierWithOpenInvoice("Nichts offen", open = null)

                assertEquals(
                    listOf("Nichts offen", "Hat offen"),
                    repo.list(sort = SupplierSort.OPEN_TOTAL, ascending = true, ytdSince = YEAR_START).map { it.partner.name },
                )
                assertEquals(
                    listOf("Hat offen", "Nichts offen"),
                    repo.list(sort = SupplierSort.OPEN_TOTAL, ascending = false, ytdSince = YEAR_START).map { it.partner.name },
                )
            }
        }
    }

    /** The sum is over the supplier's invoices, so several invoices add up rather than competing. */
    @Test
    fun `several open invoices sum into one ordering key`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val many = repo.create(name = "Viele", vatId = null)
                listOf("30.00", "40.00").forEachIndexed { index, amount ->
                    Invoice.new {
                        this.supplier = many
                        invoiceNumber = "RE-viele-$index"
                        openAmount = BigDecimal(amount)
                        createdAt = currentTimeStamp()
                    }
                }
                supplierWithOpenInvoice("Einer", open = "50.00")

                assertEquals(
                    listOf("Einer", "Viele"),
                    repo.list(sort = SupplierSort.OPEN_TOTAL, ascending = true, ytdSince = YEAR_START).map { it.partner.name },
                    "70 total beats a single 50",
                )
            }
        }
    }

    /**
     * The ordering has to work on the invoices the parser actually produces.
     *
     * Every test above writes `open_amount` explicitly, and nothing on the real insert path does —
     * so all of them passed while ordering by open total ranked every supplier as NULL and left the
     * list in name order. This one states only the gross, the way `ParsedInvoiceWriter` does, and
     * fails on a query that reads `open_amount` alone.
     */
    @Test
    fun `the order holds for parsed invoices, which state only a gross`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                listOf("Aldi" to "345.80", "Metro" to "2380.00", "Zeta" to "1190.00").forEach { (name, gross) ->
                    val supplier = repo.create(name = name, vatId = null)
                    Invoice.new {
                        this.supplier = supplier
                        invoiceNumber = "RE-$name"
                        taxInclusiveTotal = BigDecimal(gross)
                        createdAt = currentTimeStamp()
                    }
                }

                assertEquals(
                    listOf("Aldi", "Zeta", "Metro"),
                    repo.list(sort = SupplierSort.OPEN_TOTAL, ascending = true, ytdSince = YEAR_START).map { it.partner.name },
                    "ordered by what is owed, not alphabetically",
                )
            }
        }
    }

    /** Ordering must not change which rows come back — it is a sort, not a filter. */
    @Test
    fun `sorting by an aggregate returns every supplier, including those with no invoices at all`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                supplierWithOpenInvoice("Mit", open = "10.00")
                repo.create(name = "Ohne", vatId = null)

                assertEquals(2, repo.list(sort = SupplierSort.OPEN_TOTAL, ytdSince = YEAR_START).size)
                assertEquals(2, repo.list(sort = SupplierSort.YTD_SPEND, ytdSince = YEAR_START).size)
            }
        }
    }

    @Test
    fun `the aggregate sorts still honour the search and the provisional filter`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                supplierWithOpenInvoice("Metro AG", open = "10.00")
                supplierWithOpenInvoice("Metro Klein", open = "20.00")
                supplierWithOpenInvoice("Aldi", open = "30.00")

                val hits =
                    repo.list(
                        search = "metro",
                        sort = SupplierSort.OPEN_TOTAL,
                        ascending = false,
                        ytdSince = YEAR_START,
                    )
                assertEquals(listOf("Metro Klein", "Metro AG"), hits.map { it.partner.name })
            }
        }
    }

    /** Paging over an aggregate order must not repeat or skip a row. */
    @Test
    fun `paging an aggregate order walks the list once`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                supplierWithOpenInvoice("A", open = "100.00")
                supplierWithOpenInvoice("B", open = "200.00")
                supplierWithOpenInvoice("C", open = "300.00")

                val first = repo.list(sort = SupplierSort.OPEN_TOTAL, ascending = true, ytdSince = YEAR_START, limit = 2)
                val second =
                    repo.list(
                        sort = SupplierSort.OPEN_TOTAL,
                        ascending = true,
                        ytdSince = YEAR_START,
                        limit = 2,
                        offset = 2,
                    )

                assertEquals(listOf("A", "B"), first.map { it.partner.name })
                assertEquals(listOf("C"), second.map { it.partner.name })
            }
        }
    }

    @Test
    fun `parse accepts the two aggregate columns`() {
        assertEquals(SupplierSort.OPEN_TOTAL, SupplierSort.parse("open_total"))
        assertEquals(SupplierSort.YTD_SPEND, SupplierSort.parse("YTD_SPEND"))
    }
}
