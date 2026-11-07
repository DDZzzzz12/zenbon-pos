package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.payment.domain.models.Payment
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.table.domain.models.TableSession
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
import kotlin.test.assertTrue

/**
 * H5 (audit 2026-07-13): Z_NR must be the gapless, printed `zbonNummer` — never the
 * database primary key, which burns values on failed attempts and diverges from the
 * printed „ZBon Nr.".
 */
@DisplayName("DSFinV-K Z_NR sources zbonNummer (H5)")
class DsfinvkZNrSourceTest {
    private val service = DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink())

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    @Test
    fun `exported Z_NR equals zbonNummer not the primary key`() {
        transaction {
            val admin = TestData.createAdmin()
            val shop = TestData.createShopInfo()
            val cash = TestData.createPaymentType("Cash", isCash = true)
            val zBon = TestData.createZBonFullDay(shop, admin, nummer = 41L)
            val tws = TestData.createTableWithSession("ZNR1")
            val session = TableSession.findById(tws.session.id)!!
            val now = currentTimeStamp()
            val bill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "ZNR-1"
                    this.billAmount = BigDecimal("10.00")
                    this.paidAmount = BigDecimal("10.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }
            Payment.new {
                this.bill = bill
                this.paymentType = cash
                this.amount = BigDecimal("10.00")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = now
            }
        }

        val zip =
            runBlocking {
                service.generateExport(DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"))
            }

        for (file in listOf(
            "cashpointclosing.csv",
            "transactions.csv",
            "transactions_tse.csv",
            "datapayment.csv",
            "allocation_groups.csv",
        )) {
            val rows = readRows(zip, file)
            assertTrue(rows.isNotEmpty(), "$file must have rows")
            assertTrue(
                rows.all { it["Z_NR"] == "41" },
                "$file must key on zbonNummer 41, got Z_NR values ${rows.map { it["Z_NR"] }.distinct()}",
            )
        }
    }

    @Test
    fun `bill BON_NR equals the printed bill number, AV rows use their reserved ranges`() {
        var orderId = 0L
        transaction {
            val admin = TestData.createAdmin()
            val shop = TestData.createShopInfo()
            val zBon = TestData.createZBonFullDay(shop, admin, nummer = 7L)
            val tws = TestData.createTableWithSession("BNR1")
            val session = TableSession.findById(tws.session.id)!!
            val now = currentTimeStamp()
            Bill.new {
                this.employeeId = admin
                this.tableSession = session
                this.billNumber = "0123" // printed receipt number — Anhang E p. 84
                this.billAmount = BigDecimal("10.00")
                this.paidAmount = BigDecimal("10.00")
                this.tips = BigDecimal.ZERO
                this.startTime = now
                this.endTime = now
                this.zBon = zBon
            }
            orderId =
                org.example.project.features.order.domain.models.Orders
                    .new {
                        this.orderNumber = "BNR-ORD"
                        this.employee = admin
                        this.tableSession = session
                        this.orderStatus = org.example.project.core.domain.enums.order.OrderStatus.CONFIRMED
                        this.orderTime = now
                        this.orderTotal = BigDecimal("5.00")
                        this.tseSigningStatus = org.example.project.core.domain.enums.tse.TseSigningStatus.PENDING
                        this.isTraining = false
                    }.id.value
        }

        val zip =
            runBlocking {
                service.generateExport(DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"))
            }

        val rows = readRows(zip, "transactions.csv")
        val billRow = rows.single { it["BON_ID"]!!.startsWith("B-") }
        assertTrue(billRow["BON_NR"] == "123", "bill BON_NR must be the printed number, got ${billRow["BON_NR"]}")
        val orderRow = rows.single { it["BON_ID"] == "O-$orderId" }
        assertTrue(
            orderRow["BON_NR"] == (900_000_000L + orderId).toString(),
            "order BON_NR must use the 900M range, got ${orderRow["BON_NR"]}",
        )
    }

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
}
