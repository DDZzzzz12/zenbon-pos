package org.example.project.admin.features.documents

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import org.example.project.admin.features.documents.presentation.DocumentSearchFilter
import org.example.project.admin.features.documents.presentation.DocumentsViewModel
import org.example.project.admin.features.documents.presentation.applyPanelFilter
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.enums.document.ProcessingStatus
import org.example.project.features.reports.presentation.ReportsGranularity
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The Belege filter panel hands its whole draft to [applyPanelFilter], which must reach exactly that
 * filter through the unchanged [DocumentsViewModel] API — period, type, inactive, the seven advanced
 * fields and the statuses — without touching the live search term.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DocumentFilterApplyTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var api: FakeDocumentManagementApiClient
    private lateinit var vm: DocumentsViewModel

    private val empty =
        DocumentDto.DocumentListResponseDto(items = emptyList(), total = 0, offset = 0, limit = 50, hasMore = false)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        api = FakeDocumentManagementApiClient()
        listOf(null, "INVOICE").forEach { type ->
            api.respondTo(type) { empty }
            api.respondTo(type, listOf(ProcessingStatus.REVIEWED)) { empty }
            // The request lists statuses in declaration order (statusesForRequest).
            api.respondTo(type, listOf(ProcessingStatus.FAILED, ProcessingStatus.REVIEWED)) { empty }
        }
        vm = DocumentsViewModel(api)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun everyPanelFieldReachesTheAppliedFilterInOneApply() =
        runTest(dispatcher) {
            val target =
                vm.state.applied.copy(
                    granularity = ReportsGranularity.CUSTOM,
                    customFrom = LocalDate(2026, 1, 5),
                    customTo = LocalDate(2026, 2, 20),
                    fileType = "INVOICE",
                    includeInactive = true,
                    vendorName = "Metro",
                    invoiceNumber = "RE-1",
                    minAmount = "10.00",
                    maxAmount = "99.90",
                    bankName = "Sparkasse",
                    statementNumber = "7",
                    uploadedBy = "admin",
                    processingStatuses = setOf(ProcessingStatus.FAILED, ProcessingStatus.REVIEWED),
                )
            vm.applyPanelFilter(target)
            advanceUntilIdle()
            assertEquals(target, vm.state.applied)
        }

    @Test
    fun anAnchoredPeriodChangeIsApplied() =
        runTest(dispatcher) {
            val anchor = LocalDate(2025, 6, 2)
            vm.applyPanelFilter(vm.state.applied.copy(granularity = ReportsGranularity.WEEK, anchor = anchor))
            assertEquals(ReportsGranularity.WEEK, vm.state.applied.granularity)
            assertEquals(anchor, vm.state.applied.anchor)
        }

    @Test
    fun theCustomSegmentWithoutARangeIsAppliedAsNoDateFilter() =
        runTest(dispatcher) {
            vm.applyPanelFilter(vm.state.applied.copy(granularity = ReportsGranularity.CUSTOM))
            assertEquals(ReportsGranularity.CUSTOM, vm.state.applied.granularity)
            assertNull(vm.state.applied.customFrom)
        }

    @Test
    fun theLiveSearchTermIsNeverTouched() =
        runTest(dispatcher) {
            vm.updateSearchTerm("metro")
            vm.applyPanelFilter(vm.state.applied.copy(searchTerm = "stale", vendorName = "X"))
            assertEquals("metro", vm.state.applied.searchTerm)
            assertEquals("X", vm.state.applied.vendorName)
        }

    @Test
    fun clearingFieldsInThePanelClearsThemFromTheAppliedFilter() =
        runTest(dispatcher) {
            vm.applyPanelFilter(
                vm.state.applied.copy(vendorName = "Metro", includeInactive = true, processingStatuses = setOf(ProcessingStatus.REVIEWED)),
            )
            vm.applyPanelFilter(
                vm.state.applied
                    .clearAllAdvanced()
                    .copy(includeInactive = false),
            )
            assertNull(vm.state.applied.vendorName)
            assertEquals(emptySet(), vm.state.applied.processingStatuses)
            assertEquals(false, vm.state.applied.includeInactive)
        }

    @Test
    fun aStaleViewModelDraftDoesNotLeakIntoTheApply() =
        runTest(dispatcher) {
            // The old inline panel edited the view model's own draft; leftovers there must not ride along.
            vm.updateDraft(DocumentSearchFilter.AdvancedField.VENDOR_NAME, "Stale")
            vm.toggleDraftStatus(ProcessingStatus.NONE)
            vm.applyPanelFilter(vm.state.applied.copy(invoiceNumber = "1", processingStatuses = setOf(ProcessingStatus.REVIEWED)))
            assertNull(vm.state.applied.vendorName)
            assertEquals("1", vm.state.applied.invoiceNumber)
            assertEquals(setOf(ProcessingStatus.REVIEWED), vm.state.applied.processingStatuses)
        }

    @Test
    fun anUnchangedDraftSendsNoRequest() =
        runTest(dispatcher) {
            vm.search()
            advanceUntilIdle()
            val before = api.callCount
            vm.applyPanelFilter(vm.state.applied)
            advanceUntilIdle()
            assertEquals(before, api.callCount)
        }
}
