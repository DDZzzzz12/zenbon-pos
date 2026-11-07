package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.order.OrderStatus
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.order.domain.models.Orders
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
import kotlin.test.assertEquals

/**
 * Round-3 BPOS-2/3/4:
 *  - an ordered line mirrors the goods classification (a Pfand-return line is a
 *    PfandRueckzahlung, not a hardcoded "Umsatz"), and the reversal sign lives in MENGE
 *    (STK_BR keeps the positive Grundpreis) — Tz. 4.2.5.
 *  - tender/promo/tip/kassenbuch lines carry an EMPTY INHAUS (Anhang G allows '').
 */
@DisplayName("DSFinV-K order-line GV_TYP + INHAUS (round-3 BPOS-2/3/4)")
class DsfinvkOrderLineGvTypInhausTest {
    private val service = DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink())

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    private fun export(): ByteArray =
        runBlocking {
            service.generateExport(DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"))
        }

    @Test
    fun `Pfand-return order line exports PfandRueckzahlung with sign in MENGE`() {
        var orderBonId = ""
        transaction {
            val admin = TestData.createAdmin()
            val shop = TestData.createShopInfo()
            val zBon = TestData.createZBonFullDay(shop, admin, nummer = 1L)
            val tws = TestData.createTableWithSession("OLP1")
            val session = TableSession.findById(tws.session.id)!!
            val now = currentTimeStamp()
            val product = TestData.createProduct(TestData.createCategory("Pfand Cat").id.value, "Leergut", "LEER")
            product.isDepositReturn = true
            val order =
                Orders.new {
                    this.orderNumber = "ORD-PFAND"
                    this.employee = admin
                    this.tableSession = session
                    this.orderStatus = OrderStatus.CONFIRMED
                    this.orderTime = now
                    this.orderTotal = BigDecimal("-0.25")
                    this.tseSigningStatus = TseSigningStatus.PENDING
                    this.isTraining = false
                }
            OrderItem.new {
                this.session = session
                this.order = order
                this.product = product
                this.unitPrice = BigDecimal("-0.25")
                this.finalPrice = BigDecimal("-0.25")
                this.vat = TestData.createDefaultVatRates()[0]
                this.itemStatus = ItemStatus.OPEN
                this.takeAway = false
            }
            orderBonId = "O-${order.id.value}"
        }

        val line = readRows(export(), "lines.csv").single { it["BON_ID"] == orderBonId }
        assertEquals(DsfinvkExportDto.BusinessCaseTypes.PFAND_RUECKZAHLUNG, line["GV_TYP"])
        assertEquals("-1,000", line["MENGE"], "reversal sign lives in MENGE")
        assertEquals("0,25", line["STK_BR"], "STK_BR keeps the positive Grundpreis")
    }

    @Test
    fun `tip line carries empty INHAUS`() {
        var billBonId = ""
        transaction {
            val admin = TestData.createAdmin()
            val shop = TestData.createShopInfo()
            val zBon = TestData.createZBonFullDay(shop, admin, nummer = 1L)
            val tws = TestData.createTableWithSession("OLT1")
            val session = TableSession.findById(tws.session.id)!!
            val now = currentTimeStamp()
            val bill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "TIP-1"
                    this.billAmount = BigDecimal("10.00")
                    this.paidAmount = BigDecimal("11.00")
                    this.tips = BigDecimal("1.00")
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }
            OrderItem.new {
                this.session = session
                this.product = TestData.createProduct(TestData.createCategory("Tip Cat").id.value, "Kaffee", "KAF")
                this.unitPrice = BigDecimal("10.00")
                this.finalPrice = BigDecimal("10.00")
                this.vat = TestData.createDefaultVatRates()[0]
                this.itemStatus = ItemStatus.PAID
                this.takeAway = false
                this.bill = bill
            }
            billBonId = "B-${bill.id.value}"
        }

        val tipRow =
            readRows(export(), "lines.csv")
                .single { it["BON_ID"] == billBonId && it["GV_TYP"].orEmpty().startsWith("Trinkgeld") }
        assertEquals("", tipRow["INHAUS"], "a tip is not an Inhaus/Außerhaus goods sale")
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
