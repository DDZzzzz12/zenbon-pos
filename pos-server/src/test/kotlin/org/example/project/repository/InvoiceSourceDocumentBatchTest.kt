package org.example.project.repository

import org.example.project.features.document.data.repository.DocumentRepositoryImpl
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the batched counterpart of `DocumentRepository.getInvoiceBySourceDocument`.
 *
 * The document list renders up to 100 rows per page and needs each row's invoice. Doing that with a
 * per-row lookup is an N+1 on a paged endpoint, so the list uses one `IN` query per page instead —
 * and that query has to agree with the single-document lookup on every edge, or a list row would
 * show a different invoice than `GET /api/documents/{id}/invoice` returns for the same document.
 *
 * `source_document_id` is a plain, non-unique index (`InvoiceTable.init`), so "which invoice" is
 * decided by ordering: earliest by id, exactly as `getInvoiceBySourceDocument` does.
 */
@DisplayName("DocumentRepository — batched invoice lookup by source document")
class InvoiceSourceDocumentBatchTest {
    private val repo = DocumentRepositoryImpl()
    private lateinit var adminId: UUID

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
        adminId = TestData.createAdmin().id.value
    }

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    private fun document(filename: String): Long =
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
    fun `returns the earliest invoice per document and agrees with the single lookup`() {
        var withTwo = 0L
        var withOne = 0L
        var withNone = 0L
        var firstInvoiceId = 0L

        transaction {
            withTwo = document("zwei-rechnungen.pdf")
            withOne = document("eine-rechnung.pdf")
            withNone = document("keine-rechnung.pdf")

            firstInvoiceId =
                repo
                    .createInvoice(
                        sourceDocumentId = withTwo,
                        invoiceNumber = "RE-ERSTE",
                        sellerName = "Erste GmbH",
                        taxInclusiveTotal = BigDecimal("100.00"),
                    ).id.value
            repo.createInvoice(
                sourceDocumentId = withTwo,
                invoiceNumber = "RE-ZWEITE",
                sellerName = "Zweite GmbH",
                taxInclusiveTotal = BigDecimal("200.00"),
            )
            repo.createInvoice(
                sourceDocumentId = withOne,
                invoiceNumber = "RE-EINZEL",
                sellerName = "Einzel GmbH",
                taxInclusiveTotal = BigDecimal("300.00"),
            )
            // A bare XRechnung with no PDF — must never be attributed to a document.
            repo.createInvoice(
                sourceDocumentId = null,
                invoiceNumber = "RE-OHNE-PDF",
                sellerName = "Ohne PDF GmbH",
            )
        }

        transaction {
            val batch = repo.getInvoicesBySourceDocuments(listOf(withTwo, withOne, withNone))

            assertEquals(
                firstInvoiceId,
                batch[withTwo]?.id?.value,
                "a document carrying two invoices must resolve to the earliest by id, like getInvoiceBySourceDocument",
            )
            assertEquals("RE-EINZEL", batch[withOne]?.invoiceNumber)
            assertNull(batch[withNone], "a document with no invoice must be absent from the map, not mapped to a stray row")
            assertEquals(2, batch.size, "the source-less invoice must not appear under any document key")

            assertEquals(
                repo.getInvoiceBySourceDocument(withTwo)?.id?.value,
                batch[withTwo]?.id?.value,
                "the batched and single lookups must not disagree",
            )
        }
    }

    @Test
    fun `an empty id list issues no query and returns an empty map`() {
        transaction {
            assertTrue(repo.getInvoicesBySourceDocuments(emptyList()).isEmpty())
        }
    }
}
