package org.example.project.features.bill

import kotlinx.datetime.LocalDate
import kotlinx.datetime.atTime
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.dto.statistics.StatisticsDto
import org.example.project.core.domain.enums.bill.BillKind
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.bill.TipRecipient
import org.example.project.core.domain.enums.salesinvoice.RevenueOrigin
import org.example.project.core.domain.enums.salesinvoice.SalesInvoiceStatus
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.data.service.MapperBillSummaries
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.mapper.BillRelationLoader
import org.example.project.features.bill.mapper.toResponseDto
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.payment.database.tables.PaymentTable
import org.example.project.features.payment.domain.models.PaymentType
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.statistics.data.repository.StatisticsRepositoryImpl
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.testutils.QueryCounter
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The visible links between related bills (BillDto.BillRelationsDto), read from the foreign keys
 * that already exist: a Storno names what it reverses, the original names its Storno, a receivable
 * clearing names the bill whose invoice it settles.
 *
 * Chains are seeded exactly as BillServiceImpl writes them: a Storno points `deletedBill` at the
 * original, the original's payment is refunded onto it, and the original gets a `deletingReason`.
 */
@DisplayName("Bill relations — Stornos and originals link to each other")
class BillRelationLinksTest {
    private val repo = StatisticsRepositoryImpl(MapperBillSummaries(SalesInvoiceBillInvoiceLink()))
    private val start = LocalDate(2026, 9, 1).atTime(0, 0, 0)
    private val end = LocalDate(2026, 9, 30).atTime(23, 59, 59)
    private val at = LocalDate(2026, 9, 27).atTime(12, 0, 0)

    private lateinit var cashier: Employee
    private lateinit var session: TableSession
    private lateinit var shop: ShopInfo
    private lateinit var bar: PaymentType
    private lateinit var ec: PaymentType

    private fun bill(
        number: String,
        amount: String,
        configure: Bill.() -> Unit = {},
    ) = Bill.new {
        this.employeeId = cashier
        this.tableSession = session
        this.billNumber = number
        this.billAmount = BigDecimal(amount)
        this.paidAmount = BigDecimal(amount)
        this.startTime = at
        this.endTime = at
        this.isTraining = false
        configure()
    }

    private fun pay(
        bill: Bill,
        type: PaymentType,
        amount: String,
    ): EntityID<Long> =
        PaymentTable.insertAndGetId {
            it[billId] = bill.id
            it[paymentTypeId] = type.id
            it[this.amount] = BigDecimal(amount)
            it[paymentStatus] = PaymentStatus.COMPLETED
            it[paymentTime] = at
            it[currency] = "EUR"
        }

    private fun seedBase() {
        shop = TestData.createShopInfo()
        bar = TestData.createPaymentType(name = "BAR", isCash = true)
        ec = TestData.createPaymentType(name = "EC-KARTE", isCash = false)
        cashier = TestData.createCashier(employeeNumber = "REL", username = "rel")
        session = TestData.createTableWithSession("REL").session
    }

    /**
     * RE-0: cash → Storno (RE-S). RE-1: a standing cash sale. RE-9: a plain cash sale, invoiced and
     * cleared at the counter by RE-K.
     */
    private fun seedFamilies() {
        seedBase()
        transaction {
            val re0 = bill("RE-0", "10.60")
            val re0Cash = pay(re0, bar, "10.60")
            re0.deletingReason = "RE-S : Falsch gebucht"
            val storno = bill("RE-S", "-10.60") { deletedBill = re0 }
            PaymentTable.update({ PaymentTable.id eq re0Cash }) {
                it[refundedBillId] = storno.id
                it[paymentStatus] = PaymentStatus.REFUNDED
            }

            val re1 = bill("RE-1", "10.60")
            pay(re1, bar, "10.60")

            val re9 = bill("RE-9", "23.00")
            pay(re9, bar, "23.00")
            val invoice =
                SalesInvoice.new {
                    this.shop = this@BillRelationLinksTest.shop
                    this.revenueOrigin = RevenueOrigin.POS_RECEIVABLE
                    this.status = SalesInvoiceStatus.ISSUED
                    this.buyerName = "Catering Nord GmbH"
                    this.issueDate = at
                    this.serviceDate = at
                    this.billId = re9.id
                }
            val clearing = bill("RE-K", "23.00") { clearsSalesInvoiceId = invoice.id.value }
            pay(clearing, ec, "23.00")
        }
    }

    private fun idOf(number: String): Long =
        transaction {
            Bill
                .find { BillTable.billNumber eq number }
                .single()
                .id.value
        }

    private fun link(number: String) = BillDto.BillLinkDto(idOf(number), number)

    private fun List<PaymentDto.PaymentSummary>.render(): List<String> = map { "${it.paymentTypeDE} ${BigDecimal(it.amount).toPlainString()}" }

    @Test
    fun `each bill in a family names the bills it is linked to`() =
        testApp(setupData = { seedFamilies() }) { _ ->
            val numbers = listOf("RE-0", "RE-S", "RE-1", "RE-9", "RE-K")
            val relations = transaction { BillRelationLoader.load(numbers.map(::idOf), SalesInvoiceBillInvoiceLink()) }

            fun of(number: String) = relations.getValue(idOf(number))

            // The cancelled original: the Storno that reversed it.
            with(of("RE-0")) {
                assertEquals(BillKind.SALE, kind)
                assertTrue(isCancelled, "RE-0 was storno'd")
                assertEquals(link("RE-S"), reversedBy)
                assertNull(reversesBill)
            }
            with(of("RE-S")) {
                assertEquals(BillKind.STORNO, kind)
                assertEquals(link("RE-0"), reversesBill)
                // The Storno carries a deleting reason too, but it is not itself "cancelled".
                assertFalse(isCancelled, "a Storno bill is never flagged cancelled")
            }
            assertEquals(BillDto.BillRelationsDto(), of("RE-1"), "a standing sale carries no links")
            // A receivable clearing names the bill whose invoice it settles.
            with(of("RE-K")) {
                assertEquals(BillKind.RECEIVABLE_CLEARING, kind)
                assertEquals(link("RE-9"), clearsBill)
            }
            assertEquals(BillDto.BillRelationsDto(), of("RE-9"), "a plain sale carries no links")
        }

    @Test
    fun `the single-bill detail carries the same relations`() =
        testApp(setupData = { seedFamilies() }) { _ ->
            val original = transaction { Bill.findById(idOf("RE-0"))!!.toResponseDto(SalesInvoiceBillInvoiceLink()) }
            assertTrue(original.relations.isCancelled)
            assertEquals(link("RE-S"), original.relations.reversedBy)

            val storno = transaction { Bill.findById(idOf("RE-S"))!!.toResponseDto(SalesInvoiceBillInvoiceLink()) }
            assertEquals(BillKind.STORNO, storno.relations.kind)
            assertEquals(link("RE-0"), storno.relations.reversesBill)
        }

    @Test
    fun `the list shows each bill's own tender`() =
        testApp(setupData = { seedFamilies() }) { _ ->
            val page =
                transaction {
                    repo.getPaginatedBills(start, end, null, 0, 50, StatisticsDto.StatisticsSortOption.RECENT, false, null)
                }
            val rows = page.bills.associateBy { it.billNumber }

            assertEquals(listOf("BAR 10.60"), rows.getValue("RE-1").paymentType.render())
            // A cancelled original shows its tender, the one its Storno refunded.
            assertEquals(listOf("BAR 10.60"), rows.getValue("RE-0").paymentType.render())
            assertTrue(rows.getValue("RE-0").relations.isCancelled)
            assertEquals(listOf("BAR 10.60"), rows.getValue("RE-S").paymentType.render())
            assertEquals(link("RE-0"), rows.getValue("RE-S").relations.reversesBill)
            // An untouched sale keeps its own payment.
            assertEquals(listOf("BAR 23.00"), rows.getValue("RE-9").paymentType.render())
        }

    @Test
    fun `relations load in a fixed number of queries, whatever the page size`() =
        testApp(setupData = { seedFamilies() }) { _ ->
            val familyIds = listOf("RE-0", "RE-S", "RE-1", "RE-9", "RE-K").map(::idOf)
            // Warm up once: the first load in a fresh JVM can run one-off metadata queries, which
            // would land in whichever count runs first and make the comparison depend on test order.
            transaction { BillRelationLoader.load(familyIds, SalesInvoiceBillInvoiceLink()) }
            val smallCount = transaction { QueryCounter.count { BillRelationLoader.load(familyIds.take(2), SalesInvoiceBillInvoiceLink()) } }
            val fullCount = transaction { QueryCounter.count { BillRelationLoader.load(familyIds, SalesInvoiceBillInvoiceLink()) } }
            // own links + parent numbers + reversals + rebookings (rebooksBillId → Neubuchung,
            // added for Task 10's isRebookable/relation-link support) + cleared invoices + the
            // items' re-bill sources (one grouped query) — never one per bill.
            assertTrue(fullCount <= 6, "relation load for ${familyIds.size} bills took $fullCount queries")
            assertTrue(smallCount <= fullCount, "the count does not grow with the batch")
        }

    /** RE-4: a plain standing sale later replaced by RE-5, a Zahlartwechsel Neubuchung. */
    private fun seedRebookChain() {
        seedBase()
        transaction {
            val re4 = bill("RE-4", "12.00")
            pay(re4, bar, "12.00")
            val re5 = bill("RE-5", "12.00") { rebooksBill = re4 }
            pay(re5, ec, "12.00")
        }
    }

    @Test
    fun `a Neubuchung names the bill it replaces and the original names its Neubuchung`() =
        testApp(setupData = { seedRebookChain() }) { _ ->
            val relations = transaction { BillRelationLoader.load(listOf(idOf("RE-4"), idOf("RE-5")), SalesInvoiceBillInvoiceLink()) }

            with(relations.getValue(idOf("RE-4"))) {
                assertEquals(BillKind.SALE, kind)
                assertEquals(link("RE-5"), rebookedBy)
                assertNull(rebooksBill)
            }
            with(relations.getValue(idOf("RE-5"))) {
                assertEquals(BillKind.REBOOKING, kind)
                assertEquals(link("RE-4"), rebooksBill)
                assertNull(rebookedBy)
            }
        }

    @Test
    fun `a rebook through the service links original, Storno and Neubuchung`() =
        testApp { _ ->
            val fixture = RebookFixture("rel-rebook-device")
            try {
                val sale = fixture.closeSale(TipRecipient.EMPLOYEE)
                val rebooked =
                    fixture.rebook(
                        sale,
                        BillDto.RebookRequestDto(payments = listOf(PaymentDto.PaymentRequest(amount = "23.00", paymentTypeId = sale.cashTypeId))),
                    )

                transaction {
                    val original = Bill.findById(sale.billId)!!
                    val storno = Bill.find { BillTable.deletedBillId eq sale.billId }.single()
                    val originalLink = BillDto.BillLinkDto(original.id.value, original.billNumber)
                    val stornoLink = BillDto.BillLinkDto(storno.id.value, storno.billNumber)
                    val newLink = BillDto.BillLinkDto(rebooked.billId, rebooked.billNumber)

                    with(original.toResponseDto(SalesInvoiceBillInvoiceLink()).relations) {
                        assertTrue(isCancelled, "the original is cancelled")
                        assertEquals(stornoLink, reversedBy)
                        assertEquals(newLink, rebookedBy)
                    }
                    assertEquals(originalLink, storno.toResponseDto(SalesInvoiceBillInvoiceLink()).relations.reversesBill)
                    val neubuchung = Bill.findById(rebooked.billId)!!.toResponseDto(SalesInvoiceBillInvoiceLink())
                    assertEquals(BillKind.REBOOKING, neubuchung.relations.kind)
                    assertEquals(originalLink, neubuchung.relations.rebooksBill)
                    assertTrue(neubuchung.isRebookable, "a Neubuchung can be rebooked again")
                }
            } finally {
                BillNumberTracker.clearAll()
            }
        }

    private fun closeBusinessDay(bill: Bill) {
        transaction {
            val shopInfo = ShopInfo.all().first()
            val now = currentTimeStamp()
            val zBon =
                ZBon.new {
                    this.shopInfo = shopInfo
                    this.employee = bill.employeeId
                    this.startTime = now
                    this.endTime = now
                    this.cancelledAmount = BigDecimal.ZERO
                    this.totalDiscounts = BigDecimal.ZERO
                    this.totalPosRevenue = BigDecimal.ZERO
                    this.adminTip = BigDecimal.ZERO
                    this.openingBalance = BigDecimal.ZERO
                    this.expenses = BigDecimal.ZERO
                    this.cashRevenue = BigDecimal.ZERO
                    this.closingBalance = BigDecimal.ZERO
                    this.createdAt = now
                    this.trainingRevenue = BigDecimal.ZERO
                    this.trainingBillCount = 0
                    this.refundAmount = BigDecimal.ZERO
                    this.stornoBillCount = 0
                    this.zbonNummer = 1L
                }
            bill.zBon = zBon
        }
    }

    @Test
    fun `isRebookable is true for a plain standing sale, false for a Storno and false once the day is closed`() =
        testApp(setupData = { seedFamilies() }) { _ ->
            val plainSale = transaction { Bill.findById(idOf("RE-1"))!!.toResponseDto(SalesInvoiceBillInvoiceLink()) }
            assertTrue(plainSale.isRebookable, "a plain standing sale is rebookable")

            val storno = transaction { Bill.findById(idOf("RE-S"))!!.toResponseDto(SalesInvoiceBillInvoiceLink()) }
            assertFalse(storno.isRebookable, "a Storno bill is not rebookable")

            val closedDayBillId = idOf("RE-1")
            transaction { closeBusinessDay(Bill.findById(closedDayBillId)!!) }
            val onClosedDay = transaction { Bill.findById(closedDayBillId)!!.toResponseDto(SalesInvoiceBillInvoiceLink()) }
            assertFalse(onClosedDay.isRebookable, "a bill on a closed day is not rebookable")
        }
}
