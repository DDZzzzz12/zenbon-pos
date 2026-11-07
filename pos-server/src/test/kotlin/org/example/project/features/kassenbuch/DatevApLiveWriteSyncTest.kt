package org.example.project.features.kassenbuch

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.features.document.data.repository.DocumentRepositoryImpl
import org.example.project.features.document.data.service.InvoiceBackfill
import org.example.project.features.document.domain.service.DocumentManagementService
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the two ways an invoice used to go missing from the DATEV Eingangsrechnungen export.
 *
 * The export reads the normalized `InvoiceTable`, but for a while the only writer of that table was
 * the startup backfill. Both consequences were silent and both are covered here:
 *
 * 1. an invoice booked since the last restart was simply **absent** from the export — a month-end
 *    run without an intervening restart omitted everything booked in the month;
 * 2. a later correction to an already-backfilled invoice never reached it, because the backfill
 *    skips a document that already has an invoice — the export kept emitting the pre-correction
 *    booking forever.
 *
 * A third failure mode is covered here too, because it shares the same cause: a mixed-VAT invoice
 * carries a per-rate split that a writer which collapsed it produced as one ledger entry with no
 * rate at all.
 *
 * Every test writes through [DocumentManagementService]'s **normalized** invoice endpoints —
 * `createInvoiceForDocument`, `updateInvoiceSupplierContent`, `updateInvoiceBooking` — which is the
 * live path an operator reaches through adminApp's capture form. Calling `createInvoice` on the
 * repository directly would bypass exactly the gap under test.
 *
 * These tests previously drove the legacy `updateInvoiceMetadata` path, which projected onto the
 * model through `LegacyInvoiceSync.syncQuietly`. That projection and that entry point were removed
 * when legacy was frozen; the defects above did not go away with them, so the tests moved rather
 * than retired. The backfill leg in test 2 is deliberately kept: `InvoiceBackfill` still runs at
 * startup and is still the only populator of `InvoiceTable` for pre-migration rows.
 */
class DatevApLiveWriteSyncTest {
    private companion object {
        const val START_DATE = "2026-03-01"
        const val END_DATE = "2026-03-31"
    }

    private lateinit var adminId: UUID
    private var documentId: Long = 0
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
    fun `an invoice booked through the live path is exportable without a server restart`() {
        testApp(
            setupData = {
                TestData.createShopInfo()
                adminId = TestData.createAdmin().id.value
                documentId = transaction { invoiceDocument("invoice-live.pdf") }
            },
        ) { _ ->
            val invoice =
                documentService().createInvoiceForDocument(
                    documentId,
                    DocumentDto.InvoiceContentRequestDto(
                        invoiceNumber = "INV-LIVE-0001",
                        issueDate = "2026-03-14",
                        currency = "EUR",
                        sellerName = "Frisch Gebucht GmbH",
                        taxExclusiveTotal = "200.00",
                        taxTotal = "38.00",
                        taxInclusiveTotal = "238.00",
                        vatBreakdown =
                            listOf(
                                DocumentDto.VatBreakdownDto(
                                    vatCategoryCode = "S",
                                    vatRate = "19.00",
                                    taxableAmount = "200.00",
                                    vatAmount = "38.00",
                                ),
                            ),
                    ),
                )

            documentService().updateInvoiceBooking(
                invoice.id,
                DocumentDto.UpdateInvoiceBookingRequestDto(
                    accountNo = "70000",
                    bookingText = "Sofort exportierbar",
                ),
            )

            val xml = exportLedgerXml()

            assertTrue(
                xml.contains("<invoiceId>INV-LIVE-0001</invoiceId>"),
                "an invoice booked since the last restart must reach the export; got:\n$xml",
            )
            assertTrue(
                xml.contains("<bookingText>Sofort exportierbar</bookingText>"),
                "its booking decision must reach the export too; got:\n$xml",
            )
            assertTrue(
                xml.contains("<amount>238.00</amount>"),
                "the exported entry must carry the booked amount; got:\n$xml",
            )
            assertTrue(
                xml.contains("<tax>19.00</tax>"),
                "the VAT breakdown row must survive as the entry's rate; got:\n$xml",
            )
        }
    }

    @Test
    fun `a correction to an already-backfilled invoice reaches the export`() {
        testApp(
            setupData = {
                TestData.createShopInfo()
                adminId = TestData.createAdmin().id.value

                transaction {
                    documentId = invoiceDocument("invoice-corrected.pdf")
                    repo.updateInvoiceMetadata(
                        documentId = documentId,
                        vendorName = "Vertippt GmbH",
                        invoiceNumber = "INV-TYPO-0001",
                        invoiceDate = LocalDateTime(2026, 3, 18, 0, 0),
                        grossAmount = BigDecimal("238.00"),
                        taxRate = BigDecimal("19.00"),
                        currency = "EUR",
                        accountNo = "70000",
                        bookingText = "Falscher Text",
                    )
                    InvoiceBackfill(repo).run()
                }
            },
        ) { _ ->
            // The accountant corrects the invoice number and the booking text on the normalized
            // model. Supplier content is a full replace, not a patch, so everything else is
            // re-sent unchanged; booking is a separate, field-level update.
            val invoice = documentService().getInvoiceForDocument(documentId)

            documentService().updateInvoiceSupplierContent(
                invoice.id,
                DocumentDto.InvoiceContentRequestDto(
                    invoiceNumber = "INV-KORRIGIERT-01",
                    issueDate = "2026-03-18",
                    currency = "EUR",
                    sellerName = "Vertippt GmbH",
                    taxExclusiveTotal = "200.00",
                    taxTotal = "38.00",
                    taxInclusiveTotal = "238.00",
                    vatBreakdown =
                        listOf(
                            DocumentDto.VatBreakdownDto(
                                vatCategoryCode = "S",
                                vatRate = "19.00",
                                taxableAmount = "200.00",
                                vatAmount = "38.00",
                            ),
                        ),
                ),
            )

            documentService().updateInvoiceBooking(
                invoice.id,
                DocumentDto.UpdateInvoiceBookingRequestDto(
                    accountNo = "70000",
                    bookingText = "Korrigierter Text",
                ),
            )

            val xml = exportLedgerXml()

            assertTrue(
                xml.contains("<invoiceId>INV-KORRIGIERT-01</invoiceId>"),
                "the corrected invoice number must reach the export; got:\n$xml",
            )
            assertTrue(
                xml.contains("<bookingText>Korrigierter Text</bookingText>"),
                "the corrected booking text must reach the export; got:\n$xml",
            )
            assertFalse(
                xml.contains("INV-TYPO-0001"),
                "the pre-correction invoice number must not survive in the export; got:\n$xml",
            )
            assertFalse(
                xml.contains("Falscher Text"),
                "the pre-correction booking text must not survive in the export; got:\n$xml",
            )
        }
    }

    @Test
    fun `a mixed-VAT invoice still exports one ledger entry per rate`() {
        testApp(
            setupData = {
                TestData.createShopInfo()
                adminId = TestData.createAdmin().id.value
                documentId = transaction { invoiceDocument("invoice-mixed-vat.pdf") }
            },
        ) { _ ->
            // A 7 % food line alongside a 19 % drinks line on one supplier invoice. The per-rate
            // split lives in the VAT breakdown, and the export emits one <accountsPayableLedger>
            // entry per breakdown row — collapsing it would lose the Vorsteuer split the
            // accountant books from.
            val invoice =
                documentService().createInvoiceForDocument(
                    documentId,
                    DocumentDto.InvoiceContentRequestDto(
                        invoiceNumber = "INV-MIXED-0001",
                        issueDate = "2026-03-21",
                        currency = "EUR",
                        sellerName = "Gemischt Gastro GmbH",
                        taxExclusiveTotal = "300.00",
                        taxTotal = "45.00",
                        taxInclusiveTotal = "345.00",
                        vatBreakdown =
                            listOf(
                                DocumentDto.VatBreakdownDto(
                                    vatCategoryCode = "S",
                                    vatRate = "7.00",
                                    taxableAmount = "100.00",
                                    vatAmount = "7.00",
                                    description = "Speisen",
                                ),
                                DocumentDto.VatBreakdownDto(
                                    vatCategoryCode = "S",
                                    vatRate = "19.00",
                                    taxableAmount = "200.00",
                                    vatAmount = "38.00",
                                    description = "Getraenke",
                                ),
                            ),
                    ),
                )

            documentService().updateInvoiceBooking(
                invoice.id,
                DocumentDto.UpdateInvoiceBookingRequestDto(
                    accountNo = "70000",
                    bookingText = "Wareneinkauf",
                ),
            )

            val xml = exportLedgerXml()

            assertEquals(
                2,
                xml.split("<accountsPayableLedger>").size - 1,
                "a two-rate invoice must stay two ledger entries, one per rate; got:\n$xml",
            )
            assertTrue(
                xml.contains("<amount>107.00</amount>") && xml.contains("<tax>7.00</tax>"),
                "the 7 % line must keep its own amount and rate; got:\n$xml",
            )
            assertTrue(
                xml.contains("<amount>238.00</amount>") && xml.contains("<tax>19.00</tax>"),
                "the 19 % line must keep its own amount and rate; got:\n$xml",
            )
            assertTrue(
                xml.contains("<bookingText>Speisen</bookingText>") &&
                    xml.contains("<bookingText>Getraenke</bookingText>"),
                "each row's description must survive as its per-entry booking text; got:\n$xml",
            )
        }
    }

    private fun documentService(): DocumentManagementService = GlobalContext.get().get()

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
