package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.payment.domain.models.Payment
import org.example.project.features.payment.domain.models.PaymentType
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
 * Closing identity (audit KABS-2/3/4 umbrella): Σ(payment.csv) == Σ(datapayment of
 * non-training Bons) == Z_SE_ZAHLUNGEN, and Σ(Bar rows) == Z_SE_BARZAHLUNGEN — by
 * construction, because payment.csv derives from the emitted datapayment rows.
 */
@DisplayName("DSFinV-K closing identity: payment.csv from datapayment rows")
class DsfinvkClosingIdentityTest {
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
    fun `payment csv equals datapayment sums and Z_SE fields, training tender excluded`() {
        transaction {
            val admin = TestData.createAdmin()
            val shop = TestData.createShopInfo()
            val zBon = TestData.createZBonFullDay(shop, admin, nummer = 1L)
            val cash = TestData.createPaymentType("Cash", isCash = true)
            val card = TestData.createPaymentType("Card", isCash = false)
            val voucher = TestData.createPaymentType("Voucher", isCash = false, isVoucher = true)
            val tws = TestData.createTableWithSession("CID1")
            val session = TableSession.findById(tws.session.id)!!
            val now = currentTimeStamp()

            fun bill(
                number: String,
                amount: String,
                training: Boolean = false,
            ): Bill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = number
                    this.billAmount = BigDecimal(amount)
                    this.paidAmount = BigDecimal(amount)
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                    this.isTraining = training
                }

            fun pay(
                bill: Bill,
                type: PaymentType,
                amount: String,
            ) {
                Payment.new {
                    this.bill = bill
                    this.paymentType = type
                    this.amount = BigDecimal(amount)
                    this.currency = "EUR"
                    this.paymentStatus = PaymentStatus.COMPLETED
                    this.paymentTime = now
                }
            }

            pay(bill("1001", "50.00"), cash, "50.00") // Bar 50
            pay(bill("1002", "80.00"), card, "80.00") // Unbar/Karte 80
            val mixed = bill("1003", "30.00")
            pay(mixed, voucher, "20.00")
            pay(mixed, cash, "10.00") // Bar 10, voucher netted
            pay(bill("1004", "25.00", training = true), cash, "25.00") // AVTraining: tender excluded from closing
        }

        val zip =
            runBlocking {
                service.generateExport(DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"))
            }

        val closing = readRows(zip, "cashpointclosing.csv").single()
        val payment = readRows(zip, "payment.csv")
        val trainingBonIds =
            readRows(zip, "transactions.csv")
                .filter { it["BON_TYP"] == "AVTraining" }
                .map { it["BON_ID"] }
                .toSet()
        val dataNonTraining = readRows(zip, "datapayment.csv").filter { it["BON_ID"] !in trainingBonIds }

        val paymentSum = payment.sumOf { de(it, "Z_ZAHLART_BETRAG") }
        val dataSum = dataNonTraining.sumOf { de(it, "BASISWAEH_BETRAG") }
        assertEquals(dataSum, paymentSum, "Σ(payment.csv) must equal Σ(datapayment) excl. training")
        assertEquals(paymentSum, de(closing["Z_SE_ZAHLUNGEN"]), "Z_SE_ZAHLUNGEN must equal Σ(payment.csv)")

        val barSum = payment.filter { it["ZAHLART_TYP"] == "Bar" }.sumOf { de(it["Z_ZAHLART_BETRAG"]) }
        assertEquals(barSum, de(closing["Z_SE_BARZAHLUNGEN"]), "Z_SE_BARZAHLUNGEN must equal Σ(Bar rows)")
        assertEquals(BigDecimal("60.00"), barSum, "training cash (25) must not count; Bar = 50 + 10")
        assertEquals(BigDecimal("140.00"), paymentSum, "voucher (20) and training (25) excluded: 50+80+10")

        assertTrue(payment.none { it["ZAHLART_TYP"] == "Keine" && de(it["Z_ZAHLART_BETRAG"]).signum() != 0 })
    }

    private fun de(s: String?) =
        BigDecimal(
            requireNotNull(s) { "missing numeric cell" }.replace(".", "").replace(',', '.'),
        )

    private fun de(
        row: Map<String, String>,
        key: String,
    ) = BigDecimal(
        requireNotNull(row[key]) { "row lacks $key: $row" }.replace(".", "").replace(',', '.'),
    )

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
