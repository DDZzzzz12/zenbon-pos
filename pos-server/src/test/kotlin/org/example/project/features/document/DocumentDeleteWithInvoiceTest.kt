package org.example.project.features.document

import kotlinx.coroutines.runBlocking
import org.example.project.core.error.PosErrorCode
import org.example.project.features.document.data.repository.DocumentRepositoryImpl
import org.example.project.features.document.domain.models.Document
import org.example.project.features.document.domain.service.DocumentManagementService
import org.example.project.infrastructure.errors.PosException
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T9-19: deleting a Beleg used to leave the invoice read from it live — still counted in the
 * supplier's open total, the Rechnungen OFFEN bucket and the Zuordnung suggestions, now without its
 * evidence. An incoming invoice has no void state, so the deletion is refused while an invoice
 * references the document (`DELETE_HAS_REFERENCES`, the same answer a linked document gets), and the
 * document stays active.
 */
@DisplayName("Document delete is refused while an invoice references it (T9-19)")
class DocumentDeleteWithInvoiceTest {
    private lateinit var adminId: UUID
    private val repo = DocumentRepositoryImpl()

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

    private fun documentService(): DocumentManagementService = GlobalContext.get().get()

    @Test
    fun `a document with an invoice cannot be deleted and stays active`() {
        var documentId = 0L
        testApp(
            setupData = {
                adminId = TestData.createAdmin().id.value
                transaction {
                    documentId = document("rechnung-mit-beleg.pdf")
                    repo.createInvoice(
                        sourceDocumentId = documentId,
                        invoiceNumber = "RE-T919-1",
                        currency = "EUR",
                        sellerName = "Lieferant GmbH",
                        taxInclusiveTotal = BigDecimal("119.00"),
                    )
                }
            },
        ) { _ ->
            val error = assertFailsWith<PosException> { runBlocking { documentService().deleteDocument(documentId) } }
            assertEquals(PosErrorCode.DELETE_HAS_REFERENCES, error.code)

            transaction { assertTrue(Document.findById(documentId)!!.isActive, "the refused delete must leave the Beleg active") }
            val dto = runBlocking { documentService().getDocumentById(documentId) }
            assertTrue(dto.invoiceId != null, "the DTO names the invoice so the client can disable the action")
        }
    }

    @Test
    fun `a document without an invoice reports no invoice id`() {
        var documentId = 0L
        testApp(
            setupData = {
                adminId = TestData.createAdmin().id.value
                transaction { documentId = document("nur-ein-scan.pdf") }
            },
        ) { _ ->
            assertNull(runBlocking { documentService().getDocumentById(documentId) }.invoiceId)
        }
    }
}
