package org.example.project.features.document

import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.features.document.data.repository.DocumentRepositoryImpl
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
import kotlin.test.assertNull

/**
 * Pins the document list's flattened columns to the **normalized** invoice model.
 *
 * Stage 4 stopped the upload path writing `document_invoice_metadata` and Stage 5 deleted the last
 * legacy writer, so an invoice captured by hand or parsed from an e-invoice payload has no legacy
 * row at all. `toListItemDto` still read that row, which rendered amount, date, reference, vendor,
 * IBAN and currency **blank** in both apps — while the same document remained findable by vendor
 * name, because the search filters had already been repointed.
 *
 * Three things have to hold at once:
 *
 * 1. a document whose invoice exists only on the new model shows its values;
 * 2. when both a legacy row and a normalized invoice exist, the **normalized** one wins — it is the
 *    row edits write and the row the DATEV Eingangsrechnungen export books from, and the list must
 *    not disagree with the export;
 * 3. the bank-statement columns are untouched. Bank statements still write legacy metadata and have
 *    no normalized write path, so repointing that leg would blank every bank-statement row.
 */
@DisplayName("Document list — flattened invoice columns read the normalized model")
class DocumentListItemInvoiceSourceTest {
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

    private suspend fun listItem(documentId: Long): DocumentDto.DocumentListItemDto =
        documentService()
            .searchDocuments(DocumentDto.DocumentSearchFilterDto())
            .items
            .single { it.id == documentId }

    @Test
    fun `an invoice that exists only on the new model populates the list columns`() {
        var documentId = 0L
        testApp(
            setupData = {
                adminId = TestData.createAdmin().id.value
                transaction {
                    documentId = document("nur-neues-modell.pdf")
                    repo.createInvoice(
                        sourceDocumentId = documentId,
                        invoiceNumber = "RE-NEUMODELL-1",
                        issueDate = kotlinx.datetime.LocalDateTime.parse("2026-03-14T00:00:00"),
                        currency = "EUR",
                        sellerName = "Nur Neues Modell GmbH",
                        paymentIban = "DE02120300000000202051",
                        taxInclusiveTotal = BigDecimal("238.00"),
                    )
                }
            },
        ) { _ ->
            val item = listItem(documentId)
            assertEquals("238.00", item.amount, "amount must come from InvoiceTable.taxInclusiveTotal")
            assertEquals("2026-03-14T00:00", item.date, "date must come from InvoiceTable.issueDate")
            assertEquals("RE-NEUMODELL-1", item.reference)
            assertEquals("Nur Neues Modell GmbH", item.vendorName, "vendorName must come from InvoiceTable.sellerName")
            assertEquals("DE02120300000000202051", item.iban)
            assertEquals("EUR", item.currency)
        }
    }

    @Test
    fun `the normalized invoice wins over a legacy metadata row for the same document`() {
        var documentId = 0L
        testApp(
            setupData = {
                adminId = TestData.createAdmin().id.value
                transaction {
                    documentId = document("beides-vorhanden.pdf")
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
            val item = listItem(documentId)
            assertEquals(
                "Neu Normalisiert GmbH",
                item.vendorName,
                "the normalized row is the one edits write and the AP export books; it must win",
            )
            assertEquals("222.22", item.amount)
            assertEquals("RE-NEU", item.reference)
        }
    }

    @Test
    fun `a document with no invoice at all still falls back to its legacy row`() {
        var documentId = 0L
        testApp(
            setupData = {
                adminId = TestData.createAdmin().id.value
                transaction {
                    documentId = document("nur-legacy.pdf")
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
            val item = listItem(documentId)
            assertEquals(
                "Nur Legacy GmbH",
                item.vendorName,
                "the backfill is best-effort; a shop where it failed must still see its legacy values",
            )
            assertEquals("33.33", item.amount)
        }
    }

    @Test
    fun `bank statement columns still read the legacy bank metadata`() {
        var documentId = 0L
        testApp(
            setupData = {
                adminId = TestData.createAdmin().id.value
                transaction {
                    documentId = document("kontoauszug.pdf", fileType = "BANK_STATEMENT")
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
            val item = listItem(documentId)
            assertEquals("Sparkasse Musterstadt", item.bankName)
            assertEquals("1234.56", item.closingBalance)
            assertEquals("2026-03-31T00:00", item.statementDate)
            assertEquals(
                "2026-03-31T00:00",
                item.date,
                "with no invoice, `date` must still fall through to the statement date",
            )
            assertNull(item.vendorName, "a bank statement has no invoice columns")
        }
    }
}
