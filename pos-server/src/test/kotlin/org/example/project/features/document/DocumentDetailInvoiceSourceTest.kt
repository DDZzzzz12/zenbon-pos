package org.example.project.features.document

import org.example.project.features.document.data.repository.DocumentRepositoryImpl
import org.example.project.features.document.domain.repository.DocumentRepository
import org.example.project.features.document.domain.service.DocumentManagementService
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Pins `GET /api/documents/{id}` and `/metadata` to the **normalized** invoice model.
 *
 * The predecessor slice repointed the document *list*; the detail path was left on the frozen
 * `document_invoice_metadata`, so an invoice captured by hand or parsed from an e-invoice payload
 * showed a populated list row and an empty detail panel for the same document. Both composeApp's
 * `DocumentMetadataPanel` and adminApp's `DocumentMetadataForm.toForm()` read this DTO.
 *
 * Four things have to hold at once:
 *
 * 1. a document whose invoice exists only on the new model populates the detail DTO — header,
 *    booking and VAT breakdown alike;
 * 2. when both a legacy row and a normalized invoice exist, the **normalized** one wins, whole-record
 *    — it is the row `PUT /api/invoices/{id}` edits and the row the DATEV Eingangsrechnungen export
 *    books from, and the panel must not disagree with the export;
 * 3. a document with no normalized invoice still falls back to its legacy row, because
 *    `InvoiceBackfill` is best-effort;
 * 4. `invoiceType` stays null on the normalized leg. Its counterpart is BT-3 `invoiceTypeCode`
 *    (`"381"`), and rendering a UNTDID code under "Rechnungsart" where an operator expects
 *    "Gutschrift" is worse than rendering nothing — the localized label lives in adminApp's
 *    `InvoiceTypeCode`, reachable through `GET /api/documents/{id}/invoice`.
 */
@DisplayName("Document detail — invoice metadata reads the normalized model")
class DocumentDetailInvoiceSourceTest {
    private lateinit var adminId: UUID
    private val repo = DocumentRepositoryImpl()

    private fun document(
        filename: String,
        fileType: String = "INVOICE",
    ): Long =
        repo
            .create(
                filename = filename,
                fileType = fileType,
                filePath = "/nonexistent/$filename",
                fileSize = 1024L,
                uploadedAt = currentTimeStamp(),
                uploadedBy = adminId,
                contentHash = "hash-${System.nanoTime()}",
                mediaType = "application/pdf",
            ).id.value

    private fun documentService(): DocumentManagementService = GlobalContext.get().get()

    @Test
    fun `an invoice that exists only on the new model populates the detail metadata`() {
        var documentId = 0L
        testApp(
            setupData = {
                adminId = TestData.createAdmin().id.value
                transaction {
                    documentId = document("nur-neues-modell-detail.pdf")
                    val invoice =
                        repo.createInvoice(
                            sourceDocumentId = documentId,
                            invoiceNumber = "RE-DETAIL-1",
                            issueDate = kotlinx.datetime.LocalDateTime.parse("2026-03-14T00:00:00"),
                            dueDate = kotlinx.datetime.LocalDateTime.parse("2026-04-14T00:00:00"),
                            currency = "EUR",
                            sellerName = "Nur Neues Modell GmbH",
                            sellerVatId = "DE123456789",
                            sellerStreet = "Musterweg 1",
                            sellerZip = "12345",
                            sellerCity = "Musterstadt",
                            paymentIban = "DE02120300000000202051",
                            paymentReference = "Verwendungszweck 4711",
                            taxExclusiveTotal = BigDecimal("200.00"),
                            taxTotal = BigDecimal("38.00"),
                            taxInclusiveTotal = BigDecimal("238.00"),
                        )
                    repo.replaceVatBreakdown(
                        invoice.id.value,
                        listOf(
                            DocumentRepository.VatBreakdownRow(
                                vatCategoryCode = "S",
                                vatRate = BigDecimal("19.00"),
                                taxableAmount = BigDecimal("200.00"),
                                vatAmount = BigDecimal("38.00"),
                                description = "Wareneinkauf",
                            ),
                        ),
                    )
                    repo.upsertInvoiceBooking(
                        invoiceId = invoice.id.value,
                        accountNo = "3400",
                        bpAccountNo = "70001",
                        bookingText = "Wareneinkauf",
                        costAmount = BigDecimal("238.00"),
                    )
                }
            },
        ) { _ ->
            val metadata = documentService().getDocumentById(documentId).invoiceMetadata
            assertNotNull(metadata, "an invoice on the new model must produce invoice metadata")

            assertEquals("Nur Neues Modell GmbH", metadata.vendorName, "vendorName comes from Invoice.sellerName")
            assertEquals("RE-DETAIL-1", metadata.invoiceNumber)
            assertEquals("2026-03-14T00:00", metadata.invoiceDate, "invoiceDate comes from Invoice.issueDate")
            assertEquals("2026-04-14T00:00", metadata.dueDate)
            assertEquals("200.00", metadata.netAmount, "netAmount comes from Invoice.taxExclusiveTotal")
            assertEquals("238.00", metadata.grossAmount, "grossAmount comes from Invoice.taxInclusiveTotal")
            assertEquals("38.00", metadata.taxAmount, "taxAmount comes from Invoice.taxTotal")
            assertEquals("19.00", metadata.taxRate, "a single VAT breakdown row supplies the header rate")
            assertEquals("EUR", metadata.currency)
            assertEquals("DE02120300000000202051", metadata.iban)
            assertEquals("Verwendungszweck 4711", metadata.paymentReference)
            assertEquals("Musterstadt", metadata.supplierCity)
            assertEquals("DE123456789", metadata.vatId)
            assertEquals("3400", metadata.accountNo, "accountNo comes from InvoiceBooking")
            assertEquals("70001", metadata.bpAccountNo)
            assertEquals("Wareneinkauf", metadata.bookingText)
            assertEquals("238.00", metadata.costAmount)
            assertEquals(1, metadata.taxLineItems?.size, "the VAT breakdown becomes the tax line items")
            // Line-level values render at their stored DECIMAL(19,4) scale via toExactDecimalString,
            // exactly as InvoiceVatBreakdown.toDto already does on GET /api/documents/{id}/invoice.
            // Pinning scale 2 here would round a four-decimal amount and make the two views disagree.
            assertEquals("200.0000", metadata.taxLineItems?.single()?.netAmount)
            assertEquals("238.0000", metadata.taxLineItems?.single()?.grossAmount)
        }
    }

    @Test
    fun `invoiceType stays null on the normalized leg rather than showing the UNTDID code`() {
        var documentId = 0L
        testApp(
            setupData = {
                adminId = TestData.createAdmin().id.value
                transaction {
                    documentId = document("gutschrift.pdf")
                    repo.createInvoice(
                        sourceDocumentId = documentId,
                        invoiceNumber = "GS-1",
                        invoiceTypeCode = "381",
                        precedingInvoiceReference = "RE-ORIGINAL-9",
                        sellerName = "Gutschrift GmbH",
                    )
                }
            },
        ) { _ ->
            val metadata = documentService().getDocumentById(documentId).invoiceMetadata
            assertNotNull(metadata)
            assertNull(
                metadata.invoiceType,
                "rendering \"381\" under Rechnungsart is worse than blank; the localized label lives " +
                    "on InvoiceDto.invoiceTypeCode, not here",
            )
            assertEquals("RE-ORIGINAL-9", metadata.draweeNo, "BT-25 is a plain string and maps straight across")
        }
    }

    @Test
    fun `the normalized invoice wins over a legacy metadata row for the same document`() {
        var documentId = 0L
        testApp(
            setupData = {
                adminId = TestData.createAdmin().id.value
                transaction {
                    documentId = document("beides-vorhanden-detail.pdf")
                    repo.createInvoiceMetadata(
                        documentId = documentId,
                        vendorName = "Alt Legacy GmbH",
                        invoiceNumber = "RE-ALT",
                        grossAmount = BigDecimal("11.11"),
                        currency = "EUR",
                    )
                    repo.createInvoice(
                        sourceDocumentId = documentId,
                        invoiceNumber = "RE-NEU",
                        sellerName = "Neu Normalisiert GmbH",
                        currency = "EUR",
                        taxInclusiveTotal = BigDecimal("222.22"),
                    )
                }
            },
        ) { _ ->
            val metadata = documentService().getDocumentMetadata(documentId).invoiceMetadata
            assertNotNull(metadata)
            assertEquals(
                "Neu Normalisiert GmbH",
                metadata.vendorName,
                "the normalized row is the one edits write and the AP export books; it must win",
            )
            assertEquals("222.22", metadata.grossAmount)
            assertEquals("RE-NEU", metadata.invoiceNumber)
        }
    }

    @Test
    fun `a document with no normalized invoice still falls back to its legacy row`() {
        var documentId = 0L
        testApp(
            setupData = {
                adminId = TestData.createAdmin().id.value
                transaction {
                    documentId = document("nur-legacy-detail.pdf")
                    repo.createInvoiceMetadata(
                        documentId = documentId,
                        vendorName = "Nur Legacy GmbH",
                        invoiceNumber = "RE-NUR-ALT",
                        grossAmount = BigDecimal("33.33"),
                        currency = "EUR",
                    )
                }
            },
        ) { _ ->
            val metadata = documentService().getDocumentById(documentId).invoiceMetadata
            assertNotNull(metadata)
            assertEquals(
                "Nur Legacy GmbH",
                metadata.vendorName,
                "the backfill is best-effort; a shop where it failed must still see its legacy values",
            )
            assertEquals("33.33", metadata.grossAmount)
        }
    }

    @Test
    fun `a bank statement is untouched by the invoice repoint`() {
        var documentId = 0L
        testApp(
            setupData = {
                adminId = TestData.createAdmin().id.value
                transaction {
                    documentId = document("kontoauszug-detail.pdf", fileType = "BANK_STATEMENT")
                    repo.createBankStatementMetadata(
                        documentId = documentId,
                        bankName = "Sparkasse Musterstadt",
                        statementNumber = "2026/03",
                        statementDate = kotlinx.datetime.LocalDateTime.parse("2026-03-31T00:00:00"),
                        closingBalance = BigDecimal("1234.56"),
                    )
                }
            },
        ) { _ ->
            val response = documentService().getDocumentById(documentId)
            assertNull(response.invoiceMetadata, "a bank statement has no invoice metadata")
            assertEquals("Sparkasse Musterstadt", response.bankStatementMetadata?.bankName)
            assertEquals("1234.56", response.bankStatementMetadata?.closingBalance)
        }
    }
}
