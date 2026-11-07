package org.example.project.features.kassenbuch

import kotlinx.datetime.LocalDateTime
import org.example.project.features.document.data.repository.DocumentRepositoryImpl
import org.example.project.features.document.domain.repository.DocumentRepository.VatBreakdownRow
import org.example.project.features.kassenbuch.domain.service.DatevApExportService
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.io.File
import java.math.BigDecimal
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Golden-capture test for the DATEV AP (Eingangsrechnungen) export, taken **before** the
 * e-invoice/bank-statement model migration rewrites
 * [org.example.project.features.kassenbuch.data.service.DatevApExportServiceImpl.mapInvoiceToApEntries]
 * to read a new normalized invoice model instead of the flat `document_invoice_metadata` table.
 *
 * **What this proves, and what it does not:** production currently holds 97 invoice metadata rows,
 * and in every one of them all booking fields (`accountNo`, `bpAccountNo`, `buCode`, `bookingText`,
 * `costCategoryId`) and all five JSON blobs (`taxLineItemsJson`, `lineItemsJson`,
 * `paymentDetailsJson`, `discountTermsJson`, `additionalInfoJson`) are empty. The two fixtures built
 * here are therefore synthetic — they deliberately populate those fields (and both the split-tax and
 * single-tax amount paths) so the export mapping is actually exercised. This golden file demonstrates
 * that the *mapping shape* survives the model migration; it does **not** demonstrate that any real
 * production export is unaffected, because no production row looks like these fixtures. In
 * particular, both fixtures carry VAT breakdown rows, so neither covers the empty-breakdown
 * fallback that a backfilled legacy invoice actually hits.
 *
 * **Mode:** ASSERT. `pos-server/src/test/resources/datev-ap-golden.xml` was captured through the
 * **legacy** `document_invoice_metadata` path; the fixtures below now build the same two invoices
 * through the **new** normalized model (`createInvoice` + `replaceVatBreakdown` +
 * `upsertInvoiceBooking`), and the export output must still equal that file byte for byte. The
 * dual-mode logic below (assert when the file is present, capture when it is not) is kept only as
 * an escape hatch for a deliberate, reviewed export-format change — never delete the golden file
 * to make a failing run pass.
 */
class DatevApExportModelMigrationTest {
    companion object {
        private val goldenFile = File("src/test/resources/datev-ap-golden.xml")
        private const val START_DATE = "2026-01-01"
        private const val END_DATE = "2026-01-31"
    }

    private lateinit var adminId: UUID

    @Test
    fun `AP export output matches the pre-migration golden capture`() =
        testApp(
            setupData = {
                TestData.createShopInfo()
                val admin = TestData.createAdmin()
                adminId = admin.id.value

                val documentRepository = DocumentRepositoryImpl()
                transaction {
                    // Fixture 1: two VAT breakdown rows -> exercises the split-tax path, which emits
                    // one accountsPayableLedger entry per breakdown row, each with its own booking
                    // text taken from the row description.
                    val splitTaxDoc =
                        documentRepository.create(
                            filename = "invoice-split-tax.pdf",
                            fileType = "INVOICE",
                            filePath = "/nonexistent/invoice-split-tax.pdf",
                            fileSize = 1024L,
                            uploadedAt = currentTimeStamp(),
                            uploadedBy = adminId,
                            contentHash = "golden-split-tax-hash",
                            mediaType = "application/pdf",
                        )
                    val splitTaxInvoice =
                        documentRepository.createInvoice(
                            sourceDocumentId = splitTaxDoc.id.value,
                            invoiceNumber = "INV-SPLIT-0001",
                            issueDate = LocalDateTime(2026, 1, 15, 0, 0),
                            dueDate = LocalDateTime(2026, 2, 14, 0, 0),
                            currency = "EUR",
                            sellerName = "Acme Wholesale GmbH",
                            sellerVatId = "DE123456789",
                            sellerCity = "Berlin",
                            paymentIban = "DE89370400440532013000",
                            paymentReference = "Bestellung 4711",
                            taxExclusiveTotal = BigDecimal("150.00"),
                            taxTotal = BigDecimal("16.50"),
                            taxInclusiveTotal = BigDecimal("166.50"),
                        )
                    documentRepository.replaceVatBreakdown(
                        invoiceId = splitTaxInvoice.id.value,
                        rows =
                            listOf(
                                VatBreakdownRow(
                                    vatCategoryCode = "S",
                                    vatRate = BigDecimal("7.00"),
                                    taxableAmount = BigDecimal("100.00"),
                                    vatAmount = BigDecimal("7.00"),
                                    description = "Speisen",
                                ),
                                VatBreakdownRow(
                                    vatCategoryCode = "S",
                                    vatRate = BigDecimal("19.00"),
                                    taxableAmount = BigDecimal("50.00"),
                                    vatAmount = BigDecimal("9.50"),
                                    description = "Getraenke",
                                ),
                            ),
                    )
                    documentRepository.upsertInvoiceBooking(
                        invoiceId = splitTaxInvoice.id.value,
                        accountNo = "70000",
                        bpAccountNo = "70001",
                        buCode = "9",
                        bookingText = "Wareneinkauf gemischter Steuersatz",
                        costCategoryId = "KOST-100",
                    )

                    // Fixture 2: a single VAT breakdown row with no description -> exercises the
                    // fall-back to the invoice-level booking text.
                    val singleTaxDoc =
                        documentRepository.create(
                            filename = "invoice-single-tax.pdf",
                            fileType = "INVOICE",
                            filePath = "/nonexistent/invoice-single-tax.pdf",
                            fileSize = 2048L,
                            uploadedAt = currentTimeStamp(),
                            uploadedBy = adminId,
                            contentHash = "golden-single-tax-hash",
                            mediaType = "application/pdf",
                        )
                    val singleTaxInvoice =
                        documentRepository.createInvoice(
                            sourceDocumentId = singleTaxDoc.id.value,
                            invoiceNumber = "INV-SINGLE-0002",
                            issueDate = LocalDateTime(2026, 1, 20, 0, 0),
                            dueDate = LocalDateTime(2026, 2, 19, 0, 0),
                            currency = "EUR",
                            sellerName = "Buero Partner AG",
                            sellerVatId = "DE987654321",
                            sellerCity = "Muenchen",
                            paymentIban = "DE12500105170648489890",
                            paymentReference = "Rechnung INV-SINGLE-0002",
                            taxExclusiveTotal = BigDecimal("200.00"),
                            taxTotal = BigDecimal("38.00"),
                            taxInclusiveTotal = BigDecimal("238.00"),
                        )
                    documentRepository.replaceVatBreakdown(
                        invoiceId = singleTaxInvoice.id.value,
                        rows =
                            listOf(
                                VatBreakdownRow(
                                    vatCategoryCode = "S",
                                    vatRate = BigDecimal("19.00"),
                                    taxableAmount = BigDecimal("200.00"),
                                    vatAmount = BigDecimal("38.00"),
                                ),
                            ),
                    )
                    documentRepository.upsertInvoiceBooking(
                        invoiceId = singleTaxInvoice.id.value,
                        accountNo = "70000",
                        bpAccountNo = "70002",
                        buCode = "9",
                        bookingText = "Bueromaterial",
                        costCategoryId = "KOST-200",
                    )
                }
            },
        ) { _ ->
            val exportService = GlobalContext.get().get<DatevApExportService>()
            val result = exportService.exportTransient(START_DATE, END_DATE, adminId)

            val actual = extractXmlEntriesAsText(result.bytes)
            assertTrue(actual.isNotBlank(), "extracted XML content must not be blank")

            if (goldenFile.exists()) {
                val expected = goldenFile.readText()
                assertEquals(expected, actual, "DATEV AP export output diverged from the pre-migration golden capture")
            } else {
                goldenFile.parentFile.mkdirs()
                goldenFile.writeText(actual)
                println("DatevApExportModelMigrationTest: captured golden file at ${goldenFile.absolutePath}")
            }
        }

    /**
     * Unzips the AP export package and concatenates its XML entries (document.xml plus one
     * per-invoice ledger file) in a deterministic, sorted order into a single diffable text blob.
     *
     * `document.xml`'s `<header><date>` is `LocalDateTime.now()` at generation time
     * ([org.example.project.export.datevxml.ap.DatevApDocumentGenerator.generate]) — it is the one
     * genuinely non-deterministic field in the whole package. Left alone it would make this golden
     * comparison fail on every run regardless of whether the mapping changed, so it is normalized to
     * a fixed placeholder before diffing. Nothing else in the export output is time-dependent.
     */
    private fun extractXmlEntriesAsText(zipBytes: ByteArray): String {
        val entries = sortedMapOf<String, String>()
        ZipInputStream(zipBytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                if (!entry.isDirectory && entry.name.endsWith(".xml")) {
                    entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                }
                zip.closeEntry()
            }
        }
        val generationTimestamp = Regex("""<date>\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}</date>""")
        return entries.entries.joinToString(separator = "\n") { (name, content) ->
            "<!-- === $name === -->\n${content.replace(generationTimestamp, "<date>REDACTED-GENERATION-TIMESTAMP</date>")}"
        }
    }
}
