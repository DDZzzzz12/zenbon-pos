package org.example.project.features.kassenbuch

import kotlinx.datetime.LocalDateTime
import org.example.project.features.document.data.repository.DocumentRepositoryImpl
import org.example.project.features.document.data.service.InvoiceBackfill
import org.example.project.features.kassenbuch.domain.service.DatevApExportService
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the AP export path that `DatevApExportModelMigrationTest`'s golden fixtures do **not**
 * reach: an invoice with no `invoice_vat_breakdown` rows.
 *
 * That gap is not academic. Both golden fixtures carry breakdown rows and so exercise only the
 * per-row branch, while nothing in production writes breakdown rows — every real invoice reaches
 * the export through [InvoiceBackfill]. A migration that dropped the legacy `tax_rate` would
 * therefore have removed `<tax>` from 100 % of exported AP ledger entries while the golden file
 * stayed green. The second test below is the assertion that pins the fix.
 */
class DatevApExportFallbackTest {
    private companion object {
        const val START_DATE = "2026-03-01"
        const val END_DATE = "2026-03-31"
    }

    private lateinit var adminId: UUID
    private val repo = DocumentRepositoryImpl()

    /** Creates the INVOICE document an invoice hangs off. The file itself never has to exist. */
    private fun invoiceDocument(filename: String): Long =
        repo
            .create(
                filename = filename,
                fileType = "INVOICE",
                filePath = "/nonexistent/$filename",
                fileSize = 1024L,
                uploadedAt = currentTimeStamp(),
                uploadedBy = adminId,
                contentHash = "hash-${System.nanoTime()}",
                mediaType = "application/pdf",
            ).id.value

    @Test
    fun `an invoice with no VAT breakdown still exports its total and emits no tax`() {
        testApp(
            setupData = {
                TestData.createShopInfo()
                adminId = TestData.createAdmin().id.value

                transaction {
                    repo.createInvoice(
                        sourceDocumentId = invoiceDocument("invoice-no-breakdown.pdf"),
                        invoiceNumber = "INV-NOBREAKDOWN-0001",
                        issueDate = LocalDateTime(2026, 3, 10, 0, 0),
                        currency = "EUR",
                        sellerName = "Ohne Steuerangabe GmbH",
                        taxInclusiveTotal = BigDecimal("238.00"),
                    )
                }
            },
        ) { _ ->
            val xml = exportLedgerXml()

            assertTrue(
                xml.contains("<amount>238.00</amount>"),
                "the fallback must still emit the invoice total as the entry amount; got:\n$xml",
            )
            assertFalse(
                xml.contains("<tax>"),
                "an invoice with no VAT breakdown has no rate to emit; got:\n$xml",
            )
        }
    }

    @Test
    fun `a backfilled legacy invoice still emits its tax rate`() {
        testApp(
            setupData = {
                TestData.createShopInfo()
                adminId = TestData.createAdmin().id.value

                transaction {
                    repo.updateInvoiceMetadata(
                        documentId = invoiceDocument("invoice-legacy.pdf"),
                        vendorName = "Alte Welt AG",
                        invoiceNumber = "INV-LEGACY-0001",
                        invoiceDate = LocalDateTime(2026, 3, 12, 0, 0),
                        grossAmount = BigDecimal("238.00"),
                        taxRate = BigDecimal("19.00"),
                        currency = "EUR",
                    )
                    InvoiceBackfill(repo).run()
                }
            },
        ) { _ ->
            val xml = exportLedgerXml()

            assertTrue(
                xml.contains("<tax>19.00</tax>"),
                "the legacy header tax rate must survive as a synthesized VAT breakdown row; got:\n$xml",
            )
            assertTrue(
                xml.contains("<amount>238.00</amount>"),
                "taxable + vat must add back up to the legacy gross amount; got:\n$xml",
            )
        }
    }

    /** Runs the side-effect-free export and returns the concatenated per-invoice ledger XML. */
    private suspend fun exportLedgerXml(): String {
        val exportService = GlobalContext.get().get<DatevApExportService>()
        val result = exportService.exportTransient(START_DATE, END_DATE, adminId)

        val ledgerFiles = StringBuilder()
        ZipInputStream(result.bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                if (!entry.isDirectory && entry.name.startsWith("Rechnungsdaten_")) {
                    ledgerFiles.append(zip.readBytes().toString(Charsets.UTF_8))
                }
                zip.closeEntry()
            }
        }
        return ledgerFiles.toString()
    }
}
