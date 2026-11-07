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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * C1 (audit 2026-07-13): a Mehrzweckgutschein redemption is a negative key-5
 * Geschäftsvorfall, NOT a tender (official example V_2_3_02 Bsp. 2.2; Anhang D „Keine").
 * UMS_BRUTTO nets the redemption, transactions_vat carries the negative key-5 row, and
 * datapayment shows only real tenders (or a single „Keine"/0 row when fully voucher-paid).
 */
@DisplayName("DSFinV-K Mehrzweckgutschein redemption model (C1)")
class DsfinvkVoucherRedemptionModelTest {
    private val service = DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink())

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    private fun seedBill(
        gross: String,
        voucher: String?,
        cash: String?,
    ): Long {
        var billId = 0L
        transaction {
            val admin = TestData.createAdmin()
            val shop = TestData.createShopInfo()
            val zBon = TestData.createZBonFullDay(shop, admin, nummer = 1L)
            val cashType = TestData.createPaymentType("Cash", isCash = true)
            val voucherType = TestData.createPaymentType("Voucher", isCash = false, isVoucher = true)
            val tws = TestData.createTableWithSession("VRM1")
            val session = TableSession.findById(tws.session.id)!!
            val now = currentTimeStamp()
            val bill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "4711"
                    this.billAmount = BigDecimal(gross)
                    this.paidAmount = BigDecimal(gross)
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }
            billId = bill.id.value
            if (voucher != null) {
                Payment.new {
                    this.bill = bill
                    this.paymentType = voucherType
                    this.amount = BigDecimal(voucher)
                    this.currency = "EUR"
                    this.paymentStatus = PaymentStatus.COMPLETED
                    this.paymentTime = now
                }
            }
            if (cash != null) {
                Payment.new {
                    this.bill = bill
                    this.paymentType = cashType
                    this.amount = BigDecimal(cash)
                    this.currency = "EUR"
                    this.paymentStatus = PaymentStatus.COMPLETED
                    this.paymentTime = now
                }
            }
        }
        return billId
    }

    private fun export(): ByteArray =
        runBlocking {
            service.generateExport(DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"))
        }

    @Test
    fun `fully voucher-paid bill nets UMS_BRUTTO, emits negative key-5 vat row and Keine 0 tender`() {
        val billId = seedBill(gross = "50.00", voucher = "50.00", cash = null)
        val zip = export()

        val kopf = readRows(zip, "transactions.csv").single { it["BON_ID"] == "B-$billId" }
        assertEquals("0,00", kopf["UMS_BRUTTO"], "UMS_BRUTTO must net the redemption")

        val vat = readRows(zip, "transactions_vat.csv").filter { it["BON_ID"] == "B-$billId" }
        val key5 = vat.filter { it["UST_SCHLUESSEL"] == "5" }
        assertTrue(
            key5.any { it["BON_BRUTTO"] == "-50,00" },
            "transactions_vat must carry the negative key-5 redemption row, got $vat",
        )

        val pay = readRows(zip, "datapayment.csv").filter { it["BON_ID"] == "B-$billId" }
        assertEquals(1, pay.size, "fully voucher-paid bill has exactly one tender row, got $pay")
        assertEquals("Keine", pay.single()["ZAHLART_TYP"])
        assertEquals("0,00", pay.single()["BASISWAEH_BETRAG"])
    }

    @Test
    fun `partial voucher plus cash exports only the cash tender and nets UMS_BRUTTO`() {
        val billId = seedBill(gross = "120.00", voucher = "100.00", cash = "20.00")
        val zip = export()

        val kopf = readRows(zip, "transactions.csv").single { it["BON_ID"] == "B-$billId" }
        assertEquals("20,00", kopf["UMS_BRUTTO"])

        val pay = readRows(zip, "datapayment.csv").filter { it["BON_ID"] == "B-$billId" }
        assertEquals(listOf("Bar"), pay.map { it["ZAHLART_TYP"] })
        assertEquals("20,00", pay.single()["BASISWAEH_BETRAG"])
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
