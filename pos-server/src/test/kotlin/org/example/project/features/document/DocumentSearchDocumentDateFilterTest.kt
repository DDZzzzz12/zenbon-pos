package org.example.project.features.document

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.features.document.data.repository.DocumentRepositoryImpl
import org.example.project.features.document.domain.service.DocumentManagementService
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals

/**
 * FO-51: the Belege period filter (month, `fromDate`, `toDate`) works on the DOCUMENT date — what the
 * list's "Datum" column shows — and falls back to the upload date only for a document that has no
 * date of its own. Before, it filtered on the upload date, so an invoice dated March but scanned in
 * April was listed under April's period while its Datum column said March.
 */
@DisplayName("Document search — period filter uses the document date")
class DocumentSearchDocumentDateFilterTest {
    private lateinit var adminId: UUID
    private val repo = DocumentRepositoryImpl()

    private fun document(
        filename: String,
        uploadedAt: LocalDateTime,
        fileType: String = "INVOICE",
    ): Long =
        repo
            .create(
                filename = filename,
                fileType = fileType,
                filePath = "/nonexistent/$filename",
                fileSize = 1024L,
                uploadedAt = uploadedAt,
                uploadedBy = adminId,
                contentHash = "hash-${System.nanoTime()}",
                mediaType = "application/pdf",
            ).id.value

    private fun search(filter: DocumentDto.DocumentSearchFilterDto): List<Long> {
        val page = kotlinx.coroutines.runBlocking { GlobalContext.get().get<DocumentManagementService>().searchDocuments(filter) }
        assertEquals(page.items.size.toLong(), page.total, "search and count must apply the same predicate")
        return page.items.map { it.id }.sorted()
    }

    private val uploadedApril = LocalDateTime(2026, 4, 10, 9, 0)

    @Test
    fun `an invoice dated in March but uploaded in April is found in March, not April`() {
        var dated = 0L
        var scan = 0L
        testApp(
            setupData = {
                adminId = TestData.createAdmin().id.value
                transaction {
                    dated = document("rechnung-maerz.pdf", uploadedApril)
                    repo.createInvoice(
                        sourceDocumentId = dated,
                        invoiceNumber = "RE-MAERZ",
                        issueDate = LocalDateTime(2026, 3, 20, 0, 0),
                        taxInclusiveTotal = BigDecimal("10.00"),
                    )
                    // A plain scan: no date of its own, so its upload date (April) stands in.
                    scan = document("scan-ohne-datum.pdf", uploadedApril)
                }
            },
        ) { _ ->
            assertEquals(listOf(dated), search(DocumentDto.DocumentSearchFilterDto(month = "2026-03")))
            assertEquals(listOf(scan), search(DocumentDto.DocumentSearchFilterDto(month = "2026-04")))

            // The same expression drives the explicit bounds, and the end day is inclusive.
            assertEquals(
                listOf(dated),
                search(DocumentDto.DocumentSearchFilterDto(fromDate = "2026-03-01", toDate = "2026-03-20")),
            )
            assertEquals(
                emptyList(),
                search(DocumentDto.DocumentSearchFilterDto(fromDate = "2026-03-21", toDate = "2026-03-31")),
            )
            assertEquals(
                listOf(dated, scan).sorted(),
                search(DocumentDto.DocumentSearchFilterDto(fromDate = "2026-03-01", toDate = "2026-04-30")),
            )
        }
    }

    @Test
    fun `a bank statement is filed under its statement date`() {
        var statementDoc = 0L
        testApp(
            setupData = {
                adminId = TestData.createAdmin().id.value
                transaction {
                    statementDoc = document("auszug.pdf", uploadedApril, fileType = "BANK_STATEMENT")
                    repo.createBankStatement(
                        sourceDocumentId = statementDoc,
                        statementDate = LocalDateTime(2026, 2, 28, 0, 0),
                    )
                }
            },
        ) { _ ->
            assertEquals(listOf(statementDoc), search(DocumentDto.DocumentSearchFilterDto(month = "2026-02", fileType = "BANK_STATEMENT")))
            assertEquals(emptyList(), search(DocumentDto.DocumentSearchFilterDto(month = "2026-04", fileType = "BANK_STATEMENT")))
        }
    }
}
