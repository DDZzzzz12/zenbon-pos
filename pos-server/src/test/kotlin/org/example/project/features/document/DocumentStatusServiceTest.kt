package org.example.project.features.document

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.document.ProcessingStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.features.document.data.repository.DocumentRepositoryImpl
import org.example.project.features.document.domain.service.DocumentManagementService
import org.example.project.features.employee.domain.models.Employee
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * `processing_status` stops being purely "what the parser did" and becomes the document's workflow
 * state. The initial value comes from the parse; [ProcessingStatus.IN_PROGRESS] and
 * [ProcessingStatus.REVIEWED] come from human action.
 *
 * Only those two are settable through the endpoint. The parser's outcomes and the vestigial queue
 * states are not the operator's to write — a client that could set `COMPLETED` would make the
 * column mean two different things at once.
 */
@DisplayName("DocumentManagementService.updateDocumentStatus")
class DocumentStatusServiceTest {
    private val repo = DocumentRepositoryImpl()

    private fun service(): DocumentManagementService = GlobalContext.get().get()

    /** uploadedBy is a real FK: a random UUID fails the write with "Employee not found". */
    private fun uploaderId(): UUID =
        Employee
            .all()
            .first()
            .id.value

    private fun createDocument(): Long =
        repo
            .create(
                filename = "scan.pdf",
                fileType = "INVOICE",
                filePath = "documents/scan.pdf",
                fileSize = 1_024L,
                uploadedAt = LocalDateTime(2026, 3, 1, 9, 0),
                uploadedBy = uploaderId(),
                contentHash = "hash-${System.nanoTime()}",
                metadataJson = null,
                ocrText = null,
                mediaType = "application/pdf",
            ).id.value

    @Test
    fun `an operator can move a document to IN_PROGRESS and then REVIEWED`() {
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { _ ->
            val documentId = transaction { createDocument() }

            runBlocking { service().updateDocumentStatus(documentId, ProcessingStatus.IN_PROGRESS) }
            transaction {
                assertEquals("IN_PROGRESS", assertNotNull(repo.findById(documentId)).processingStatus)
            }

            runBlocking { service().updateDocumentStatus(documentId, ProcessingStatus.REVIEWED) }
            transaction {
                assertEquals("REVIEWED", assertNotNull(repo.findById(documentId)).processingStatus)
            }
        }
    }

    @Test
    fun `a parser-owned status cannot be set by hand`() {
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { _ ->
            val documentId = transaction { createDocument() }

            val thrown =
                assertFailsWith<PosException> {
                    runBlocking { service().updateDocumentStatus(documentId, ProcessingStatus.COMPLETED) }
                }
            assertEquals(PosErrorCode.VALIDATION_FAILED, thrown.code)

            transaction {
                assertEquals(
                    "NONE",
                    assertNotNull(repo.findById(documentId)).processingStatus,
                    "a rejected status write must change nothing",
                )
            }
        }
    }

    @Test
    fun `an unknown document is a 404, not a silent no-op`() {
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { _ ->
            val thrown =
                assertFailsWith<PosException> {
                    runBlocking { service().updateDocumentStatus(999_999L, ProcessingStatus.REVIEWED) }
                }
            assertEquals(PosErrorCode.RESOURCE_NOT_FOUND, thrown.code)
        }
    }
}
