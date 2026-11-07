package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testJson
import org.jetbrains.exposed.v1.core.eq
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
 * Round-3 CONT-3/KABS-7: Z_BUCHUNGSTAG in cashpointclosing.csv is the trading-day deviation
 * marker — filled only when the closing's window START day differs from its creation day (an
 * after-midnight closing books to the previous trading day); empty for a same-day closing.
 */
@DisplayName("DSFinV-K Z_BUCHUNGSTAG trading-day deviation (round-3 CONT-3/KABS-7)")
class DsfinvkBuchungstagTest {
    private val service = DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink())

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    private fun seedZBon(
        start: LocalDateTime,
        created: LocalDateTime,
        nummer: Long,
        billStart: LocalDateTime = start,
    ): Unit =
        transaction {
            val admin =
                org.example.project.features.employee.domain.models.Employee
                    .find { org.example.project.features.employee.database.tables.EmployeeTable.username eq "admin" }
                    .firstOrNull() ?: TestData.createAdmin()
            val shop = ShopInfo.all().firstOrNull() ?: TestData.createShopInfo()
            val zBon =
                ZBon.new {
                    this.shopInfo = shop
                    this.employee = admin
                    this.startTime = start
                    this.endTime = created
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
            val tws = TestData.createTableWithSession("BT-$nummer")
            val session = TableSession.findById(tws.session.id)!!
            Bill.new {
                this.employeeId = admin
                this.tableSession = session
                this.billNumber = "BT-$nummer"
                this.billAmount = BigDecimal("10.00")
                this.paidAmount = BigDecimal("10.00")
                this.tips = BigDecimal.ZERO
                this.startTime = billStart
                this.endTime = created
                this.zBon = zBon
            }
        }

    @Test
    fun `same-day closing empty, cross-midnight closing carries the previous trading date`() {
        // nummer 1: opens and closes the same calendar day -> no deviation
        seedZBon(
            start = LocalDateTime(2026, 7, 13, 8, 0),
            created = LocalDateTime(2026, 7, 13, 18, 0),
            nummer = 1L,
        )
        // nummer 2: opened the previous day, closed after midnight -> books to 2026-07-14
        seedZBon(
            start = LocalDateTime(2026, 7, 14, 17, 0),
            created = LocalDateTime(2026, 7, 15, 0, 30),
            nummer = 2L,
        )

        val zip =
            runBlocking {
                service.generateExport(DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"))
            }
        val rows = readRows(zip, "cashpointclosing.csv").associateBy { it["Z_NR"] }
        assertEquals("", rows["1"]!!["Z_BUCHUNGSTAG"], "same-day closing must leave Z_BUCHUNGSTAG empty")
        assertEquals("2026-07-14", rows["2"]!!["Z_BUCHUNGSTAG"], "after-midnight closing books to its window-start day")
    }

    /**
     * E2E-2026-09-22 R3-2. A closing's window opens at the PREVIOUS closing's timestamp, which for a
     * shop that closes every evening is the evening before — so `startTime`'s day is yesterday on
     * every ordinary closing, and the day before a Ruhetag after one. In the production archive 136
     * of 140 closings had `start_time::date <> created_at::date`, and Z 137, created on 09.09., was
     * exported as trading day 2026-09-08 — a day that already had its own closing.
     *
     * The deviation marker must come from the closing's OWN business, i.e. its earliest Bon, not
     * from when the previous closing happened to end.
     */
    @Test
    fun `a closing whose Bons are all from its creation day carries no trading day`() {
        // The previous evening's closing: this is what the next window opens at.
        seedZBon(
            start = LocalDateTime(2026, 7, 13, 8, 0),
            created = LocalDateTime(2026, 7, 13, 21, 37),
            nummer = 1L,
        )
        // Opened by that close, but its only Bon is from the 14th — an ordinary trading day.
        seedZBon(
            start = LocalDateTime(2026, 7, 13, 21, 37),
            created = LocalDateTime(2026, 7, 14, 21, 40),
            nummer = 2L,
            billStart = LocalDateTime(2026, 7, 14, 12, 0),
        )

        val zip =
            runBlocking {
                service.generateExport(DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"))
            }
        val rows = readRows(zip, "cashpointclosing.csv").associateBy { it["Z_NR"] }
        assertEquals(
            "",
            rows["2"]!!["Z_BUCHUNGSTAG"],
            "a closing whose Bons are all from its own day must leave the deviation marker empty",
        )
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
        error("$name not found")
    }
}
