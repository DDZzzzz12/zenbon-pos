package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.customer.domain.models.Customer
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
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
 * Round-3 BKOPF-5: when a customer is attached to the bill's table session, transactions.csv
 * must export KUNDE_NAME (full name) and KUNDE_ID (customer number). Previously KUNDE_ID was
 * always null and KUNDE_NAME carried only the first name.
 */
@DisplayName("DSFinV-K Bonkopf customer fields (round-3 BKOPF-5)")
class DsfinvkCustomerBonkopfTest {
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
    fun `attached customer exports name and number`() {
        var billId = 0L
        transaction {
            val admin = TestData.createAdmin()
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
            val customer =
                Customer.new {
                    this.customerNumber = "K-4711"
                    this.firstName = "Erika"
                    this.lastName = "Mustermann"
                    this.updatedAt = now
                }
            val tws = TestData.createTableWithSession("CUST1")
            val session = TableSession.findById(tws.session.id)!!
            session.customer = customer
            billId =
                Bill
                    .new {
                        this.employeeId = admin
                        this.tableSession = session
                        this.billNumber = "CUST-1"
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
        assertEquals("Erika Mustermann", row["KUNDE_NAME"])
        assertEquals("K-4711", row["KUNDE_ID"])
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
