package org.example.project.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.document.ProcessingStatus
import org.example.project.features.document.data.repository.DocumentRepositoryImpl
import org.example.project.features.employee.domain.models.Employee
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The predicate behind the adminApp work queue.
 *
 * `processing_status` is the document's workflow state, so "show me everything still to be entered"
 * is a status query. The filter is **multi-valued** because the adminApp work queue's buckets are
 * sets: Zu erfassen = NONE/UNPROCESSABLE/FAILED, Zu prüfen = COMPLETED, Fertig = REVIEWED. One
 * request per status was the earlier design and does not survive paging — `offset`/`limit` are
 * per-request, so page 2 of a three-request merge is incoherent by construction.
 *
 * An **empty** list means "no filter", identically to null. That is normalized at the boundary (the
 * route maps a blank parameter to null) and guarded here, so a caller cannot accidentally ask for
 * "match nothing" — a state no screen can escape from.
 *
 * Both `search` and `count` are asserted on every case. They are separate methods that call the
 * same `buildSearchQuery`; if the predicate ever reached only one of them, page 2 of a filtered
 * queue would show rows the total does not account for, and that is what the paired assertion
 * catches.
 */
@DisplayName("DocumentRepository.search — processingStatus filter")
class DocumentSearchProcessingStatusTest {
    private val repo = DocumentRepositoryImpl()

    @Test
    fun `a status filter returns only the documents at that status`() {
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { _ ->
            val ids =
                transaction {
                    val toCapture = createDocument("zu-erfassen.pdf")
                    val parsed = createDocument("aus-xml.pdf")
                    val done = createDocument("geprueft.pdf")
                    repo.updateProcessingStatus(parsed, ProcessingStatus.COMPLETED)
                    repo.updateProcessingStatus(done, ProcessingStatus.REVIEWED)
                    Triple(toCapture, parsed, done)
                }
            val (toCapture, parsed, done) = ids

            transaction {
                assertEquals(
                    listOf(toCapture),
                    repo.search(processingStatuses = listOf(ProcessingStatus.NONE)).map { it.id.value },
                    "NONE is the column default and must be selectable like any other status",
                )
                assertEquals(1L, repo.count(processingStatuses = listOf(ProcessingStatus.NONE)))

                assertEquals(
                    listOf(parsed),
                    repo.search(processingStatuses = listOf(ProcessingStatus.COMPLETED)).map { it.id.value },
                )
                assertEquals(1L, repo.count(processingStatuses = listOf(ProcessingStatus.COMPLETED)))

                assertEquals(
                    listOf(done),
                    repo.search(processingStatuses = listOf(ProcessingStatus.REVIEWED)).map { it.id.value },
                )
                assertEquals(1L, repo.count(processingStatuses = listOf(ProcessingStatus.REVIEWED)))
            }
        }
    }

    @Test
    fun `a null status filter leaves every document in the result`() {
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { _ ->
            transaction {
                createDocument("ohne-status.pdf")
                val reviewed = createDocument("mit-status.pdf")
                repo.updateProcessingStatus(reviewed, ProcessingStatus.REVIEWED)
            }

            transaction {
                // The whole existing search surface has to keep behaving as if the parameter did
                // not exist — every current caller passes nothing.
                val all = repo.search().map { it.id.value }
                assertEquals(2, all.size, "an absent status filter must not filter anything")
                assertEquals(2L, repo.count())
            }
        }
    }

    @Test
    fun `a status with no documents returns an empty page and a zero total`() {
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { _ ->
            transaction { createDocument("nur-eines.pdf") }

            transaction {
                // FAILED is never written by this fixture. A status predicate that silently
                // degenerated into "match everything" would return the document here.
                assertTrue(repo.search(processingStatuses = listOf(ProcessingStatus.FAILED)).isEmpty())
                assertEquals(0L, repo.count(processingStatuses = listOf(ProcessingStatus.FAILED)))
            }
        }
    }

    @Test
    fun `a multi-status filter returns the union of those statuses`() {
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { _ ->
            val ids =
                transaction {
                    val toCapture = createDocument("zu-erfassen.pdf")
                    val notConformant = createDocument("minimum-profil.pdf")
                    val broken = createDocument("kaputt.pdf")
                    val parsed = createDocument("aus-xml.pdf")
                    repo.updateProcessingStatus(notConformant, ProcessingStatus.UNPROCESSABLE)
                    repo.updateProcessingStatus(broken, ProcessingStatus.FAILED)
                    repo.updateProcessingStatus(parsed, ProcessingStatus.COMPLETED)
                    listOf(toCapture, notConformant, broken)
                }

            transaction {
                // The "Zu erfassen" bucket. It has to be ONE query: three separate requests each
                // carry their own offset/limit, so merging them client-side is only coherent on
                // page 1 and cannot produce a usable total or hasMore at all.
                val bucket =
                    repo
                        .search(
                            processingStatuses =
                                listOf(
                                    ProcessingStatus.NONE,
                                    ProcessingStatus.UNPROCESSABLE,
                                    ProcessingStatus.FAILED,
                                ),
                        ).map { it.id.value }

                assertEquals(ids.sorted(), bucket.sorted())
                assertEquals(
                    3L,
                    repo.count(
                        processingStatuses =
                            listOf(
                                ProcessingStatus.NONE,
                                ProcessingStatus.UNPROCESSABLE,
                                ProcessingStatus.FAILED,
                            ),
                    ),
                    "count must apply the same IN predicate as search, or page 2 disagrees with the total",
                )
            }
        }
    }

    @Test
    fun `an empty status list means no filter, not match-nothing`() {
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { _ ->
            transaction {
                createDocument("eines.pdf")
                val reviewed = createDocument("zwei.pdf")
                repo.updateProcessingStatus(reviewed, ProcessingStatus.REVIEWED)
            }

            transaction {
                // Getting this backwards is easy and the symptom is an empty screen the user cannot
                // escape from: the "Alle" chip clears the bucket, and if that meant "match nothing"
                // the archive would look empty with every filter visibly off.
                assertEquals(2, repo.search(processingStatuses = emptyList()).size)
                assertEquals(2L, repo.count(processingStatuses = emptyList()))
            }
        }
    }

    /**
     * Must run inside a transaction: `uploadedBy` has to be a real employee — `create` rejects an
     * unknown UUID with `IllegalArgumentException: Employee not found` — and `contentHash` is
     * unique, hence the nanoTime suffix.
     */
    private fun createDocument(filename: String): Long {
        val uploader = Employee.all().first()
        return repo
            .create(
                filename = filename,
                fileType = "INVOICE",
                filePath = "documents/$filename",
                fileSize = 1_024L,
                uploadedAt = LocalDateTime(2026, 8, 11, 9, 0),
                uploadedBy = uploader.id.value,
                contentHash = "hash-" + System.nanoTime(),
                metadataJson = null,
                ocrText = null,
                mediaType = "application/pdf",
            ).id.value
    }
}
