package org.example.project.admin.features.tse

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.tse.presentation.TseFindingsViewModel
import org.example.project.core.domain.dto.tse.TseExplanationDto
import org.example.project.core.domain.enums.tse.TseExplanationCategory
import org.example.project.core.domain.enums.tse.TseFindingKind
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.PosApiException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TseFindingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val gap = TseExplanationDto.FindingDto("AA", 3, 5, TseFindingKind.GAP)

    @Test
    fun loadFillsTheFindings() =
        runTest(dispatcher) {
            val api =
                FakeTseFindingsApiClient().apply {
                    response = TseExplanationDto.FindingsResponseDto(listOf(gap), emptyList())
                }
            val vm = TseFindingsViewModel(api)
            vm.load()
            advanceUntilIdle()
            assertEquals(listOf(gap), vm.state.findings)
            assertFalse(vm.state.isLoading)
        }

    @Test
    fun aBlankExplanationNeverReachesTheApi() =
        runTest(dispatcher) {
            // The client half of CLAUDE.md rule 8: the server refuses the same input with a 400,
            // and `CreateRequestDto.validationError()` is the single definition both levels call.
            val api = FakeTseFindingsApiClient()
            val vm = TseFindingsViewModel(api)
            vm.explain(gap, TseExplanationCategory.OTHER, "   ")
            advanceUntilIdle()
            assertEquals(AdminStringKeys.TseFindingsTextRequired, vm.state.explainError)
            assertTrue(api.explained.isEmpty())
        }

    @Test
    fun explainingAFindingSendsItsRangeAndReloads() =
        runTest(dispatcher) {
            val api = FakeTseFindingsApiClient()
            val vm = TseFindingsViewModel(api)
            vm.explain(gap, TseExplanationCategory.TSE_OUTAGE, "Stromausfall")
            advanceUntilIdle()
            val sent = api.explained.single()
            assertEquals(3L to 5L, sent.tanrFrom to sent.tanrTo)
            assertEquals("AA", sent.tseSerialHex)
            assertNull(vm.state.explainError)
            // The screen closes the dialog on this count (E2E round 4, R4-7).
            assertEquals(1, vm.state.explainAcceptedCount)
        }

    @Test
    fun aServerRefusalOfTheContentStaysNextToTheInput() =
        runTest(dispatcher) {
            // Routing a 400 to `error` would lose the operator's text behind a global toast. A refused
            // supersedesId (an entry already corrected, a different range) arrives exactly so.
            val api =
                FakeTseFindingsApiClient().apply {
                    failWith = PosApiException(PosErrorCode.VALIDATION_FAILED, 400, "already superseded", null)
                }
            val vm = TseFindingsViewModel(api)
            vm.explain(gap, TseExplanationCategory.OTHER, "Nachtrag")
            advanceUntilIdle()
            assertEquals(AdminStringKeys.TseFindingsNotAccepted, vm.state.explainError)
            assertNull(vm.state.error, "a content refusal is not a global error")
            assertFalse(vm.state.isBusy)
            assertEquals(0, vm.state.explainAcceptedCount, "a refusal must not close the dialog")
        }

    @Test
    fun anythingButAContentRefusalIsAGlobalError() =
        runTest(dispatcher) {
            val api =
                FakeTseFindingsApiClient().apply {
                    failWith = PosApiException(PosErrorCode.AUTH_FORBIDDEN, 403, "nope", null)
                }
            val vm = TseFindingsViewModel(api)
            vm.explain(gap, TseExplanationCategory.OTHER, "Nachtrag")
            advanceUntilIdle()
            assertNull(vm.state.explainError)
            assertTrue(vm.state.error != null, "auth, network and 500s keep their global error and retry")
        }

    @Test
    fun aRefusalThatIsNotABlankTextGetsItsOwnMessage() =
        runTest(dispatcher) {
            // An inverted range is not an empty one; reporting it as "enter an explanation" would
            // send the operator looking for a problem that is not there.
            val api = FakeTseFindingsApiClient()
            val vm = TseFindingsViewModel(api)
            vm.explain(gap.copy(tanrFrom = 9, tanrTo = 4), TseExplanationCategory.OTHER, "Stromausfall")
            advanceUntilIdle()
            assertEquals(AdminStringKeys.TseFindingsNotAccepted, vm.state.explainError)
            assertTrue(api.explained.isEmpty())
        }

    @Test
    fun correctingAnExplainedFindingSupersedesTheEntryItCarries() =
        runTest(dispatcher) {
            // The register is append-only: a correction is a new row naming the one it replaces,
            // never an edit of it.
            val api = FakeTseFindingsApiClient()
            val vm = TseFindingsViewModel(api)
            vm.explain(gap.copy(explanationId = 42L), TseExplanationCategory.OTHER, "Nachtrag")
            advanceUntilIdle()
            assertEquals(42L, api.explained.single().supersedesId)
        }
}
