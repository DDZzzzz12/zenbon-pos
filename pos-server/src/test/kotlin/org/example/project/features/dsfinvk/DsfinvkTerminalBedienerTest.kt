package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.terminal.domain.TerminalEmployee
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
import kotlin.test.assertNotEquals

/**
 * A login-less order station books every order against ONE shared employee row, which has no
 * `employee_info` by design — a personnel record belongs to a person, and a device is not one.
 * BEDIENER_NAME therefore fell through to that row's raw username and a station's orders exported
 * `terminal-device`: lowercase developer text in the operator column of a German fiscal document.
 *
 * BEDIENER_ID is unaffected and must stay `TERMINAL-DEVICE` — it is already in shipped exports, and
 * audit BKOPF-9 exists precisely because the employee number was once written into both fields. So
 * this pins both halves: the name is the account's own internal label, and the two stay distinct.
 */
@DisplayName("DSFinV-K Bonkopf operator fields for the shared device account")
class DsfinvkTerminalBedienerTest {
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
    fun `a bill booked by the device account exports its display name, not its username`() {
        var billId = 0L
        transaction {
            val admin = TestData.createAdmin()
            val device = TestData.createTerminalDeviceEmployee()
            val shop = ShopInfo.all().firstOrNull() ?: TestData.createShopInfo()
            val now = currentTimeStamp()
            val zBon =
                ZBon.new {
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
            val tws = TestData.createTableWithSession("STATION1")
            billId =
                Bill
                    .new {
                        this.employeeId = device
                        this.tableSession = tws.session
                        this.billNumber = "STATION-1"
                        this.billAmount = BigDecimal("10.00")
                        this.paidAmount = BigDecimal("10.00")
                        this.tips = BigDecimal.ZERO
                        this.startTime = now
                        this.endTime = now
                        this.zBon = zBon
                    }.id.value
        }

        val zip =
            runBlocking {
                service.generateExport(DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"))
            }
        val row = readRows(zip, "transactions.csv").single { it["BON_ID"] == "B-$billId" }

        assertEquals(TerminalEmployee.DISPLAY_NAME, row["BEDIENER_NAME"])
        assertEquals(TerminalEmployee.EMPLOYEE_NUMBER, row["BEDIENER_ID"])
        // BKOPF-9: the operator's name and id are different kinds of value and must not coincide.
        assertNotEquals(row["BEDIENER_ID"], row["BEDIENER_NAME"])
        // The technical username must not reach a fiscal export at all.
        assertNotEquals(TerminalEmployee.USERNAME, row["BEDIENER_NAME"])
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
