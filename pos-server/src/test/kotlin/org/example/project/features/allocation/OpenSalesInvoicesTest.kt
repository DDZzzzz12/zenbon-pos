package org.example.project.features.allocation

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.salesinvoice.RevenueOrigin
import org.example.project.core.domain.enums.salesinvoice.SalesInvoiceStatus
import org.example.project.features.allocation.data.repository.AllocationQueueRepositoryImpl
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
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
 * The AR open-item picker: which STANDALONE invoices the Zuordnung editor can settle.
 *
 * The lifecycle is the gate — `sales_invoice` has no `paid_at`, so `status = ISSUED` is what makes a
 * row a live obligation, and `POS_SETTLED`/`CANCELLED` rows never qualify.
 */
@DisplayName("Open sales invoices")
class OpenSalesInvoicesTest {
    private val queue = AllocationQueueRepositoryImpl()

    private fun setup() {
        TestData.createShopInfo()
        TestData.createManager()
    }

    private var numberCounter = 0

    private fun issued(
        gross: String,
        open: String? = gross,
        status: SalesInvoiceStatus = SalesInvoiceStatus.ISSUED,
        origin: RevenueOrigin = RevenueOrigin.STANDALONE,
    ): Long =
        SalesInvoice
            .new {
                this.shop =
                    org.example.project.features.setup.domain.models.ShopInfo
                        .all()
                        .first()
                this.revenueOrigin = origin
                this.status = status
                numberCounter += 1
                this.invoiceNumber = "RG-2027-${String.format("%05d", numberCounter)}"
                this.buyerName = "Musterfirma GmbH"
                this.issueDate = currentTimeStamp()
                this.serviceDate = currentTimeStamp()
                this.taxInclusiveTotal = BigDecimal(gross)
                this.amountDue = BigDecimal(gross)
                this.openAmount = open?.let { BigDecimal(it) }
            }.id.value

    @Test
    fun `an issued STANDALONE invoice with a positive residual is listed`() {
        testApp(setupData = { setup() }) { _ ->
            val id = transaction { issued("119.0000") }

            transaction {
                val rows = queue.openSalesInvoices(debtorId = null, overdueAsOf = null, bookedFrom = null, limit = 50, offset = 0)
                assertEquals(listOf(id), rows.map { it.id.value })
                assertEquals(1L, queue.countOpenSalesInvoices(debtorId = null, overdueAsOf = null, bookedFrom = null))
            }
        }
    }

    @Test
    fun `a null residual coalesces to the amount due`() {
        testApp(setupData = { setup() }) { _ ->
            val id = transaction { issued("80.0000", open = null) }

            transaction {
                val rows = queue.openSalesInvoices(debtorId = null, overdueAsOf = null, bookedFrom = null, limit = 50, offset = 0)
                assertEquals(listOf(id), rows.map { it.id.value }, "a NULL residual means nobody computed it, not that it is settled")
            }
        }
    }

    @Test
    fun `a POS_SETTLED invoice never qualifies`() {
        testApp(setupData = { setup() }) { _ ->
            transaction { issued("80.0000", open = "0.0000", origin = RevenueOrigin.POS_SETTLED) }

            transaction {
                assertTrue(queue.openSalesInvoices(debtorId = null, overdueAsOf = null, bookedFrom = null, limit = 50, offset = 0).isEmpty())
            }
        }
    }

    @Test
    fun `a CANCELLED invoice never qualifies`() {
        testApp(setupData = { setup() }) { _ ->
            transaction { issued("80.0000", status = SalesInvoiceStatus.CANCELLED) }

            transaction {
                assertTrue(queue.openSalesInvoices(debtorId = null, overdueAsOf = null, bookedFrom = null, limit = 50, offset = 0).isEmpty())
            }
        }
    }

    @Test
    fun `a fully settled invoice leaves the open list`() {
        testApp(setupData = { setup() }) { _ ->
            val id = transaction { issued("80.0000", open = "0.0000") }

            transaction {
                assertTrue(queue.openSalesInvoices(debtorId = null, overdueAsOf = null, bookedFrom = null, limit = 50, offset = 0).none { it.id.value == id })
            }
        }
    }

    @Test
    fun `the debtorId filter narrows`() {
        testApp(setupData = { setup() }) { _ ->
            val debtorId =
                transaction {
                    org.example.project.features.debtor.domain.models.Debtor
                        .new {
                            // `partner_id` is non-null: the debtor role row needs a party.
                            this.partner =
                                org.example.project.features.partner.domain.models.BusinessPartner
                                    .new {
                                        this.shop =
                                            org.example.project.features.setup.domain.models.ShopInfo
                                                .all()
                                                .first()
                                        this.name = "Musterfirma GmbH"
                                    }
                            this.shop =
                                org.example.project.features.setup.domain.models.ShopInfo
                                    .all()
                                    .first()
                        }.id.value
                }
            val id = transaction { issued("80.0000") }

            transaction {
                val rows = queue.openSalesInvoices(debtorId = debtorId, overdueAsOf = null, bookedFrom = null, limit = 50, offset = 0)
                assertEquals(emptyList<Long>(), rows.map { it.id.value }, "no invoice is linked to this debtor yet")
                val all = queue.openSalesInvoices(debtorId = null, overdueAsOf = null, bookedFrom = null, limit = 50, offset = 0)
                assertEquals(listOf(id), all.map { it.id.value })
            }
        }
    }

    @Test
    fun `an outgoing invoice is overdue only after its due day`() {
        testApp(setupData = { setup() }) { _ ->
            val yesterday = transaction { issued("10.0000").also { dueOf(it, LocalDateTime(2026, 3, 14, 0, 0)) } }
            transaction {
                issued("10.0000").also { dueOf(it, LocalDateTime(2026, 3, 15, 0, 0)) }
                issued("10.0000").also { dueOf(it, LocalDateTime(2026, 3, 16, 0, 0)) }
            }

            transaction {
                val now = LocalDateTime(2026, 3, 15, 12, 0)
                assertEquals(1L, queue.countOpenSalesInvoices(debtorId = null, overdueAsOf = now, bookedFrom = null))
                assertEquals(
                    listOf(yesterday),
                    queue.openSalesInvoices(debtorId = null, overdueAsOf = now, bookedFrom = null, limit = 50, offset = 0).map { it.id.value },
                )
            }
        }
    }

    private fun dueOf(
        id: Long,
        due: LocalDateTime,
    ) {
        SalesInvoice.findById(id)!!.dueDate = due
    }
}
