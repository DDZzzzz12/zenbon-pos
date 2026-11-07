package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.enums.bill.TipRecipient
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.order.domain.models.ItemExtras
import org.example.project.features.order.domain.models.OrderItem
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
 * H11 (audit 2026-07-13): every child row's POS_ZEILE must resolve to the lines.csv row it
 * belongs to. The old per-producer counters drifted as soon as a discount interleaved a
 * Rabatt line, an item-specific promotion folded inline, or a multi-rate owner tip
 * consumed extra numbers.
 */
@DisplayName("DSFinV-K POS_ZEILE alignment across child files (H11)")
class DsfinvkPosZeileAlignmentTest {
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
    fun `child rows point at the correct lines csv positions despite an interleaved Rabatt line`() {
        var bonId = ""
        var item2Id = 0L
        transaction {
            val admin = TestData.createAdmin()
            val shop = TestData.createShopInfo()
            val zBon = TestData.createZBonFullDay(shop, admin, nummer = 1L)
            val tws = TestData.createTableWithSession("ALGN1")
            val session = TableSession.findById(tws.session.id)!!
            val now = currentTimeStamp()
            val bill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "9001"
                    this.billAmount = BigDecimal("14.00")
                    this.paidAmount = BigDecimal("16.00")
                    this.tips = BigDecimal("2.00")
                    this.tipRecipient = TipRecipient.EMPLOYEE
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }
            bonId = "B-${bill.id.value}"
            val cat = TestData.createCategory("Algn Cat").id.value
            // item1: discounted (10 → 9) → lines.csv: item1=1, Rabatt=2
            OrderItem.new {
                this.session = session
                this.product = TestData.createProduct(cat, "Pizza", "PIZ")
                this.unitPrice = BigDecimal("10.00")
                this.finalPrice = BigDecimal("9.00")
                this.discount = BigDecimal("0.90")
                this.vat = TestData.createDefaultVatRates()[0]
                this.itemStatus = ItemStatus.PAID
                this.takeAway = false
                this.bill = bill
            }
            // item2 with a priced extra → lines.csv: item2=3; tip=4
            val item2 =
                OrderItem.new {
                    this.session = session
                    this.product = TestData.createProduct(cat, "Pasta", "PAS")
                    this.unitPrice = BigDecimal("4.00")
                    this.finalPrice = BigDecimal("5.00")
                    this.vat = TestData.createDefaultVatRates()[0]
                    this.itemStatus = ItemStatus.PAID
                    this.takeAway = false
                    this.bill = bill
                }
            item2Id = item2.id.value
            ItemExtras.new {
                this.orderItem = item2
                this.optionNameSnapshot = "Extra Käse"
                this.priceDeltaSnapshot = BigDecimal("1.00")
                this.groupNameSnapshot = "Extras"
                this.quantity = 1
            }
        }

        val zip =
            runBlocking {
                service.generateExport(DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"))
            }

        val lines = readRows(zip, "lines.csv").filter { it["BON_ID"] == bonId }.associateBy { it["POS_ZEILE"]!! }
        assertEquals(setOf("1", "2", "3", "4"), lines.keys, "item1, Rabatt, item2, tip")

        // subitems: item2's extra must attach to POS_ZEILE 3 (the old index+1 said 2 = the Rabatt line).
        val sub = readRows(zip, "subitems.csv").single { it["BON_ID"] == bonId }
        assertEquals("3", sub["POS_ZEILE"], "the extra belongs to item2's line, not the Rabatt line")
        assertEquals("Umsatz", lines.getValue(sub["POS_ZEILE"]!!)["GV_TYP"])

        // lines_vat: every row's POS_ZEILE must resolve, and the tip row sits on the tip line.
        val vats = readRows(zip, "lines_vat.csv").filter { it["BON_ID"] == bonId }
        assertTrue(vats.isNotEmpty())
        vats.forEach { v ->
            assertTrue(lines.containsKey(v["POS_ZEILE"]), "orphan lines_vat POS_ZEILE ${v["POS_ZEILE"]}")
        }
        val tipVat = vats.single { it["UST_SCHLUESSEL"] == "5" }
        assertEquals("4", tipVat["POS_ZEILE"], "tip VAT row must sit on the tip Bonpos line")
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
