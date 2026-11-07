package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.zBon.domain.models.ZBonPaymentSummary
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testJson
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
 * C2 (audit 2026-07-13): cash_per_currency.csv (Z_Waehrungen) is the „erfasste
 * Bargeldbestand als Summe" per currency — the Kassensturz figure — NOT a tender total
 * (Tz. 3.3.3; Anhang E Nr. 10 ZAHLART_BETRAG_WAEH „Summe des Bargeldbestandes").
 */
@DisplayName("DSFinV-K cash_per_currency = closing cash balance (C2)")
class DsfinvkCashPerCurrencyTest {
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
    fun `reports the closing cash balance, never the sum of all tenders`() {
        transaction {
            val admin = TestData.createAdmin()
            val shop = TestData.createShopInfo()
            val cash = TestData.createPaymentType("Cash", isCash = true)
            val card = TestData.createPaymentType("Card", isCash = false)
            val zBon = TestData.createZBonFullDay(shop, admin, nummer = 1L)
            // Drawer: 100 opening + 200 cash sales = 300; card sales must never count.
            zBon.closingBalance = BigDecimal("300.00")
            ZBonPaymentSummary.new {
                this.zBon = zBon
                this.currency = "EUR"
                this.paymentType = cash
                this.amount = BigDecimal("200.00")
            }
            ZBonPaymentSummary.new {
                this.zBon = zBon
                this.currency = "EUR"
                this.paymentType = card
                this.amount = BigDecimal("500.00")
            }
        }

        val zip =
            runBlocking {
                service.generateExport(DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"))
            }

        val rows = readRows(zip, "cash_per_currency.csv")
        assertEquals(1, rows.size, "single-currency system exports exactly one EUR row")
        assertEquals("EUR", rows.single()["ZAHLART_WAEH"])
        assertEquals("300,00", rows.single()["ZAHLART_BETRAG_WAEH"], "must be the drawer balance, not 700 tender total")
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
