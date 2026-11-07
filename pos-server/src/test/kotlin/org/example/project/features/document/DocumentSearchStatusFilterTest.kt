package org.example.project.features.document

import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.enums.document.ProcessingStatus
import org.example.project.features.document.data.repository.DocumentRepositoryImpl
import org.example.project.features.document.domain.service.DocumentManagementService
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The service half of the work-queue filter: the DTO field has to reach **both** repository calls.
 *
 * `searchDocuments` builds the page from `repository.search` and the total from `repository.count`
 * in two separate argument lists. Threading a new filter into only one of them is the easy mistake,
 * and it is invisible on page 1 — every assertion here therefore checks `total` against
 * `items.size`, the same guard `DocumentSearchNewModelFilterTest` uses.
 */
@DisplayName("DocumentManagementService.searchDocuments — processingStatus reaches search and count")
class DocumentSearchStatusFilterTest {
    private lateinit var adminId: UUID
    private val repo = DocumentRepositoryImpl()

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

    /** The file itself never has to exist. */
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
    fun `the status filter selects one bucket and leaves the rest out`() {
        var toCapture = 0L
        var reviewed = 0L
        testApp(
            setupData = {
                adminId = TestData.createAdmin().id.value
                transaction {
                    toCapture = document("zu-erfassen.pdf")
                    reviewed = document("fertig.pdf")
                    repo.updateProcessingStatus(reviewed, ProcessingStatus.REVIEWED)
                }
            },
        ) { _ ->
            val queue = search(DocumentDto.DocumentSearchFilterDto(processingStatuses = listOf(ProcessingStatus.NONE)))
            assertEquals(
                listOf(toCapture),
                queue.items.map { it.id },
                "NONE is the default a scanned invoice sits at — the Zu-erfassen bucket",
            )

            val done = search(DocumentDto.DocumentSearchFilterDto(processingStatuses = listOf(ProcessingStatus.REVIEWED)))
            assertEquals(listOf(reviewed), done.items.map { it.id })

            val failed = search(DocumentDto.DocumentSearchFilterDto(processingStatuses = listOf(ProcessingStatus.FAILED)))
            assertTrue(failed.items.isEmpty(), "no document is at FAILED in this fixture")
        }
    }

    @Test
    fun `an omitted status filter returns every document, as before`() {
        var plain = 0L
        var reviewed = 0L
        testApp(
            setupData = {
                adminId = TestData.createAdmin().id.value
                transaction {
                    plain = document("ohne-status.pdf")
                    reviewed = document("mit-status.pdf")
                    repo.updateProcessingStatus(reviewed, ProcessingStatus.REVIEWED)
                }
            },
        ) { _ ->
            val all = search(DocumentDto.DocumentSearchFilterDto())
            assertTrue(all.items.any { it.id == plain })
            assertTrue(all.items.any { it.id == reviewed })
        }
    }

    @Test
    fun `a bucket of statuses reaches search and count as one predicate`() {
        var toCapture = 0L
        var broken = 0L
        var reviewed = 0L
        testApp(
            setupData = {
                adminId = TestData.createAdmin().id.value
                transaction {
                    toCapture = document("zu-erfassen.pdf")
                    broken = document("kaputt.pdf")
                    reviewed = document("fertig.pdf")
                    repo.updateProcessingStatus(broken, ProcessingStatus.FAILED)
                    repo.updateProcessingStatus(reviewed, ProcessingStatus.REVIEWED)
                }
            },
        ) { _ ->
            // `search` asserts total == items.size internally, which is the guard that catches the
            // filter reaching repository.search but not repository.count.
            val bucket =
                search(
                    DocumentDto.DocumentSearchFilterDto(
                        processingStatuses = listOf(ProcessingStatus.NONE, ProcessingStatus.FAILED),
                    ),
                )
            assertEquals(listOf(toCapture, broken).sorted(), bucket.items.map { it.id }.sorted())

            val everything = search(DocumentDto.DocumentSearchFilterDto(processingStatuses = emptyList()))
            assertEquals(3, everything.items.size, "an empty list is no filter, not match-nothing")
        }
    }
}
