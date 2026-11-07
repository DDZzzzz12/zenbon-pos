package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.payment.domain.models.Vat
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
 * Round-3 STAM-3: KEINE_UST_ZUORDNUNG in cashregister.csv is a PER-Kassenabschluss flag —
 * set to "1" only for the closing whose own rows use VAT key 7 (UmsatzsteuerNichtErmittelbar),
 * empty for the others. It was previously blanket-stamped across every register row whenever
 * any closing in the range used key 7.
 */
@DisplayName("DSFinV-K KEINE_UST_ZUORDNUNG per closing (round-3 STAM-3)")
class DsfinvkKeineUstZuordnungPerClosingTest {
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
    fun `only the closing that used VAT key 7 is flagged`() {
        transaction {
            val admin = TestData.createAdmin()
            val shop = ShopInfo.all().firstOrNull() ?: TestData.createShopInfo()
            val now = currentTimeStamp()
            val cat = TestData.createCategory("K7 Cat")
            val vat19 = TestData.createDefaultVatRates()[0]
            // 13 % is not a configured Anlage-2 rate → getVatKey(0.13) == 7 (NICHT_ERMITTELBAR).
            val vat13 =
                Vat.findById("K7") ?: Vat.new("K7") {
                    this.vatClass = "Odd"
                    this.vatRate = BigDecimal("0.1300")
                }

            fun closing(nummer: Long) =
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
                    this.zbonNummer = nummer
                }

            fun bill(
                zBon: ZBon,
                tag: String,
                vat: Vat,
            ) {
                val tws = TestData.createTableWithSession("K7-$tag")
                val session = TableSession.findById(tws.session.id)!!
                val b =
                    Bill.new {
                        this.employeeId = admin
                        this.tableSession = session
                        this.billNumber = "K7-$tag"
                        this.billAmount = BigDecimal("10.00")
                        this.paidAmount = BigDecimal("10.00")
                        this.tips = BigDecimal.ZERO
                        this.startTime = now
                        this.endTime = now
                        this.zBon = zBon
                    }
                OrderItem.new {
                    this.session = session
                    this.product = TestData.createProduct(cat.id.value, "P-$tag", "P-$tag")
                    this.unitPrice = BigDecimal("10.00")
                    this.finalPrice = BigDecimal("10.00")
                    this.vat = vat
                    this.itemStatus = ItemStatus.PAID
                    this.takeAway = false
                    this.bill = b
                }
            }

            val z1 = closing(1L)
            val z2 = closing(2L)
            bill(z1, "A", vat19) // normal 19 % → no key 7
            bill(z2, "B", vat13) // 13 % → key 7 in closing 2 only
        }

        val zip =
            runBlocking {
                service.generateExport(DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"))
            }
        val rows = readRows(zip, "cashregister.csv").associateBy { it["Z_NR"] }
        assertEquals("", rows["1"]!!["KEINE_UST_ZUORDNUNG"], "closing 1 used no key 7")
        assertEquals("1", rows["2"]!!["KEINE_UST_ZUORDNUNG"], "closing 2 used key 7")
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
