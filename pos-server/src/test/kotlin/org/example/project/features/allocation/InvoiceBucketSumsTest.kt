package org.example.project.features.allocation

import kotlinx.datetime.LocalDateTime
import org.example.project.features.allocation.data.repository.AllocationQueueRepositoryImpl
import org.example.project.features.allocation.data.repository.AllocationRepositoryImpl
import org.example.project.features.allocation.data.service.AllocationWriter
import org.example.project.features.allocation.domain.models.AllocationMatchedBy
import org.example.project.features.allocation.domain.models.AllocationSourceKind
import org.example.project.features.allocation.domain.models.AllocationTargetKind
import org.example.project.features.allocation.domain.models.NewAllocation
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * What the three payment buckets are *worth*, not just how many rows they hold.
 *
 * Rechnungen shows offen / überfällig / überzahlt as counts today. A count answers "how many
 * invoices are late"; the question an operator opens the screen with is "how much money is late",
 * and the two are not the same number.
 *
 * The sums must use the same residual fallback the buckets themselves filter on — `open_amount`,
 * else `amount_due`, else `tax_inclusive_total`. Nothing writes `open_amount` on insert, so summing
 * that column alone would report freshly parsed invoices as worth nothing while listing them as
 * open.
 */
@DisplayName("Invoice bucket sums")
class InvoiceBucketSumsTest {
    private val queue = AllocationQueueRepositoryImpl()
    private val writer = AllocationWriter(AllocationRepositoryImpl())

    private fun setup() {
        TestData.createShopInfo()
        TestData.createManager()
    }

    @Test
    fun `the open sum counts a null residual at the full gross`() {
        testApp(setupData = { setup() }) { _ ->
            transaction {
                AllocationFixtures.newInvoice(BigDecimal("100.0000"), number = "RE-1")
                AllocationFixtures.newInvoice(BigDecimal("250.5000"), number = "RE-2")
            }

            transaction {
                assertEquals(
                    0,
                    BigDecimal("350.5000").compareTo(
                        queue.sumOpenInvoices(supplierId = null, overdueAsOf = null, bookedFrom = null),
                    ),
                )
            }
        }
    }

    @Test
    fun `a part payment reduces the open sum by what was allocated`() {
        testApp(setupData = { setup() }) { _ ->
            transaction {
                val tx = AllocationFixtures.newResolvedBankTransaction(BigDecimal("40.0000"))
                val invoice = AllocationFixtures.newInvoice(BigDecimal("100.0000"))
                writer.allocate(
                    NewAllocation(
                        sourceKind = AllocationSourceKind.BANK_TRANSACTION,
                        targetKind = AllocationTargetKind.INVOICE,
                        amount = BigDecimal("40.0000"),
                        matchedBy = AllocationMatchedBy.MANUAL,
                        bankTransactionId = tx.id.value,
                        invoiceId = invoice.id.value,
                    ),
                )
            }

            transaction {
                assertEquals(
                    0,
                    BigDecimal("60.0000").compareTo(
                        queue.sumOpenInvoices(supplierId = null, overdueAsOf = null, bookedFrom = null),
                    ),
                )
            }
        }
    }

    @Test
    fun `the overdue sum is a subset of the open sum, not a bucket beside it`() {
        testApp(setupData = { setup() }) { _ ->
            transaction {
                AllocationFixtures.newInvoice(BigDecimal("100.0000"), number = "spät").apply {
                    dueDate = LocalDateTime(2020, 1, 1, 0, 0)
                }
                AllocationFixtures.newInvoice(BigDecimal("70.0000"), number = "noch nicht").apply {
                    dueDate = LocalDateTime(2099, 1, 1, 0, 0)
                }
            }

            transaction {
                val open = queue.sumOpenInvoices(supplierId = null, overdueAsOf = null, bookedFrom = null)
                val overdue = queue.sumOpenInvoices(supplierId = null, overdueAsOf = currentTimeStamp(), bookedFrom = null)

                assertEquals(0, BigDecimal("170.0000").compareTo(open))
                assertEquals(0, BigDecimal("100.0000").compareTo(overdue))
            }
        }
    }

    @Test
    fun `the overdue sum counts an invoice only after its due day`() {
        testApp(setupData = { setup() }) { _ ->
            transaction {
                AllocationFixtures.newInvoice(BigDecimal("10.0000"), number = "gestern").apply {
                    dueDate = LocalDateTime(2026, 3, 14, 0, 0)
                }
                AllocationFixtures.newInvoice(BigDecimal("20.0000"), number = "heute").apply {
                    dueDate = LocalDateTime(2026, 3, 15, 0, 0)
                }
                AllocationFixtures.newInvoice(BigDecimal("40.0000"), number = "morgen").apply {
                    dueDate = LocalDateTime(2026, 3, 16, 0, 0)
                }
            }

            transaction {
                val now = LocalDateTime(2026, 3, 15, 9, 0)
                assertEquals(0, BigDecimal("10.0000").compareTo(queue.sumOpenInvoices(supplierId = null, overdueAsOf = now, bookedFrom = null)))
                assertEquals(0, BigDecimal("70.0000").compareTo(queue.sumOpenInvoices(supplierId = null, overdueAsOf = null, bookedFrom = null)))
            }
        }
    }

    /** An empty bucket is zero, never null — a tile reading "—" where it should read "0,00 €". */
    @Test
    fun `an empty bucket sums to zero rather than null`() {
        testApp(setupData = { setup() }) { _ ->
            transaction {
                assertEquals(
                    0,
                    BigDecimal.ZERO.compareTo(
                        queue.sumOpenInvoices(supplierId = null, overdueAsOf = null, bookedFrom = null),
                    ),
                )
                assertEquals(0, BigDecimal.ZERO.compareTo(queue.sumOverpaidInvoices(bookedFrom = null)))
            }
        }
    }

    /**
     * The overpaid sum is reported as the **magnitude** overpaid, not the negative residual it is
     * stored as: a tile saying „Überzahlt −20,00 €" reads as a credit, which is the opposite of
     * what it means.
     */
    @Test
    fun `the overpaid sum is positive`() {
        testApp(setupData = { setup() }) { _ ->
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
            }

            transaction {
                assertEquals(0, BigDecimal("20.0000").compareTo(queue.sumOverpaidInvoices(bookedFrom = null)))
                // And it has left the open bucket entirely, so the two tiles never double-count.
                assertEquals(
                    0,
                    BigDecimal.ZERO.compareTo(
                        queue.sumOpenInvoices(supplierId = null, overdueAsOf = null, bookedFrom = null),
                    ),
                )
            }
        }
    }
}
