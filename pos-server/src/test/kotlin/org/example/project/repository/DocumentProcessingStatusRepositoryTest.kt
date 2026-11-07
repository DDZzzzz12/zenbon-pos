package org.example.project.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.document.ProcessingStatus
import org.example.project.features.document.data.repository.DocumentRepositoryImpl
import org.example.project.features.document.domain.models.Document
import org.example.project.features.employee.domain.models.Employee
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
@DisplayName("DocumentRepository.updateProcessingStatus")
class DocumentProcessingStatusRepositoryTest {
    private val repo = DocumentRepositoryImpl()

    @Test
    fun `a new document starts at NONE and the status can be set`() {
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { _ ->
            val documentId = transaction { createDocument(repo) }

            // The column default, before anything writes to it.
            transaction {
                assertEquals("NONE", Document.findById(documentId)!!.processingStatus)
            }

            transaction { repo.updateProcessingStatus(documentId, ProcessingStatus.COMPLETED) }

            // Separate transaction: reading back inside the writing one would hit Exposed's
            // identity map rather than the stored row.
            transaction {
                assertEquals("COMPLETED", Document.findById(documentId)!!.processingStatus)
            }
        }
    }

    @Test
    fun `the stored value is always an exact enum constant name`() {
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { _ ->
            val documentId = transaction { createDocument(repo) }

            // DocumentMapper reads this column with a bare ProcessingStatus.valueOf() and no
            // fallback, so any value that is not an exact constant name makes every later read
            // of the document throw. Pin every constant, not just the ones this feature writes.
            for (status in ProcessingStatus.entries) {
                transaction { repo.updateProcessingStatus(documentId, status) }
                transaction {
                    val stored = Document.findById(documentId)!!.processingStatus
                    assertEquals(status.name, stored)
                    assertEquals(status, ProcessingStatus.valueOf(stored))
                }
            }
        }
    }

    @Test
    fun `an unknown document id returns null rather than throwing`() {
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { _ ->
            // The upload path calls this after the document transaction has already committed and
            // must never fail the upload, so a missing row is a null, not an exception.
            transaction {
                assertNull(repo.updateProcessingStatus(999_999L, ProcessingStatus.FAILED))
            }
        }
    }

    /**
     * Must run inside a transaction: `uploadedBy` has to be a real employee — `create` rejects an
     * unknown UUID with `IllegalArgumentException: Employee not found`.
     */
    private fun createDocument(repo: DocumentRepositoryImpl): Long {
        // uploadDocument rejects duplicate hashes, so keep each created document distinct.
        val contentHash = "hash-" + System.nanoTime()
        val uploader = Employee.all().first()
        return repo
            .create(
                filename = "scan.pdf",
                fileType = "INVOICE",
                filePath = "documents/scan.pdf",
                fileSize = 1_024L,
                uploadedAt = LocalDateTime(2026, 8, 10, 9, 0),
                uploadedBy = uploader.id.value,
                contentHash = contentHash,
                metadataJson = null,
                ocrText = null,
                mediaType = "application/pdf",
            ).id.value
    }
}
