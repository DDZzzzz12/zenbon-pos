package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.minus
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.order.OrderStatus
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.order.domain.models.Orders
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.features.zBon.domain.service.ZBonService
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.util.zip.ZipInputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `orders` is the ONLY fiscal source with no `zbon_id`: `bill`, `cancellation` and
 * `kassenbuch_header` all record the Kassenabschluss they belong to, and the export resolves those
 * by the recorded link FIRST. Orders are instead attributed purely by window arithmetic —
 * `closingFor(zBons, order.orderTime)` — and `generateExport` then drops any order no exported
 * closing covers:
 *
 * ```
 * val orders = Orders.find { orderTime between closingWindowStart..closingWindowEnd }
 *     .filter { order -> closingFor(zBons, order.orderTime) != null }
 * ```
 *
 * with `closingWindowStart = zBons.minOf { startTime }` and `End = maxOf { endTime }`. So an order
 * lying in a GAP between two closings is inside the query window but covered by neither — and
 * vanishes silently.
 *
 * That gap is entirely realistic: the shop forgets to run a daily closing, or one fails. Those
 * orders were TSE-signed as `AVBestellung`, so the TSE log holds signatures the export cannot
 * explain — the same §2.1 violation class as the `AVBelegabbruch` gap.
 *
 * A recorded `zbon_id`, set when the closing is created exactly as bills and cancellations get
 * theirs, removes the guesswork: membership becomes a recorded fact instead of a derived one, and
 * it also stops a re-export re-attributing an order to a different Z_NR if closing windows change.
 */
@DisplayName("Orders record the closing they belong to (order/Z-Bon membership)")
class DsfinvkOrderClosingMembershipTest {
    private val service = DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink())

    @Test
    fun `an order in a gap between two closings is not dropped from the export`() =
        testApp(
            setupData = {
                val manager = TestData.createManager(employeeNumber = "OCM_MGR", username = "ocm_mgr")
                val shop = TestData.createShopInfo()
                val vats = TestData.createDefaultVatRates()
                val tws = TestData.createTableWithSession("OCM")
                val category = TestData.createCategory("OCM Cat")
                val product =
                    TestData.createProduct(
                        categoryId = category.id.value,
                        productName = "OCM Product",
                        productNumber = "OCM",
                        price = BigDecimal("6.0000"),
                    )

                // Two closings with a one-day hole between them — the shop failed to close on day 2.
                val now = currentTimeStamp()
                val day1 = LocalDateTime(now.year, now.month, 1, 0, 0, 0)
                val day1End = LocalDateTime(now.year, now.month, 1, 23, 59, 59)
                val day3 = LocalDateTime(now.year, now.month, 3, 0, 0, 0)
                val day3End = LocalDateTime(now.year, now.month, 3, 23, 59, 59)
                // The order sits on day 2 — inside min(day1)..max(day3End), covered by neither.
                val orphanTime = LocalDateTime(now.year, now.month, 2, 12, 0, 0)

                transaction {
                    fun closing(
                        nummer: Long,
                        from: LocalDateTime,
                        to: LocalDateTime,
                    ) = ZBon.new {
                        this.shopInfo = shop
                        this.employee = manager
                        this.startTime = from
                        this.endTime = to
                        this.cancelledAmount = BigDecimal.ZERO
                        this.totalDiscounts = BigDecimal.ZERO
                        this.totalPosRevenue = BigDecimal.ZERO
                        this.adminTip = BigDecimal.ZERO
                        this.openingBalance = BigDecimal.ZERO
                        this.expenses = BigDecimal.ZERO
                        this.cashRevenue = BigDecimal.ZERO
                        this.closingBalance = BigDecimal.ZERO
                        this.createdAt = to
                        this.trainingRevenue = BigDecimal.ZERO
                        this.trainingBillCount = 0
                        this.refundAmount = BigDecimal.ZERO
                        this.stornoBillCount = 0
                        this.zbonNummer = nummer
                    }
                    closing(1L, day1, day1End)
                    val secondClosing = closing(2L, day3, day3End)

                    val session = TableSession.findById(tws.session.id)!!
                    session.employeeId = manager
                    val order =
                        Orders.new {
                            this.tableSession = session
                            this.employee = manager
                            this.orderNumber = "OCM-ORPHAN-1"
                            this.orderTime = orphanTime
                            this.orderStatus = OrderStatus.CONFIRMED
                            this.orderTotal = BigDecimal("6.00")
                            // What ZBonServiceImpl.createReport now does: the next closing that runs claims
                            // every still-unlinked order up to its window end, including ones older than its
                            // start. Without this the order is covered by no window and vanishes.
                            this.zBon = secondClosing
                        }
                    OrderItem.new {
                        this.order = order
                        this.session = session
                        this.product = product
                        this.unitPrice = BigDecimal("6.00")
                        this.finalPrice = BigDecimal("6.00")
                        this.itemStatus = ItemStatus.OPEN
                        this.vat = vats.first()
                        this.discount = BigDecimal.ONE
                        this.customPrice = false
                        this.takeAway = false
                        this.transferred = false
                        this.sideNotes = null
                    }
                }

                val zip =
                    runBlocking {
                        service.generateExport(
                            DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"),
                        )
                    }

                val orderRow = readRows(zip, "transactions.csv").singleOrNull { it["BON_ID"]?.startsWith("O-") == true }
                assertTrue(
                    orderRow != null,
                    "the order was TSE-signed as an AVBestellung Vorgang, so it must appear in the " +
                        "export (§2.1: every TSE-secured Vorgang must be a recognisable Vorgang in the " +
                        "DSFinV-K). Before the zbon_id link, an order in a gap between closings was " +
                        "inside the query window but covered by none, and was dropped silently. " +
                        "transactions.csv held: ${readRows(zip, "transactions.csv").map { it["BON_ID"] }}",
                )
                assertEquals(
                    "2",
                    orderRow!!["Z_NR"],
                    "the Vorgang belongs to the closing that RECORDED it, not to whichever window " +
                        "happens to cover its orderTime — that is what stops a re-export re-attributing " +
                        "it to a different Kassenabschluss",
                )
            },
        ) { }

    /**
     * The other half: the closing must CLAIM a still-unlinked order, including one older than its
     * own window start. That is what rescues an order stranded by a day the shop never closed —
     * without it the recorded link would simply never be written for those rows.
     */
    @Test
    fun `createReport claims an unlinked order older than its window start`() =
        testApp(
            setupData = {
                val manager = TestData.createManager(employeeNumber = "OCM_MG2", username = "ocm_mg2")
                TestData.createShopInfo()
                val vats = TestData.createDefaultVatRates()
                val tws = TestData.createTableWithSession("OCM2")
                val category = TestData.createCategory("OCM Cat2")
                val product =
                    TestData.createProduct(
                        categoryId = category.id.value,
                        productName = "OCM Product2",
                        productNumber = "OCM2",
                        price = BigDecimal("6.0000"),
                    )
                transaction {
                    val now = currentTimeStamp()
                    Terminal.new {
                        this.deviceId = "ocm-device"
                        this.clientId = "TERM-OCM"
                        this.registeredAt = now
                        this.lastSeenAt = now
                    }
                }

                // An order from well before any closing — the stranded case.
                val orderId =
                    transaction {
                        val session = TableSession.findById(tws.session.id)!!
                        session.employeeId = manager
                        val now = currentTimeStamp()
                        val order =
                            Orders.new {
                                this.tableSession = session
                                this.employee = manager
                                this.orderNumber = "OCM-STRANDED-1"
                                this.orderTime = staleOrderTime(now)
                                this.orderStatus = OrderStatus.CONFIRMED
                                this.orderTotal = BigDecimal("6.00")
                            }
                        OrderItem.new {
                            this.order = order
                            this.session = session
                            this.product = product
                            this.unitPrice = BigDecimal("6.00")
                            this.finalPrice = BigDecimal("6.00")
                            // Settled: createReport refuses to close a table that still holds OPEN items.
                            this.itemStatus = ItemStatus.PAID
                            this.vat = vats.first()
                            this.discount = BigDecimal.ONE
                            this.customPrice = false
                            this.takeAway = false
                            this.transferred = false
                            this.sideNotes = null
                        }
                        // createReport also needs actual business to close, and refuses while a table
                        // holds a session — so settle a bill against this session and end it.
                        val nowTs = currentTimeStamp()
                        Bill.new {
                            this.employeeId = manager
                            this.tableSession = session
                            this.billNumber = "OCM-BILL-1"
                            this.billAmount = BigDecimal("6.00")
                            this.paidAmount = BigDecimal("6.00")
                            this.tips = BigDecimal.ZERO
                            this.startTime = nowTs
                            this.endTime = nowTs
                        }
                        session.end("settled for closing")
                        order.id.value
                    }

                assertTrue(
                    transaction { Orders.findById(orderId)!!.zBon == null },
                    "fixture sanity: the order starts unlinked",
                )

                runBlocking {
                    GlobalContext.get().get<ZBonService>().createReport(
                        employeeId = manager.id.value.toString(),
                        deviceId = "ocm-device",
                    )
                }

                assertTrue(
                    transaction { Orders.findById(orderId)!!.zBon != null },
                    "the closing must adopt a still-unlinked order, exactly as a late bill lands in the " +
                        "closing that finds it — otherwise a TSE-signed AVBestellung stays unattributed " +
                        "and never reaches the export",
                )
            },
        ) { }

    private fun readRows(
        zip: ByteArray,
        name: String,
    ): List<Map<String, String>> {
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name.substringAfterLast('/') == name) {
                    val lines =
                        zis
                            .readBytes()
                            .decodeToString()
                            .split("\r\n")
                            .filter { it.isNotBlank() }
                    if (lines.size < 2) return emptyList()
                    val header = lines.first().split(";").map { it.trim('"') }
                    return lines.drop(1).map { line ->
                        header.zip(line.split(";").map { it.trim('"') }).toMap()
                    }
                }
                entry = zis.nextEntry
            }
        }
        error("$name not found")
    }

    /**
     * `createReport claims an unlinked order older than its window start` needs an order
     * timestamp that is BOTH older than the fixture's TableSession (the window-start fallback,
     * see [ZBonRepositoryImpl.createReport]) AND no later than `report.endTime` — the closing's
     * own `currentTimeStamp()`, read moments after this is computed — or the claim query
     * (`OrdersTable.orderTime lessEq report.endTime`) skips the order and the test's own
     * assertion fails.
     *
     * The timestamp used to be `LocalDateTime(now.year, now.month, 1, 8, 0, 0)` — the 1st of the
     * CURRENT month at a fixed 08:00. That is only guaranteed to be in the past if the test runs
     * at or after 08:00 local time on the 1st; run it between midnight and 08:00 on the 1st (as
     * happened 2026-10-01) and the "stranded order" sits in the FUTURE relative to `now`, so it
     * is never claimed. Two days before `now`'s calendar date is in the past no matter what time
     * of day `now` is, which is all the production code requires.
     */
    private fun staleOrderTime(now: LocalDateTime): LocalDateTime = LocalDateTime(now.date.minus(DatePeriod(days = 2)), now.time)

    @Test
    fun `staleOrderTime stays in the past even at 7am on the 1st of the month`() {
        // The exact failure mode from 2026-10-01: "now" is before 08:00 on the 1st, so the old
        // fixed `LocalDateTime(now.year, now.month, 1, 8, 0, 0)` timestamp would land AFTER `now`.
        val now = LocalDateTime(2026, 10, 1, 7, 0, 0)

        val staleTime = staleOrderTime(now)

        assertTrue(staleTime < now, "a claimed order's timestamp must never be in the future: $staleTime vs $now")
    }
}
