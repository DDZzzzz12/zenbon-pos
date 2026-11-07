package org.example.project.features.salesinvoice

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.salesinvoice.SalesInvoiceDto
import org.example.project.core.domain.enums.salesinvoice.RevenueOrigin
import org.example.project.core.domain.enums.salesinvoice.SalesInvoiceStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.features.datevexportlog.database.tables.DatevExportLogTable
import org.example.project.features.datevexportlog.domain.models.DatevExportType
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.example.project.features.salesinvoice.domain.service.SalesInvoiceDatevExportService
import org.example.project.features.salesinvoice.domain.service.SalesInvoiceService
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.util.zip.ZipInputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Task 5 of slice 4d: the DATEV Debitoren (accountsReceivableLedger) export service — it selects only
 * STANDALONE invoices, blocks the whole export on any unresolvable account (preflight gate, never a
 * partial ZIP), and records the run.
 */
@DisplayName("The DATEV AR export service")
class SalesInvoiceDatevExportServiceTest {
    private val start = "2020-01-01"
    private val end = "2100-01-01"

    private fun invoices(): SalesInvoiceService = GlobalContext.get().get()

    private fun arExport(): SalesInvoiceDatevExportService = GlobalContext.get().get()

    /** Creates + issues a STANDALONE invoice and returns its id. */
    private fun issuedStandalone(
        admin: Employee,
        buyerName: String,
        gross: String,
        rate: String = "19.00",
    ): Long {
        val id =
            runBlocking {
                invoices()
                    .createStandaloneAsDto(
                        request =
                            SalesInvoiceDto.CreateStandaloneRequestDto(
                                buyerName = buyerName,
                                serviceDate = "2026-08-18T00:00:00",
                                lines =
                                    listOf(
                                        SalesInvoiceDto.StandaloneLineInputDto(
                                            itemName = "Beratung",
                                            quantity = "1",
                                            grossUnitPrice = gross,
                                            vatRate = rate,
                                        ),
                                    ),
                            ),
                        createdBy = null,
                    ).id
            }
        runBlocking { invoices().issue(id, issuedBy = admin.id.value) }
        return id
    }

    /**
     * Makes an issued STANDALONE resolvable by writing account numbers straight onto its booking row —
     * the resolver prefers the booking value, so this needs neither a debtor nor a mapping config.
     */
    private fun makeResolvable(
        invoiceId: Long,
        personenkonto: String = "10001",
        erloeskonto: String = "8400",
    ) = transaction {
        val booking = SalesInvoice.findById(invoiceId)!!.booking.first()
        booking.bpAccountNo = personenkonto
        booking.revenueAccountNo = erloeskonto
    }

    /** Inserts a raw SalesInvoice row (used to prove POS/DRAFT rows are excluded). */
    private fun insertRaw(
        origin: RevenueOrigin,
        status: SalesInvoiceStatus,
        number: String?,
    ) = transaction {
        val shop = ShopInfo.all().first()
        SalesInvoice.new {
            this.shop = shop
            this.revenueOrigin = origin
            this.status = status
            this.invoiceNumber = number
            this.buyerName = "Raw $origin $status"
            this.issueDate = currentTimeStamp()
            this.serviceDate = currentTimeStamp()
        }
    }

    private fun zipEntryNames(bytes: ByteArray): List<String> =
        ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.map { it.name }.toList()
        }

    @Test
    fun `export selects STANDALONE and excludes POS and DRAFT`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            val admin = TestData.createAdmin(employeeNumber = "AR_A", username = "ar_a")
            val standaloneId = issuedStandalone(admin, "Kanzlei Maas", "119.00")
            makeResolvable(standaloneId)
            insertRaw(RevenueOrigin.POS_RECEIVABLE, SalesInvoiceStatus.ISSUED, "POS-1")
            insertRaw(RevenueOrigin.STANDALONE, SalesInvoiceStatus.DRAFT, null)

            val stats = runBlocking { arExport().statistics(start, end) }
            assertEquals(1, stats.totalInvoices, "only the one issued STANDALONE is selected")
            assertTrue(stats.unresolved.isEmpty(), "the STANDALONE resolves, so preflight is clean")

            val bytes = runBlocking { arExport().export(start, end, exportedBy = admin.id.value) }
            val names = zipEntryNames(bytes)
            assertTrue(names.contains("document.xml"), "the archive manifest is present, was: $names")
            assertTrue(
                names.any { it.startsWith("Rechnungsdaten_AR_") },
                "a per-invoice AR ledger is present, was: $names",
            )
        }

    @Test
    fun `an unresolvable account blocks the export`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            val admin = TestData.createAdmin(employeeNumber = "AR_B", username = "ar_b")
            // Issued STANDALONE with no debtor, no booking accounts, and no Sammeldebitor configured.
            issuedStandalone(admin, "Ohne Konto GmbH", "119.00")

            val stats = runBlocking { arExport().statistics(start, end) }
            assertEquals(1, stats.unresolved.size, "the invoice has no Personenkonto and is flagged")

            val error =
                assertFailsWith<PosException> {
                    runBlocking { arExport().export(start, end, exportedBy = admin.id.value) }
                }
            assertEquals(PosErrorCode.SALES_INVOICE_EXPORT_BLOCKED, error.code)
        }

    @Test
    fun `a successful export records a DatevExportLog row of type AR_LEDGER`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            val admin = TestData.createAdmin(employeeNumber = "AR_C", username = "ar_c")
            val standaloneId = issuedStandalone(admin, "Kanzlei Nord", "238.00")
            makeResolvable(standaloneId)

            runBlocking { arExport().export(start, end, exportedBy = admin.id.value) }

            val count =
                transaction {
                    DatevExportLogTable
                        .selectAll()
                        .where { DatevExportLogTable.exportType eq DatevExportType.AR_LEDGER.name }
                        .count()
                }
            assertEquals(1, count, "the run is recorded as an AR_LEDGER export")
        }

    /**
     * QA finding: the AR export wrote the DATEV-XML Kassen-Name ("Kasse" by default) as `clientName`,
     * while the Kassenbuch and AP exports write the shop's name. All three now share one definition.
     */
    @Test
    fun `document xml names the shop as clientName, like the other DATEV exports`() =
        testApp(setupData = { TestData.createShopInfo(shopName = "Testkasse QA") }) { _ ->
            val admin = TestData.createAdmin(employeeNumber = "AR_D", username = "ar_d")
            val standaloneId = issuedStandalone(admin, "Kanzlei Ost", "119.00")
            makeResolvable(standaloneId)

            val bytes = runBlocking { arExport().export(start, end, exportedBy = admin.id.value) }
            val documentXml = zipEntryText(bytes, "document.xml")

            assertTrue(documentXml.contains("<clientName>Testkasse QA</clientName>"), documentXml)
        }

    private fun zipEntryText(
        bytes: ByteArray,
        name: String,
    ): String =
        ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.first { it.name == name }
            zip.readBytes().decodeToString()
        }
}
