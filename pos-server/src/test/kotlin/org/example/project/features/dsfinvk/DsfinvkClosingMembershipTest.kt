package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.enums.order.OrderStatus
import org.example.project.core.domain.enums.tse.TseSigningStatus
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
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.util.zip.ZipInputStream
import kotlin.test.assertTrue

/**
 * H3/H4 (audit 2026-07-13): rows must never reference Kassenabschluss 0, and Vorgänge are
 * selected by CLOSING membership, not by the calendar range — a Vorgang covered by an
 * exported closing's window is exported even if it happened before the range start; a
 * Vorgang after the last closing is excluded (it belongs to the next, not-yet-created one).
 */
@DisplayName("DSFinV-K closing membership (H3/H4)")
class DsfinvkClosingMembershipTest {
    private val service = DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink())

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    private fun admin() =
        transaction {
            org.example.project.features.employee.domain.models.Employee
                .find { org.example.project.features.employee.database.tables.EmployeeTable.username eq "admin" }
                .firstOrNull() ?: TestData.createAdmin()
        }

    private fun seedZBon(
        start: LocalDateTime,
        end: LocalDateTime,
        created: LocalDateTime,
        nummer: Long,
    ): ZBon =
        transaction {
            val admin = admin()
            val shop = ShopInfo.all().firstOrNull() ?: TestData.createShopInfo()
            ZBon.new {
                this.shopInfo = shop
                this.employee = admin
                this.startTime = start
                this.endTime = end
                this.cancelledAmount = BigDecimal.ZERO
                this.totalDiscounts = BigDecimal.ZERO
                this.totalPosRevenue = BigDecimal.ZERO
                this.adminTip = BigDecimal.ZERO
                this.openingBalance = BigDecimal.ZERO
                this.expenses = BigDecimal.ZERO
                this.cashRevenue = BigDecimal.ZERO
                this.closingBalance = BigDecimal.ZERO
                this.createdAt = created
                this.trainingRevenue = BigDecimal.ZERO
                this.trainingBillCount = 0
                this.refundAmount = BigDecimal.ZERO
                this.stornoBillCount = 0
                this.zbonNummer = nummer
            }
        }

    private fun seedBillIn(
        zBon: ZBon,
        at: LocalDateTime,
    ): Bill =
        transaction {
            val admin = admin()
            val tws = TestData.createTableWithSession("CM-${at.hour}${at.minute}")
            val session = TableSession.findById(tws.session.id)!!
            Bill.new {
                this.employeeId = admin
                this.tableSession = session
                this.billNumber = "CM-${at.hour}${at.minute}"
                this.billAmount = BigDecimal("10.00")
                this.paidAmount = BigDecimal("10.00")
                this.tips = BigDecimal.ZERO
                this.startTime = at
                this.endTime = at
                this.zBon = zBon
            }
        }

    private fun seedOrderAt(
        at: LocalDateTime,
        number: String,
    ): Orders =
        transaction {
            val admin = admin()
            val tws = TestData.createTableWithSession("CMO-$number")
            val session = TableSession.findById(tws.session.id)!!
            Orders.new {
                this.orderNumber = number
                this.employee = admin
                this.tableSession = session
                this.orderStatus = OrderStatus.CONFIRMED
                this.orderTime = at
                this.orderTotal = BigDecimal("5.00")
                this.tseSigningStatus = TseSigningStatus.PENDING
                this.isTraining = false
            }
        }

    private fun export(
        startDate: String,
        endDate: String,
    ): ByteArray =
        runBlocking {
            service.generateExport(DsfinvkExportDto.ExportRequestDto(startDate = startDate, endDate = endDate))
        }

    /** Quote-aware single-line CSV split (';' separator, '"' encapsulator). */
    private fun splitCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    cur.append('"')
                    i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ';' && !inQuotes -> {
                    out.add(cur.toString())
                    cur.clear()
                }
                else -> cur.append(c)
            }
            i++
        }
        out.add(cur.toString())
        return out
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
                    if (lines.size < 2) return emptyList()
                    val header = splitCsvLine(lines.first())
                    return lines.drop(1).map { header.zip(splitCsvLine(it)).toMap() }
                }
                entry = zis.nextEntry
            }
        }
        error("$name not found in export zip")
    }

    @Test
    fun `order after the last closing is excluded and no row carries Z_NR 0`() {
        val zBon =
            seedZBon(
                start = LocalDateTime(2026, 7, 13, 8, 0),
                end = LocalDateTime(2026, 7, 13, 18, 0),
                created = LocalDateTime(2026, 7, 13, 18, 0),
                nummer = 1L,
            )
        seedBillIn(zBon, LocalDateTime(2026, 7, 13, 12, 0))
        val late = seedOrderAt(LocalDateTime(2026, 7, 13, 20, 0), "LATE-1") // after the closing window

        val zip = export("2026-07-13", "2026-07-13")

        for (file in listOf("transactions.csv", "transactions_tse.csv", "lines.csv", "datapayment.csv")) {
            val rows = readRows(zip, file)
            assertTrue(rows.none { it["Z_NR"] == "0" }, "$file must not reference Kassenabschluss 0")
            assertTrue(
                rows.none { it["BON_ID"] == "O-${late.id.value}" },
                "$file must not contain the uncovered order O-${late.id.value}",
            )
        }
    }

    @Test
    fun `closing spanning midnight exports its pre-range orders`() {
        val zBon =
            seedZBon(
                start = LocalDateTime(2026, 7, 13, 17, 0),
                end = LocalDateTime(2026, 7, 14, 0, 30),
                created = LocalDateTime(2026, 7, 14, 0, 30), // created Tuesday → belongs to Tuesday's export
                nummer = 1L,
            )
        seedBillIn(zBon, LocalDateTime(2026, 7, 13, 23, 0))
        val mondayOrder = seedOrderAt(LocalDateTime(2026, 7, 13, 23, 50), "MID-1")

        val zip = export("2026-07-14", "2026-07-14")

        val transactions = readRows(zip, "transactions.csv")
        assertTrue(
            transactions.any { it["BON_ID"] == "O-${mondayOrder.id.value}" },
            "order inside the exported closing's window must be exported even though it predates the range start",
        )
        assertTrue(transactions.none { it["Z_NR"] == "0" })
    }
}
