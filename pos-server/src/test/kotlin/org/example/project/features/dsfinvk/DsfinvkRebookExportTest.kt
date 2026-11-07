package org.example.project.features.dsfinvk

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.dto.voucher.VoucherDto
import org.example.project.core.domain.enums.bill.TipRecipient
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.models.setup.VoucherSourceType
import org.example.project.core.websocket.NotificationHub
import org.example.project.features.bill.RebookFixture
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.util.zip.ZipInputStream
import kotlin.test.assertEquals

/**
 * A Zahlartwechsel (Storno + Neubuchung) end to end through the DSFinV-K export: real `createBill`,
 * real `rebookBill`, a real Z-Bon, then the CSV package. The Storno must reverse the original on
 * every surface and the Neubuchung must stand as an ordinary sale — its voucher redemption, its
 * owner tip as 19 % turnover — so the closing's business cases net to exactly one sale.
 */
@DisplayName("DSFinV-K — Zahlartwechsel export")
class DsfinvkRebookExportTest {
    private val deviceId = "dsfinvk-rebook-export-device"
    private val fixture = RebookFixture(deviceId)
    private val service = DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink())

    @AfterEach
    fun tearDown() {
        NotificationHub.resetForTest()
        BillNumberTracker.clearAll()
    }

    private fun externalRedeem(number: String) =
        VoucherDto.RedeemVoucherRequestDto(voucherNumber = number, redeemedAmount = "23.00", voucherSource = VoucherSourceType.EXTERNAL)

    private fun stornoIdOf(billId: Long): Long =
        transaction {
            Bill
                .find { BillTable.deletedBillId eq billId }
                .single()
                .id.value
        }

    /** Closes the day through the real Z-Bon route and exports it. */
    private suspend fun closeAndExport(client: HttpClient): ByteArray {
        transaction {
            exec("CREATE ALIAS IF NOT EXISTS PG_ADVISORY_XACT_LOCK AS 'long pgAdvisoryXactLock(long key) { return key; }'")
        }
        val token = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)
        val closing = client.post("/api/z-bon") { bearerAuth(token) }
        assertEquals(HttpStatusCode.Created, closing.status, "body: ${closing.bodyAsText()}")
        return service.generateExport(DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"))
    }

    private fun adminWithoutTse() {
        TestData.setTseMode(TseMode.DISABLED)
        TestData.createAdmin()
    }

    @Test
    fun `voucher A rebooked to voucher B - Storno reverses A, the Neubuchung redeems B, the closing nets to one sale`() =
        testApp(setupData = { adminWithoutTse() }) { client ->
            val sale =
                fixture.closeSale(
                    RebookFixture.SaleSpec(tender = RebookFixture.Tender.VOUCHER, voucherRedeem = listOf(externalRedeem("EXT-ZW-A"))),
                )
            val rebooked =
                fixture.rebook(
                    sale,
                    BillDto.RebookRequestDto(
                        payments = listOf(PaymentDto.PaymentRequest(amount = "23.00", paymentTypeId = sale.voucherTypeId)),
                        voucherRedeem = listOf(externalRedeem("EXT-ZW-B")),
                        tips = "0.00",
                    ),
                )
            val zip = closeAndExport(client)

            val original = "B-${sale.billId}"
            val storno = "B-${stornoIdOf(sale.billId)}"
            val neubuchung = "B-${rebooked.billId}"
            val kopf = readRows(zip, "transactions.csv").associateBy { it["BON_ID"] }
            listOf(original, storno, neubuchung).forEach { bon ->
                assertEquals("0,00", kopf.getValue(bon)["UMS_BRUTTO"], "$bon: goods net against the voucher tender")
            }

            val redemptions =
                readRows(zip, "lines.csv")
                    .filter { it["GV_TYP"] == DsfinvkExportDto.BusinessCaseTypes.MEHRZWECKGUTSCHEIN_EINLOESUNG }
                    .associate { it.getValue("BON_ID") to it.getValue("STK_BR") }
            assertEquals(
                mapOf(original to "-23,00", storno to "23,00", neubuchung to "-23,00"),
                redemptions,
                "A redeemed, A reversed by the Storno, B redeemed by the Neubuchung",
            )

            val cases = readRows(zip, "businesscases.csv")
            assertMoney("-23.00", cases.totalOf(DsfinvkExportDto.BusinessCaseTypes.MEHRZWECKGUTSCHEIN_EINLOESUNG), "one standing redemption")
            assertMoney("23.00", cases.totalOf(DsfinvkExportDto.BusinessCaseTypes.UMSATZ), "one standing sale")
        }

    @Test
    fun `owner tip added by a rebook is 19 percent turnover on the Neubuchung`() =
        testApp(setupData = { adminWithoutTse() }) { client ->
            val sale = fixture.closeSale(TipRecipient.OWNER)
            val rebooked =
                fixture.rebook(
                    sale,
                    BillDto.RebookRequestDto(
                        payments = listOf(PaymentDto.PaymentRequest(amount = "25.00", paymentTypeId = sale.cardTypeId)),
                        tips = "2.00",
                    ),
                )
            val zip = closeAndExport(client)

            val storno = "B-${stornoIdOf(sale.billId)}"
            val neubuchung = "B-${rebooked.billId}"
            val kopf = readRows(zip, "transactions.csv").associateBy { it["BON_ID"] }
            assertEquals("-23,00", kopf.getValue(storno)["UMS_BRUTTO"], "the Storno reverses the tip-less original")
            assertEquals("25,00", kopf.getValue(neubuchung)["UMS_BRUTTO"], "the Neubuchung's turnover includes the owner tip")

            val vat = readRows(zip, "transactions_vat.csv")
            val neuVat = vat.filter { it["BON_ID"] == neubuchung }
            val row19 = neuVat.single { it["UST_SCHLUESSEL"] == "1" }
            assertEquals("25,00", row19["BON_BRUTTO"], "items 23.00 + owner tip 2.00 at 19 %, got $neuVat")
            val expectedUst = BigDecimal("25.00") - BigDecimal("25.00").divide(BigDecimal("1.19"), 2, java.math.RoundingMode.HALF_UP)
            assertMoney(expectedUst.toPlainString(), parse(row19["BON_UST"]!!), "19 % USt on 25.00, derived by subtraction")
            assertEquals(1, neuVat.size, "an owner tip is no key-5 row, got $neuVat")
            assertEquals(
                "-23,00",
                vat.single { it["BON_ID"] == storno && it["UST_SCHLUESSEL"] == "1" }["BON_BRUTTO"],
                "the Storno reverses the original's 19 % row",
            )
        }

    private fun List<Map<String, String>>.totalOf(gvTyp: String): BigDecimal =
        filter { it["GV_TYP"] == gvTyp }.fold(BigDecimal.ZERO) { acc, row -> acc + parse(row["Z_UMS_BRUTTO"]!!) }

    private fun parse(amount: String): BigDecimal = BigDecimal(amount.replace(",", "."))

    private fun assertMoney(
        expected: String,
        actual: BigDecimal,
        what: String,
    ) = assertEquals(0, BigDecimal(expected).compareTo(actual), "$what: expected $expected, got ${actual.toPlainString()}")

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
