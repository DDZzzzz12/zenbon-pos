package org.example.project.admin.features.kassenbuch

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.kassenbuch.presentation.BookManagementViewModel
import org.example.project.core.domain.dto.kassenbuch.CashBookDto
import org.example.project.core.domain.enums.kassenbuch.CashBookCloseInterval
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.PosApiException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The fiscal book's signing-till picker: proposed not saved, saved on request, hidden without TERMINAL_MANAGE. */
class SigningTerminalSelectionTest {
    private val fiscal =
        CashBookDto.ResponseDto(
            id = 1,
            name = "Ladenkasse",
            isFiscal = true,
            datevAccount = "1010",
            closeInterval = CashBookCloseInterval.MANUAL,
            isActive = true,
            sortOrder = 0,
            currentBalance = "0.0000",
            currency = "EUR",
        )
    private val candidate = CashBookDto.SigningTerminalCandidateDto(7, "Kasse 1", "TER-001")

    private fun newVm(
        api: FakeCashBookApiClient,
        canManageTerminals: Boolean,
    ) = BookManagementViewModel(
        cashBookApiClient = api,
        fiscalConfigurationApiClient = FakeFiscalConfigurationApiClient(),
        canManageTerminals = { canManageTerminals },
    )

    @Test
    fun aSingleCandidateIsProposedButNotSaved() =
        runTest {
            val api = FakeCashBookApiClient().apply { signingCandidates = listOf(candidate) }
            val vm = newVm(api, canManageTerminals = true)
            vm.loadSigningTerminal(fiscal)
            advanceUntilIdle()
            assertEquals(7L, vm.state.signingTerminal.selectedId)
            assertTrue(vm.state.signingTerminal.isProposal)
            assertTrue(api.setSigningTerminalCalls.isEmpty())
        }

    @Test
    fun savingSendsTheSelection() =
        runTest {
            val api =
                FakeCashBookApiClient(books = listOf(fiscal)).apply {
                    signingCandidates = listOf(candidate)
                    setSigningTerminalResult = fiscal.copy(signingTerminalId = 7, signingTerminalName = "Kasse 1")
                }
            val vm = newVm(api, canManageTerminals = true)
            vm.loadSigningTerminal(fiscal)
            advanceUntilIdle()
            vm.saveSigningTerminal()
            advanceUntilIdle()
            assertEquals(listOf<Pair<Long, Long?>>(1L to 7L), api.setSigningTerminalCalls)
            assertFalse(vm.state.signingTerminal.isProposal)
        }

    @Test
    fun withoutTerminalManageNothingLoads() =
        runTest {
            val api = FakeCashBookApiClient().apply { signingCandidates = listOf(candidate) }
            val vm = newVm(api, canManageTerminals = false)
            vm.loadSigningTerminal(fiscal)
            advanceUntilIdle()
            assertFalse(vm.state.signingTerminal.visible)
            vm.saveSigningTerminal()
            advanceUntilIdle()
            assertTrue(api.setSigningTerminalCalls.isEmpty())
        }

    @Test
    fun aSuccessfulSaveRaisesTheSigningTillSavedConfirmation() =
        runTest {
            val api =
                FakeCashBookApiClient(books = listOf(fiscal)).apply {
                    signingCandidates = listOf(candidate)
                    setSigningTerminalResult = fiscal.copy(signingTerminalId = 7, signingTerminalName = "Kasse 1")
                }
            val vm = newVm(api, canManageTerminals = true)
            vm.loadSigningTerminal(fiscal)
            advanceUntilIdle()
            assertNull(vm.state.successMessage)
            vm.saveSigningTerminal()
            advanceUntilIdle()
            assertEquals(AdminStringKeys.CashBookSigningTerminalSaved, vm.state.successMessage)
            vm.clearSuccessMessage()
            assertNull(vm.state.successMessage)
        }

    @Test
    fun aFailedSaveRaisesNoConfirmationButAnError() =
        runTest {
            val api =
                FakeCashBookApiClient(books = listOf(fiscal)).apply {
                    signingCandidates = listOf(candidate)
                    setSigningTerminalFailure =
                        PosApiException(
                            code = PosErrorCode.CASH_BOOK_NAME_ALREADY_TAKEN,
                            httpStatus = 409,
                            message = "refused",
                            traceId = null,
                        )
                }
            val vm = newVm(api, canManageTerminals = true)
            vm.loadSigningTerminal(fiscal)
            advanceUntilIdle()
            vm.saveSigningTerminal()
            advanceUntilIdle()
            assertNull(vm.state.successMessage)
            assertNotNull(vm.state.error)
            assertFalse(vm.state.signingTerminal.isSaving)
        }
}
