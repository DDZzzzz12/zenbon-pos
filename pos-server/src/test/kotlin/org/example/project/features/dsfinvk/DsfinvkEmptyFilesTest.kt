package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.export.dsfinvk.builder.DsfinvkExportBuilder
import org.example.project.features.bill.domain.models.Bill
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * H8: `index.xml` describes 20 CSV files; every one of them must be present in the export
 * ZIP even when its underlying data is empty — as a header-only CSV, not simply absent.
 *
 * Regression coverage for `DsfinvkExportBuilder`/`*CsvExporter.generateCsv` returning
 * `ByteArray(0)` on empty input, which caused `addCsvToZip` to silently skip the entry.
 */
@DisplayName("DSFinV-K export — header-only files for empty sub-tables (H8)")
class DsfinvkEmptyFilesTest {
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
    fun `export with a minimal ZBon still contains every index-xml file, header-only when empty`() {
        transaction {
            val admin = TestData.createAdmin()
            val shop = ShopInfo.all().firstOrNull() ?: TestData.createShopInfo()
            val tws = TestData.createTableWithSession("EMPTY1")
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
            val session = TableSession.findById(tws.session.id)!!

            // Single bill, no Payment, no OrderItem, no Terminal registration, no Kassenbuch
            // entries — this leaves lines/lines_vat/itemamounts/subitems/transactions_vat/
            // datapayment/transactions_tse/businesscases/allocation_groups/references/slaves
            // all empty while still letting `generateExport` proceed (it 404s on zero Z-Bons,
            // not on zero bills/lines).
            Bill.new {
                this.employeeId = admin
                this.tableSession = session
                this.billNumber = "EMPTY-A"
                this.billAmount = BigDecimal("10.00")
                this.paidAmount = BigDecimal("10.00")
                this.tips = BigDecimal.ZERO
                this.startTime = now
                this.endTime = now
                this.zBon = zBon
            }
        }

        val zip =
            runBlocking {
                service.generateExport(
                    DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"),
                )
            }

        assertTrue(zip.isNotEmpty(), "export ZIP must not be empty")

        val entries = readZipEntryNames(zip)
        val expectedFiles = DsfinvkExportBuilder.getExportFileNames()

        val missing = expectedFiles.filterNot { fileName -> entries.any { it == "dsfinvk/$fileName" } }
        assertTrue(
            missing.isEmpty(),
            "The following DSFinV-K files described by index.xml are missing from the ZIP: $missing\n" +
                "Present entries: $entries",
        )

        // lines.csv has no OrderItem rows in this fixture — must still be present, header-only.
        val linesCsv = readZipEntry(zip, "lines.csv")
        assertNotNull(linesCsv, "lines.csv must be present even when there are no line items")
        val linesLines = linesCsv.trimEnd('\r', '\n').split("\r\n")
        assertEquals(1, linesLines.size, "lines.csv must contain exactly the header row when empty; got:\n$linesCsv")
        assertTrue(linesLines[0].startsWith("Z_KASSE_ID"), "lines.csv header row unexpected: ${linesLines[0]}")

        // subitems.csv is likewise sourced only from OrderItem sub-items — guaranteed empty here.
        val subItemsCsv = readZipEntry(zip, "subitems.csv")
        assertNotNull(subItemsCsv, "subitems.csv must be present even when there are no sub-items")
        val subItemsLines = subItemsCsv.trimEnd('\r', '\n').split("\r\n")
        assertEquals(1, subItemsLines.size, "subitems.csv must contain exactly the header row when empty; got:\n$subItemsCsv")

        // slaves.csv (Stamm_Terminals) — no Terminal was registered in this fixture.
        val slavesCsv = readZipEntry(zip, "slaves.csv")
        assertNotNull(slavesCsv, "slaves.csv must be present even when there are no registered terminals")
        val slavesLines = slavesCsv.trimEnd('\r', '\n').split("\r\n")
        assertEquals(1, slavesLines.size, "slaves.csv must contain exactly the header row when empty; got:\n$slavesCsv")
    }

    /** Reads all ZIP entry names. */
    private fun readZipEntryNames(zip: ByteArray): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                names.add(entry.name)
                entry = zis.nextEntry
            }
        }
        return names
    }

    /** Reads a single CSV entry (by filename suffix) out of the export ZIP as text, or null. */
    private fun readZipEntry(
        zip: ByteArray,
        name: String,
    ): String? {
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == "dsfinvk/$name") return zis.readBytes().decodeToString()
                entry = zis.nextEntry
            }
        }
        return null
    }
}
