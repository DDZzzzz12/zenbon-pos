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
import kotlin.test.assertTrue

/**
 * Pins the search filters to the **normalized** invoice model rather than the legacy metadata table.
 *
 * `vendorName`, `invoiceNumber` and the invoice leg of `minAmount`/`maxAmount` used to join
 * `document_invoice_metadata`. They now read `InvoiceTable` through a correlated `EXISTS`, so the
 * aggregate inversion — the invoice references the document instead of hanging off it — is what the
 * query walks.
 *
 * Three things have to hold at once and each has a test:
 *
 * 1. an invoice that exists **only** on the new model makes its document findable. Every such case
 *    would have returned nothing under the legacy join, so it cannot pass by accident;
 * 2. a document with no invoice at all stays findable whenever no invoice filter is applied, and
 *    disappears only when one is — the `EXISTS` must not degenerate into an inner join;
 * 3. the live operator path still works end to end: an invoice captured by hand through
 *    `createInvoiceForDocument` lands on the new model and the repointed filter finds it there.
 *
 * Every assertion also checks `total` against `items.size`. `total` comes from `count` and `items`
 * from `search`; if the two ever applied different predicates, page 2 of a filtered search would
 * show the wrong rows, and this equality is what would catch it.
 */
@DisplayName("Document search — invoice filters read the normalized invoice model")
class DocumentSearchNewModelFilterTest {
    private lateinit var adminId: UUID
    private val repo = DocumentRepositoryImpl()

    /** Creates a document row. The file itself never has to exist. */
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

    private suspend fun search(filter: DocumentDto.DocumentSearchFilterDto): DocumentDto.DocumentListResponseDto {
        val page = documentService().searchDocuments(filter)
        assertEquals(
            page.items.size.toLong(),
            page.total,
            "search and count must apply identical predicates; got ${page.items.size} items but total=${page.total}",
        )
        return page
    }

    @Test
    fun `vendor name and invoice number find a document through its new-model invoice alone`() {
        var matchId = 0L
        testApp(
            setupData = {
                adminId = TestData.createAdmin().id.value
                transaction {
                    matchId = document("nur-neues-modell.pdf")
                    repo.createInvoice(
                        sourceDocumentId = matchId,
                        invoiceNumber = "RE-NEUMODELL-1",
                        sellerName = "Nur Neues Modell GmbH",
                        taxInclusiveTotal = BigDecimal("238.00"),
                    )
                    // A second document with no invoice, so a filter that matched everything fails.
                    document("ohne-rechnung.pdf")
                }
            },
        ) { _ ->
            val byVendor = search(DocumentDto.DocumentSearchFilterDto(vendorName = "neues modell"))
            assertEquals(
                listOf(matchId),
                byVendor.items.map { it.id },
                "vendorName must match InvoiceTable.sellerName; there is no legacy metadata row at all",
            )

            val byNumber = search(DocumentDto.DocumentSearchFilterDto(invoiceNumber = "RE-NEUMODELL-1"))
            assertEquals(
                listOf(matchId),
                byNumber.items.map { it.id },
                "invoiceNumber must match InvoiceTable.invoiceNumber",
            )

            val byAmount =
                search(DocumentDto.DocumentSearchFilterDto(minAmount = "200.00", maxAmount = "300.00"))
            assertEquals(
                listOf(matchId),
                byAmount.items.map { it.id },
                "the invoice leg of the amount filter must read InvoiceTable.taxInclusiveTotal",
            )

            val noMatch = search(DocumentDto.DocumentSearchFilterDto(vendorName = "Gibt Es Nicht"))
            assertTrue(noMatch.items.isEmpty(), "a non-matching vendor must return nothing")
        }
    }

    @Test
    fun `a document with no invoice stays findable when no invoice filter is applied`() {
        var plainId = 0L
        testApp(
            setupData = {
                adminId = TestData.createAdmin().id.value
                transaction { plainId = document("vertrag.pdf", fileType = "CONTRACTS") }
            },
        ) { _ ->
            val unfiltered = search(DocumentDto.DocumentSearchFilterDto())
            assertTrue(
                unfiltered.items.any { it.id == plainId },
                "EXISTS must not act as an inner join — an invoice-less document must survive an unfiltered search",
            )

            val byTerm = search(DocumentDto.DocumentSearchFilterDto(searchTerm = "vertrag"))
            assertEquals(
                listOf(plainId),
                byTerm.items.map { it.id },
                "the searchTerm OR must still match filename for a document that has no invoice",
            )

            val byVendor = search(DocumentDto.DocumentSearchFilterDto(vendorName = "irgendwer"))
            assertTrue(
                byVendor.items.none { it.id == plainId },
                "an invoice-less document must not match an invoice filter",
            )
        }
    }

    @Test
    fun `an invoice captured by hand is findable on the new model`() {
        var documentId = 0L
        testApp(
            setupData = {
                TestData.createShopInfo()
                adminId = TestData.createAdmin().id.value
                transaction { documentId = document("live-pfad.pdf") }
            },
        ) { _ ->
            documentService().createInvoiceForDocument(
                documentId,
                DocumentDto.InvoiceContentRequestDto(
                    invoiceNumber = "RE-LIVE-77",
                    issueDate = "2026-03-14",
                    currency = "EUR",
                    sellerName = "Live Gebucht GmbH",
                    taxExclusiveTotal = "100.00",
                    taxTotal = "19.00",
                    taxInclusiveTotal = "119.00",
                    vatBreakdown =
                        listOf(
                            DocumentDto.VatBreakdownDto(
                                vatCategoryCode = "S",
                                vatRate = "19.00",
                                taxableAmount = "100.00",
                                vatAmount = "19.00",
                            ),
                        ),
                ),
            )

            val byVendor = search(DocumentDto.DocumentSearchFilterDto(vendorName = "live gebucht"))
            assertEquals(
                listOf(documentId),
                byVendor.items.map { it.id },
                "the vendor filter reads InvoiceTable.sellerName; a hand-captured invoice must be seen there",
            )

            val byNumber = search(DocumentDto.DocumentSearchFilterDto(invoiceNumber = "RE-LIVE-77"))
            assertEquals(
                listOf(documentId),
                byNumber.items.map { it.id },
                "the invoice number written through the capture path must be searchable on the new model",
            )

            val byTerm = search(DocumentDto.DocumentSearchFilterDto(searchTerm = "live gebucht"))
            assertEquals(
                listOf(documentId),
                byTerm.items.map { it.id },
                "the free-text term must reach InvoiceTable.sellerName too",
            )
        }
    }
}
