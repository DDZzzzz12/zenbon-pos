package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.enums.order.OrderStatus
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.order.domain.models.Orders
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testJson
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.util.zip.ZipInputStream
import kotlin.test.assertEquals

/**
 * BON_START must be the first CAPTURE in the Vorgang, not the moment the table was tapped.
 *
 * Anhang E p. 85 defines BON_START as „der Zeitpunkt der ersten Erfassung innerhalb eines
 * Vorgangs" — and adds, explicitly, „entspricht nicht TSE_TA_START". So it is the till's own
 * capture time of the first order line: neither a TSE timestamp nor the moment a waiter opened
 * the table.
 *
 * The export used `bill.startTime`, which `BillRepositoryImpl` copies from
 * `tableSession.startTime`. A session is created by `getOrCreateSession`, reachable from
 * `GET /api/tables/{id}/session` — i.e. when a waiter taps a table, before anything is ordered.
 * A table opened at 18:00 whose first order is entered at 18:20 therefore claimed a Vorgang start
 * at 18:00, at which point nothing had been erfasst at all.
 *
 * This matters more than a 20-minute drift suggests: under the Tz. 2.7.2 Erleichterung this system
 * claims (per-order Bestellung-V1, Kassenbeleg-V1 opened only at payment), the whole point of the
 * relief is that an auditor can reconstruct when ordering began. BON_START is where the DSFinV-K
 * data carries that, and it was reporting table occupancy instead.
 *
 * Fixed at the export rather than by changing `bill.start_time`: the orders already carry
 * `orderTime`, so deriving it here also corrects every historical Vorgang — and a fiscal export
 * exists precisely to produce past periods on demand. `bill.startTime` keeps its own honest
 * meaning (when the table was occupied), which Statistics reads as the revenue date.
 */
@DisplayName("BON_START is the first order's capture time (M9 / Anhang E p. 85)")
class DsfinvkBonStartFirstOrderTest {
    private val service = DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink())

    @BeforeEach
    fun setup() = TestDatabase.setup()

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    @Test
    fun `BON_START is the first order, not the moment the table was opened`() {
        val tableOpened = LocalDateTime(2026, 7, 15, 18, 0, 0)
        val firstOrder = LocalDateTime(2026, 7, 15, 18, 20, 0)
        val secondOrder = LocalDateTime(2026, 7, 15, 19, 5, 0)
        val paid = LocalDateTime(2026, 7, 15, 20, 30, 0)

        var billId = 0L
        transaction {
            val admin = TestData.createAdmin()
            val shop = ShopInfo.all().firstOrNull() ?: TestData.createShopInfo()
            val now = currentTimeStamp()
            val zBon = zBon(shop, admin, now)

            val tws = TestData.createTableWithSession("BST1")
            val session = TableSession.findById(tws.session.id)!!
            session.startTime = tableOpened

            // Deliberately inserted out of order: the export must pick the EARLIEST capture,
            // not whichever row the database happens to return first.
            order(session, admin, secondOrder, "O-2")
            order(session, admin, firstOrder, "O-1")

            billId =
                Bill
                    .new {
                        this.employeeId = admin
                        this.tableSession = session
                        this.billNumber = "BST-1"
                        this.billAmount = BigDecimal("10.00")
                        this.paidAmount = BigDecimal("10.00")
                        this.tips = BigDecimal.ZERO
                        this.startTime = session.startTime
                        this.endTime = paid
                        this.zBon = zBon
                    }.id.value
        }

        val row = exportRow(billId)

        assertEquals(
            "2026-07-15T18:20:00",
            row["BON_START"],
            "BON_START is the first Erfassung within the Vorgang (Anhang E p. 85). Tapping a " +
                "table captures nothing — the first order at 18:20 does. Reporting 18:00 " +
                "describes table occupancy, not the transaction.",
        )
        assertEquals(
            "2026-07-15T20:30:00",
            row["BON_ENDE"],
            "BON_ENDE is unaffected — it is the Vorgang's completion",
        )
    }

    /**
     * A Vorgang with no orders at all (e.g. a counter voucher settled on the virtual table) still
     * needs a BON_START. Anhang E makes no allowance for an empty value.
     */
    @Test
    fun `a Vorgang without orders falls back to the bill's own start`() {
        val start = LocalDateTime(2026, 7, 16, 9, 0, 0)

        var billId = 0L
        transaction {
            val admin = TestData.createAdmin()
            val shop = ShopInfo.all().firstOrNull() ?: TestData.createShopInfo()
            val now = currentTimeStamp()
            val zBon = zBon(shop, admin, now)
            val tws = TestData.createTableWithSession("BST2")
            val session = TableSession.findById(tws.session.id)!!

            billId =
                Bill
                    .new {
                        this.employeeId = admin
                        this.tableSession = session
                        this.billNumber = "BST-2"
                        this.billAmount = BigDecimal("5.00")
                        this.paidAmount = BigDecimal("5.00")
                        this.tips = BigDecimal.ZERO
                        this.startTime = start
                        this.endTime = start
                        this.zBon = zBon
                    }.id.value
        }

        assertEquals(
            "2026-07-16T09:00:00",
            exportRow(billId)["BON_START"],
            "with nothing ordered there is no earlier capture, so the bill's own start is the " +
                "best available answer — but it must never be blank",
        )
    }

    private fun order(
        session: TableSession,
        employee: org.example.project.features.employee.domain.models.Employee,
        at: LocalDateTime,
        number: String,
    ) = Orders.new {
        this.orderNumber = number
        this.employee = employee
        this.tableSession = session
        this.orderTime = at
        this.orderTotal = BigDecimal("5.00")
        this.orderStatus = OrderStatus.COMPLETED
    }

    private fun zBon(
        shop: ShopInfo,
        admin: org.example.project.features.employee.domain.models.Employee,
        now: LocalDateTime,
    ) = ZBon.new {
        this.shopInfo = shop
        this.employee = admin
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

    private fun exportRow(billId: Long): Map<String, String> {
        val zip =
            runBlocking {
                service.generateExport(
                    DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"),
                )
            }
        return readRows(zip, "transactions.csv").single { it["BON_ID"] == "B-$billId" }
    }

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
                    val header = lines.first().split(";").map { it.trim('"') }
                    return lines.drop(1).map { line ->
                        header.zip(line.split(";").map { it.trim('"') }).toMap()
                    }
                }
                entry = zis.nextEntry
            }
        }
        return emptyList()
    }
}
