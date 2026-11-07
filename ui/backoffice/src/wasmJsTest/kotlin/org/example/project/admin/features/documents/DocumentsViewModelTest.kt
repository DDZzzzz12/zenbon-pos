package org.example.project.admin.features.documents

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.admin.features.documents.presentation.DocumentSearchFilter
import org.example.project.admin.features.documents.presentation.DocumentsViewModel
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.enums.document.ProcessingStatus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun item(id: Long) =
    DocumentDto.DocumentListItemDto(
        id = id,
        filename = "doc-$id.pdf",
        fileType = "INVOICE",
        processingStatus = ProcessingStatus.NONE,
        isActive = true,
        linkCount = 0,
    )

private fun listResponse(
    items: List<DocumentDto.DocumentListItemDto>,
    total: Long,
    hasMore: Boolean,
    offset: Int = 0,
) = DocumentDto.DocumentListResponseDto(
    items = items,
    total = total,
    offset = offset,
    limit = 50,
    hasMore = hasMore,
)

/**
 * Regression tests for the race review found in DocumentsViewModel (see the class doc): a paging
 * request must never run while a full search is in flight, and a superseded search's response must
 * never overwrite a newer one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DocumentsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var api: FakeDocumentManagementApiClient
    private lateinit var vm: DocumentsViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        api = FakeDocumentManagementApiClient()
        vm = DocumentsViewModel(api)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadMore issued while a search is in flight does not run, and the search result lands`() =
        runTest(dispatcher) {
            // Seed a completed search so hasMore is true — otherwise loadMore's own !hasMore guard
            // would reject the call for an unrelated reason and the test would prove nothing.
            api.respondTo(null) { listResponse(items = listOf(item(1)), total = 2, hasMore = true) }
            vm.search()
            advanceUntilIdle()
            assertEquals(1, api.callCount)

            // Start a new search (a type filter change) and, before it has had a chance to run on
            // the dispatcher, try to page — this is exactly the window the bug fired in.
            api.respondTo("INVOICE") {
                delay(50)
                listResponse(items = listOf(item(2)), total = 1, hasMore = false)
            }
            vm.selectType("INVOICE")
            assertTrue(vm.state.isLoading) // set synchronously before the coroutine even runs
            vm.loadMore()

            advanceUntilIdle()

            // loadMore must not have issued a request at all — no call ever carries a paging offset.
            assertEquals(2, api.callCount)
            assertTrue(api.calls.none { it.offset != 0 })
            // The search's own result is what lands — not appended to, not clobbered.
            assertEquals(listOf(2L), vm.state.items.map { it.id })
            assertFalse(vm.state.isLoading)
            assertFalse(vm.state.isLoadingMore)
        }

    @Test
    fun `a superseded search's response is discarded, only the newer result lands`() =
        runTest(dispatcher) {
            // First search is slow; let it actually start and reach its network call (genuinely "in
            // flight", not merely queued) before the second, faster search for a different filter
            // supersedes it. The newer one must win regardless of arrival order.
            api.respondTo(null) {
                delay(100)
                listResponse(items = listOf(item(1)), total = 1, hasMore = false)
            }
            vm.search()
            runCurrent()
            assertEquals(1, api.callCount) // confirms the first request really was issued

            api.respondTo("INVOICE") { listResponse(items = listOf(item(2)), total = 1, hasMore = false) }
            vm.selectType("INVOICE")

            advanceUntilIdle()

            assertEquals(listOf(2L), vm.state.items.map { it.id })
            assertFalse(vm.state.isLoading)
        }

    /**
     * Regression guard for I3: [DocumentsViewModel.applyDraft] must merge the seven advanced fields
     * onto the CURRENT `applied`, not replace it wholesale with `draft` — `draft` is a snapshot taken
     * when the panel opened, but the quick filter bar (type chips, search term, period,
     * include-inactive) stays live and editable the whole time the panel is open. Before the fix,
     * applying a draft field reverted any quick filter changed while the panel was open.
     */
    @Test
    fun `applyDraft merges the advanced field onto the current applied filter, not a stale draft snapshot`() =
        runTest(dispatcher) {
            api.respondTo(null) { listResponse(items = emptyList(), total = 0, hasMore = false) }
            api.respondTo("INVOICE") { listResponse(items = emptyList(), total = 0, hasMore = false) }

            // Quick filter set before the advanced panel is even opened.
            vm.selectType("INVOICE")
            advanceUntilIdle()

            // Opening the panel snapshots `draft` from `applied` (fileType=INVOICE at this point).
            vm.toggleAdvanced()

            // The quick filter bar stays live while the panel is open — the user changes it here.
            vm.selectType(null)
            advanceUntilIdle()

            // Edit one advanced field in the (now stale, fileType=INVOICE) draft and apply it.
            vm.updateDraft(DocumentSearchFilter.AdvancedField.VENDOR_NAME, "Acme")
            vm.applyDraft()
            advanceUntilIdle()

            // Both must survive: the quick filter change made after the panel opened...
            assertEquals(null, vm.state.applied.fileType)
            // ...and the advanced field the user just applied.
            assertEquals("Acme", vm.state.applied.vendorName)
        }

    /**
     * E2E 16.9: Belege could not be narrowed by processing status (e.g. "Geprüft"). Ticking statuses
     * in the advanced panel and applying sends them — several at once, OR-ed by the server — and a
     * status chip's remove action takes exactly that status back out.
     */
    @Test
    fun `status chips in the advanced panel reach the search request and can be removed one by one`() =
        runTest(dispatcher) {
            val both = listOf(ProcessingStatus.COMPLETED, ProcessingStatus.REVIEWED)
            api.respondTo(null) { listResponse(items = emptyList(), total = 0, hasMore = false) }
            api.respondTo(null, both) { listResponse(items = listOf(item(1), item(2)), total = 2, hasMore = false) }
            api.respondTo(null, listOf(ProcessingStatus.COMPLETED)) { listResponse(items = listOf(item(1)), total = 1, hasMore = false) }

            vm.search()
            advanceUntilIdle()
            assertEquals(null, api.calls.last().processingStatuses, "no status ticked: the archive lists everything")

            vm.toggleAdvanced()
            // Ticked in the reverse of declaration order on purpose: the request order is stable.
            vm.toggleDraftStatus(ProcessingStatus.REVIEWED)
            vm.toggleDraftStatus(ProcessingStatus.COMPLETED)
            assertEquals(emptySet(), vm.state.applied.processingStatuses, "a ticked chip is a draft until Anwenden")
            vm.applyDraft()
            advanceUntilIdle()

            assertEquals(both, api.calls.last().processingStatuses)
            assertEquals(2, vm.state.items.size)
            assertTrue(vm.state.applied.hasAny, "a status filter counts as narrowing the search")

            vm.clearStatus(ProcessingStatus.REVIEWED)
            advanceUntilIdle()

            assertEquals(listOf(ProcessingStatus.COMPLETED), api.calls.last().processingStatuses)
            assertEquals(setOf(ProcessingStatus.COMPLETED), vm.state.draft.processingStatuses, "the draft follows")

            vm.resetAdvanced()
            advanceUntilIdle()
            assertEquals(null, api.calls.last().processingStatuses, "reset clears the statuses too")
        }

    /**
     * Regression guard for M10: an appended page that comes back empty while the server still claims
     * `hasMore=true` must not leave the screen re-triggering [DocumentsViewModel.loadMore] forever at
     * the same offset — `state.items` never grows in that case, so nothing else would ever stop it.
     */
    @Test
    fun `an appended page with no items stops paging instead of looping`() =
        runTest(dispatcher) {
            api.respondTo(null) { listResponse(items = listOf(item(1)), total = 10, hasMore = true) }
            vm.search()
            advanceUntilIdle()
            assertTrue(vm.state.hasMore)

            // Server inconsistency: the next page claims hasMore=true but returns nothing.
            api.respondTo(null) { listResponse(items = emptyList(), total = 10, hasMore = true) }
            vm.loadMore()
            advanceUntilIdle()

            assertEquals(listOf(1L), vm.state.items.map { it.id })
            assertFalse(vm.state.hasMore)

            // A further loadMore() must be a no-op — guarded by `!hasMore` — not another request.
            val callsBefore = api.callCount
            vm.loadMore()
            advanceUntilIdle()
            assertEquals(callsBefore, api.callCount)
        }
}
