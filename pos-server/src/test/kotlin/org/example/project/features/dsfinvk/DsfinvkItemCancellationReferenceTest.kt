package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.order.domain.models.Cancellation
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
 * Round-3 BKOPF-3/BPOS-1/CONT-4: the item-cancellation reference row (references.csv) carries
 * POS_ZEILE = "1" (the referencing Cancellation Vorgang's own single line, index.xml line 1598)
 * and resolves the cancellation's closing via the same membership pattern as the cancellation
 * transactions producer — never a fabricated Z_NR = 0.
 *
 * This branch is unreachable through the service guards (a billed item can't hold a
 * cancellation), so the fixture seeds the raw entities directly.
 */
@DisplayName("DSFinV-K item-cancellation reference (round-3 BKOPF-3/BPOS-1/CONT-4)")
class DsfinvkItemCancellationReferenceTest {
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
    fun `reference row uses POS_ZEILE 1 and never Z_NR 0`() {
        var cancellationBonId = ""
        transaction {
            val admin = TestData.createAdmin()
            val shop = TestData.createShopInfo()
            val zBon = TestData.createZBonFullDay(shop, admin, nummer = 1L)
            val tws = TestData.createTableWithSession("ICR1")
            val session = TableSession.findById(tws.session.id)!!
            val now = currentTimeStamp()
            val bill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "ICR-1"
                    this.billAmount = BigDecimal("10.00")
                    this.paidAmount = BigDecimal("10.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }
            val cat = TestData.createCategory("ICR Cat").id.value
            val vat = TestData.createDefaultVatRates()[0]
            // A first billed item so the cancelled one is NOT at Bonpos line 1 — this makes
            // POS_ZEILE="1" discriminate the referencing-Vorgang rule from the old behavior
            // (which stamped the ORIGINAL line's number, here 2).
            OrderItem.new {
                this.session = session
                this.product = TestData.createProduct(cat, "Wasser", "WASSER")
                this.unitPrice = BigDecimal("5.00")
                this.finalPrice = BigDecimal("5.00")
                this.vat = vat
                this.itemStatus = ItemStatus.PAID
                this.takeAway = false
                this.bill = bill
            }
            // A BILLED item that (contrary to the service guards) also holds a cancellation.
            val item =
                OrderItem.new {
                    this.session = session
                    this.product = TestData.createProduct(cat, "Cola", "COLA")
                    this.unitPrice = BigDecimal("10.00")
                    this.finalPrice = BigDecimal("10.00")
                    this.vat = vat
                    this.itemStatus = ItemStatus.PAID
                    this.takeAway = false
                    this.bill = bill
                }
            val cancellation =
                Cancellation.new {
                    this.orderItem = item
                    this.reason = "seeded"
                    this.employeeId = admin
                    this.createdAt = now
                    this.zBon = zBon
                    this.tseSigningStatus = TseSigningStatus.PENDING
                    this.isTraining = false
                }
            cancellationBonId = "C-${cancellation.id.value}"
        }

        val refs = readRows(export(), "references.csv").filter { it["BON_ID"] == cancellationBonId }
        assertTrue(refs.isNotEmpty(), "the item-cancellation reference row must be emitted")
        val ref = refs.single()
        assertEquals("1", ref["POS_ZEILE"], "POS_ZEILE is the referencing Cancellation's own single line")
        assertTrue(
            readRows(export(), "references.csv").none { it["Z_NR"] == "0" },
            "no reference row may carry a fabricated Z_NR 0",
        )
    }

    private fun export(): ByteArray =
        runBlocking {
            service.generateExport(DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"))
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
