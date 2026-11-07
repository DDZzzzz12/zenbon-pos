package org.example.project.admin.features.terminal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.settings.FakeShopConfigApiClient
import org.example.project.admin.features.terminal.presentation.PendingTseAdminAction
import org.example.project.admin.features.terminal.presentation.TerminalsState
import org.example.project.admin.features.terminal.presentation.TerminalsViewModel
import org.example.project.admin.features.terminal.presentation.TseRegistrationOnPairing
import org.example.project.admin.features.terminal.presentation.browserPairingLink
import org.example.project.admin.features.terminal.presentation.tseDeregisterBlockReason
import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.terminal.TerminalRole
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.NetworkApiException
import org.example.project.core.network.error.PosApiException
import org.example.project.core.presentation.error.UiError
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var terminalApi: FakeTerminalApiClient
    private lateinit var tseApi: FakeTseAdminApiClient
    private lateinit var shopConfig: FakeShopConfigApiClient
    private lateinit var vm: TerminalsViewModel

    private fun locked() =
        PosApiException(
            code = PosErrorCode.TSE_ADMIN_LOCKED,
            httpStatus = 423,
            message = "locked",
            traceId = null,
        )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        terminalApi = FakeTerminalApiClient()
        tseApi = FakeTseAdminApiClient()
        shopConfig = FakeShopConfigApiClient()
        vm = TerminalsViewModel(terminalApi, tseApi, shopConfig)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun data() = vm.state as TerminalsState.Data

    @Test
    fun `refresh loads the terminal list`() =
        runTest(dispatcher) {
            terminalApi.terminals = listOf(TerminalTestFixtures.summary(1), TerminalTestFixtures.summary(2))
            vm.refresh()
            advanceUntilIdle()
            assertEquals(listOf(1L, 2L), data().all.map { it.id })
            assertEquals(2, data().total)
        }

    @Test
    fun `refresh keeps the applied search, status filter and sort`() =
        runTest(dispatcher) {
            terminalApi.terminals = listOf(TerminalTestFixtures.summary(1), TerminalTestFixtures.summary(2))
            vm.refresh()
            advanceUntilIdle()
            vm.onSearch("till")
            vm.onFilter(TerminalsState.TerminalFilter.INACTIVE)
            vm.onSort(TerminalsState.TerminalSort.NAME)
            vm.onSort(TerminalsState.TerminalSort.NAME) // second click reverses

            vm.refresh()
            advanceUntilIdle()

            assertEquals("till", data().search)
            assertEquals(TerminalsState.TerminalFilter.INACTIVE, data().filter)
            assertEquals(TerminalsState.TerminalSort.NAME, data().sort)
            assertEquals(false, data().sortAscending)
        }

    @Test
    fun `refresh maps a NetworkApiException to a NETWORK error state`() =
        runTest(dispatcher) {
            terminalApi.getAllTerminalsError = { throw NetworkApiException(RuntimeException("down")) }
            vm.refresh()
            advanceUntilIdle()
            assertIs<UiError.Network>((vm.state as TerminalsState.Error).error)
        }

    @Test
    fun `createTerminal closes the dialog, reloads, and shows a pairing code`() =
        runTest(dispatcher) {
            terminalApi.terminals = emptyList()
            vm.refresh()
            advanceUntilIdle()
            terminalApi.createResult = TerminalTestFixtures.response(7)
            terminalApi.terminals = listOf(TerminalTestFixtures.summary(7))
            terminalApi.pairingCodeResult = TerminalTestFixtures.code(7, "482913")
            vm.openCreate()
            vm.onCreateField { it.copy(name = "Bar till", role = TerminalRole.ORDER_STATION) }

            vm.submitCreate()
            advanceUntilIdle()
            assertNotNull(vm.pairingOptions, "creating opens the pairing-code dialog")
            vm.confirmPairingCode()
            advanceUntilIdle()

            assertNull(vm.createDraft)
            assertEquals(listOf(7L), data().all.map { it.id })
            assertEquals("482913", vm.pairing?.code?.code)
            assertEquals("ORDER_STATION", terminalApi.lastCreateRequest?.role)
            assertNull(terminalApi.lastCreateRequest?.fiscal) // server derives from role
        }

    @Test
    fun `createTerminal error keeps the dialog open with a typed error`() =
        runTest(dispatcher) {
            terminalApi.terminals = emptyList()
            vm.refresh()
            advanceUntilIdle()
            terminalApi.createError = { throw PosApiException(PosErrorCode.TERMINAL_NOT_FOUND, 404, "no", null) }
            vm.openCreate()
            vm.submitCreate()
            advanceUntilIdle()
            assertNotNull(vm.createDraft)
            assertIs<UiError.FromServer>(vm.createDraft?.error)
            assertEquals(0, terminalApi.generatePairingCodeCallCount)
        }

    @Test
    fun `generatePairingCode stores the code and clears the per-row flag`() =
        runTest(dispatcher) {
            terminalApi.terminals = listOf(TerminalTestFixtures.summary(3))
            vm.refresh()
            advanceUntilIdle()
            terminalApi.pairingCodeResult = TerminalTestFixtures.code(3, "112233")
            vm.openPairingOptions(3L)
            advanceUntilIdle()
            vm.confirmPairingCode()
            advanceUntilIdle()
            assertEquals("112233", vm.pairing?.code?.code)
            assertNull(data().generatingCodeForId)
            assertNull(vm.pairingOptions)
        }

    private fun available() = TerminalDto.TsePairingStatusDto(mode = TseMode.NORMAL, initialized = true, available = true)

    private suspend fun kotlinx.coroutines.test.TestScope.openOptionsFor(summary: TerminalDto.TerminalSummaryDto) {
        terminalApi.terminals = listOf(summary)
        vm.refresh()
        advanceUntilIdle()
        vm.openPairingOptions(summary.id)
        advanceUntilIdle()
    }

    @Test
    fun `the TSE box is ticked by default for a fiscal unregistered terminal on a ready TSE, and sent`() =
        runTest(dispatcher) {
            terminalApi.tsePairingStatus = available()
            openOptionsFor(TerminalTestFixtures.summary(3, fiscal = true, tse = false))

            assertEquals(TseRegistrationOnPairing.AVAILABLE, vm.pairingOptions?.availability)
            assertEquals(true, vm.pairingOptions?.registerTseClient)

            vm.confirmPairingCode()
            advanceUntilIdle()
            assertEquals(true, terminalApi.lastPairingCodeRegisterTse)
        }

    @Test
    fun `the operator may untick the TSE box, and then no registration is asked for`() =
        runTest(dispatcher) {
            terminalApi.tsePairingStatus = available()
            openOptionsFor(TerminalTestFixtures.summary(3))

            vm.onRegisterTseClient(false)
            vm.confirmPairingCode()
            advanceUntilIdle()

            assertEquals(false, terminalApi.lastPairingCodeRegisterTse)
            assertNotNull(vm.pairing)
        }

    @Test
    fun `the TSE box is off and locked when the TSE is not ready, already registered, or the terminal is not fiscal`() =
        runTest(dispatcher) {
            // simulation / not commissioned — the fake's default status
            openOptionsFor(TerminalTestFixtures.summary(3))
            assertEquals(TseRegistrationOnPairing.TSE_NOT_READY, vm.pairingOptions?.availability)
            vm.onRegisterTseClient(true) // a disabled box cannot be ticked
            assertEquals(false, vm.pairingOptions?.requestsTseRegistration)
            vm.closePairingOptions()

            terminalApi.tsePairingStatus = available()
            openOptionsFor(TerminalTestFixtures.summary(4, tse = true))
            assertEquals(TseRegistrationOnPairing.ALREADY_REGISTERED, vm.pairingOptions?.availability)
            assertEquals(false, vm.pairingOptions?.registerTseClient)
            vm.closePairingOptions()

            openOptionsFor(TerminalTestFixtures.summary(5, fiscal = false))
            assertEquals(TseRegistrationOnPairing.NOT_FISCAL, vm.pairingOptions?.availability)
            vm.closePairingOptions()

            terminalApi.tsePairingStatusError = { throw NetworkApiException(RuntimeException("down")) }
            openOptionsFor(TerminalTestFixtures.summary(6))
            assertEquals(TseRegistrationOnPairing.STATUS_UNKNOWN, vm.pairingOptions?.availability)

            vm.confirmPairingCode()
            advanceUntilIdle()
            assertEquals(false, terminalApi.lastPairingCodeRegisterTse)
        }

    @Test
    fun `a 423 on a registering pairing code prompts for the PIN, then unlock retries the code`() =
        runTest(dispatcher) {
            terminalApi.tsePairingStatus = available()
            openOptionsFor(TerminalTestFixtures.summary(3))
            terminalApi.pairingCodeError = { throw locked() }

            vm.confirmPairingCode()
            advanceUntilIdle()

            assertEquals(PendingTseAdminAction.ISSUE_PAIRING_CODE, vm.unlock?.pendingAction)
            assertNull(vm.pairing)
            assertEquals(0, terminalApi.registerCallCount)

            terminalApi.pairingCodeError = null
            terminalApi.pairingCodeResult = TerminalTestFixtures.code(3, "998877")
            vm.onUnlockPin("123456")
            vm.unlockAndRetry()
            advanceUntilIdle()

            assertNull(vm.unlock)
            assertEquals("998877", vm.pairing?.code?.code)
            assertEquals(true, terminalApi.lastPairingCodeRegisterTse)
            assertEquals(2, terminalApi.generatePairingCodeCallCount)
            assertEquals(0, terminalApi.registerCallCount, "the retry re-issues the code, it does not call tse-register")
        }

    @Test
    fun `revokeBinding reloads the list and clears the busy flag`() =
        runTest(dispatcher) {
            terminalApi.terminals = listOf(TerminalTestFixtures.summary(5))
            vm.refresh()
            advanceUntilIdle()
            terminalApi.revokeResult = TerminalTestFixtures.summary(5, active = false)
            terminalApi.terminals = listOf(TerminalTestFixtures.summary(5, active = false))
            vm.revokeBinding(5L)
            advanceUntilIdle()
            assertEquals(1, terminalApi.revokeBindingCallCount)
            assertNull(data().busyTerminalId)
            assertTrue(data().all.none { it.isActive })
        }

    @Test
    fun `deactivate calls the API, reloads and clears the busy flag`() =
        runTest(dispatcher) {
            terminalApi.terminals = listOf(TerminalTestFixtures.summary(5))
            vm.refresh()
            advanceUntilIdle()
            terminalApi.terminals = listOf(TerminalTestFixtures.summary(5, active = false))
            vm.deactivate(5L)
            advanceUntilIdle()
            assertEquals(1, terminalApi.deactivateCallCount)
            assertNull(data().busyTerminalId)
            assertTrue(data().all.none { it.isActive })
        }

    @Test
    fun `reactivate calls the API and reloads`() =
        runTest(dispatcher) {
            terminalApi.terminals = listOf(TerminalTestFixtures.summary(5, active = false))
            vm.refresh()
            advanceUntilIdle()
            terminalApi.terminals = listOf(TerminalTestFixtures.summary(5))
            vm.reactivate(5L)
            advanceUntilIdle()
            assertEquals(1, terminalApi.reactivateCallCount)
            assertNull(data().busyTerminalId)
            assertTrue(data().all.all { it.isActive })
        }

    @Test
    fun `registerTerminalTse on 423 opens the unlock dialog for the pending terminal`() =
        runTest(dispatcher) {
            terminalApi.terminals = listOf(TerminalTestFixtures.summary(9))
            vm.refresh()
            advanceUntilIdle()
            terminalApi.registerError = { throw locked() }
            vm.registerTerminalTse(9L)
            advanceUntilIdle()
            assertEquals(9L, vm.unlock?.pendingTerminalId)
            assertNull(data().busyTerminalId)
            assertNull(data().actionError) // the 423 is the prompt, not an error toast
        }

    @Test
    fun `a failed tse-register leaves an actionError the screen can clear without losing the list`() =
        runTest(dispatcher) {
            terminalApi.terminals = listOf(TerminalTestFixtures.summary(4, name = "Bar"))
            vm.refresh()
            advanceUntilIdle()
            vm.select(4)
            vm.onSearch("bar")
            // the 409 an operator actually hits: TSE switched off, "Bei TSE registrieren" pressed anyway
            terminalApi.registerError = { throw PosApiException(PosErrorCode.TSE_DISABLED, 409, "tse off", null) }

            vm.registerTerminalTse(4L)
            advanceUntilIdle()

            val failure = assertIs<UiError.FromServer>(data().actionError)
            assertEquals(PosErrorCode.TSE_DISABLED, failure.code)

            // the screen's PosErrorWatcher clears the error once it has been shown; clearing must
            // not reset the rest of the Data state out from under the operator
            vm.clearActionError()
            assertNull(data().actionError)
            assertEquals(4L, data().selectedId)
            assertEquals("bar", data().search)
            assertEquals(listOf(4L), data().all.map { it.id })
        }

    @Test
    fun `unlockAndRetry unlocks then retries registration and clears the draft`() =
        runTest(dispatcher) {
            terminalApi.terminals = listOf(TerminalTestFixtures.summary(9))
            vm.refresh()
            advanceUntilIdle()
            terminalApi.registerError = { throw locked() } // first attempt hits the locked sitting
            vm.registerTerminalTse(9L)
            advanceUntilIdle()
            // the 423 opened the unlock dialog; prepare the successful retry (mutate the hook between calls)
            terminalApi.registerError = null
            terminalApi.registerResult = TerminalTestFixtures.summary(9, tse = true)
            terminalApi.terminals = listOf(TerminalTestFixtures.summary(9, tse = true))
            vm.onUnlockPin("12345")
            vm.unlockAndRetry()
            advanceUntilIdle()
            assertEquals(1, tseApi.unlockAdminCallCount)
            assertEquals("12345", tseApi.lastUnlockPin)
            assertEquals(2, terminalApi.registerCallCount) // first 423, then retry
            assertNull(vm.unlock)
            assertTrue(data().all.first { it.id == 9L }.tseClientRegistered)
        }

    @Test
    fun `unlockAndRetry surfaces an unlock failure on the draft without retrying`() =
        runTest(dispatcher) {
            terminalApi.terminals = listOf(TerminalTestFixtures.summary(9))
            vm.refresh()
            advanceUntilIdle()
            terminalApi.registerError = { throw locked() }
            vm.registerTerminalTse(9L)
            advanceUntilIdle()
            val callsBefore = terminalApi.registerCallCount
            tseApi.unlockAdminResult = Result.failure(PosApiException(PosErrorCode.AUTH_FORBIDDEN, 403, "bad pin", null))
            vm.onUnlockPin("00000")
            vm.unlockAndRetry()
            advanceUntilIdle()
            assertNotNull(vm.unlock)
            assertIs<UiError.FromServer>(vm.unlock?.error)
            assertEquals(callsBefore, terminalApi.registerCallCount) // no retry after failed unlock
        }

    @Test
    fun `unlockAndRetry keeps the draft when the server opens no sitting`() =
        runTest(dispatcher) {
            terminalApi.terminals = listOf(TerminalTestFixtures.summary(9))
            vm.refresh()
            advanceUntilIdle()
            terminalApi.registerError = { throw locked() }
            vm.registerTerminalTse(9L)
            advanceUntilIdle()
            val callsBefore = terminalApi.registerCallCount
            // 200 OK, but the sitting is not actually open — retrying would just bounce off another 423.
            tseApi.unlockAdminResult =
                Result.success(
                    TseDto.TseAdminSessionStatusDto(unlocked = false, idleTimeoutSeconds = 600),
                )
            vm.onUnlockPin("00000")
            vm.unlockAndRetry()
            advanceUntilIdle()
            assertNotNull(vm.unlock)
            val unlockError = assertIs<UiError.FromServer>(vm.unlock?.error)
            assertEquals(PosErrorCode.TSE_INVALID_PIN, unlockError.code)
            assertEquals("", vm.unlock?.pin)
            assertEquals(callsBefore, terminalApi.registerCallCount)
        }

    @Test
    fun `submitCreate surfaces a post-create reload failure and still issues the code`() =
        runTest(dispatcher) {
            terminalApi.terminals = listOf(TerminalTestFixtures.summary(1))
            vm.refresh()
            advanceUntilIdle()
            terminalApi.createResult = TerminalTestFixtures.response(7)
            terminalApi.getAllTerminalsError = { throw NetworkApiException(RuntimeException("down")) }
            vm.openCreate()
            vm.submitCreate()
            advanceUntilIdle()
            // the terminal exists, so the draft is gone — the error must land on the screen instead
            assertNull(vm.createDraft)
            assertIs<UiError.Network>(data().actionError)
            assertEquals(7L, vm.pairingOptions?.terminalId) // the operator still gets to issue the code
            vm.confirmPairingCode()
            advanceUntilIdle()
            assertNotNull(vm.pairing)
            assertEquals(7L, terminalApi.lastPairingCodeId)
        }

    @Test
    fun `saveEdit sends the edited fields and clears the draft`() =
        runTest(dispatcher) {
            terminalApi.terminals = listOf(TerminalTestFixtures.summary(1, name = "Bar"))
            vm.refresh()
            advanceUntilIdle()
            vm.select(1)
            vm.beginEdit()
            // the draft starts from the row, so an untouched field must still be sent as it stands
            assertEquals("Bar", data().edit?.name)
            vm.onEditField { it.copy(name = "Tresen", location = "Innen", role = TerminalRole.ORDER_STATION) }
            vm.saveEdit()
            advanceUntilIdle()
            val (id, request) = assertNotNull(terminalApi.lastUpdate)
            assertEquals(1L, id)
            assertEquals("Tresen", request.name)
            assertEquals("Innen", request.location)
            assertEquals(TerminalRole.ORDER_STATION.name, request.role)
            assertNull(data().edit)
        }

    @Test
    fun `a failed save keeps the draft so the edit is not lost`() =
        runTest(dispatcher) {
            terminalApi.terminals = listOf(TerminalTestFixtures.summary(1))
            vm.refresh()
            advanceUntilIdle()
            vm.select(1)
            vm.beginEdit()
            vm.onEditField { it.copy(name = "Tresen") }
            terminalApi.updateError = NetworkApiException(RuntimeException("down"))
            vm.saveEdit()
            advanceUntilIdle()
            assertEquals("Tresen", data().edit?.name)
            assertIs<UiError.Network>(data().edit?.error)
            assertEquals(false, data().edit?.saving)
        }

    @Test
    fun `search and filter narrow the visible rows without touching the header counts`() =
        runTest(dispatcher) {
            terminalApi.terminals =
                listOf(
                    TerminalTestFixtures.summary(1, name = "Bar"),
                    TerminalTestFixtures.summary(2, active = false, name = "Terrasse"),
                    TerminalTestFixtures.summary(3, name = null, pairingCodeExpiresAt = "2026-08-27T12:00:00Z"),
                )
            vm.refresh()
            advanceUntilIdle()
            vm.onSearch("terr")
            assertEquals(listOf(2L), data().visible.map { it.id })
            vm.onSearch("")
            vm.onFilter(TerminalsState.TerminalFilter.PAIRING_OPEN)
            assertEquals(listOf(3L), data().visible.map { it.id })
            // the header reports the fleet, not the filtered view
            assertEquals(3, data().total)
            assertEquals(2, data().activeCount)
        }

    @Test
    fun `sorting by the same column twice reverses it`() =
        runTest(dispatcher) {
            terminalApi.terminals =
                listOf(TerminalTestFixtures.summary(1, name = "Zeta"), TerminalTestFixtures.summary(2, name = "Alpha"))
            vm.refresh()
            advanceUntilIdle()
            vm.onSort(TerminalsState.TerminalSort.NAME)
            assertEquals(listOf(2L, 1L), data().visible.map { it.id })
            vm.onSort(TerminalsState.TerminalSort.NAME)
            assertEquals(listOf(1L, 2L), data().visible.map { it.id })
        }

    @Test
    fun `a name defaulted to the terminal number sorts and reads as unnamed`() =
        runTest(dispatcher) {
            // provisioning defaults name to the clientId, so most terminals arrive like this
            terminalApi.terminals =
                listOf(
                    TerminalTestFixtures.summary(1, name = "T001"),
                    TerminalTestFixtures.summary(2, name = "Alpha"),
                )
            vm.refresh()
            advanceUntilIdle()
            vm.onSort(TerminalsState.TerminalSort.NAME)
            // the placeholder sorts last with the unnamed rather than first under "T"
            assertEquals(listOf(2L, 1L), data().visible.map { it.id })
        }

    // ---------- F68: TSE-Client abmelden ----------

    private fun TestScope.loaded(vararg terminals: TerminalDto.TerminalSummaryDto) {
        terminalApi.terminals = terminals.toList()
        vm.refresh()
        advanceUntilIdle()
    }

    @Test
    fun `deregister is offered only for a deactivated, unbound terminal`() {
        assertEquals(
            AdminStringKeys.TerminalTseDeregisterNeedsDeactivate,
            tseDeregisterBlockReason(TerminalTestFixtures.summary(1, active = true, tse = true)),
        )
        assertEquals(
            AdminStringKeys.TerminalTseDeregisterNeedsRevoke,
            tseDeregisterBlockReason(TerminalTestFixtures.summary(1, active = false, tse = true, bound = true)),
        )
        assertNull(tseDeregisterBlockReason(TerminalTestFixtures.summary(1, active = false, tse = true)))
    }

    @Test
    fun `deregisterTerminalTse calls the API for a retired terminal and reloads`() =
        runTest(dispatcher) {
            loaded(TerminalTestFixtures.summary(5, active = false, tse = true))
            terminalApi.terminals = listOf(TerminalTestFixtures.summary(5, active = false, tse = false))

            vm.deregisterTerminalTse(5L)
            advanceUntilIdle()

            assertEquals(1, terminalApi.deregisterCallCount)
            assertEquals(5L, terminalApi.lastDeregisterId)
            assertNull(data().busyTerminalId)
            assertNull(data().actionError)
            assertTrue(!data().all.single().tseClientRegistered)
        }

    @Test
    fun `deregisterTerminalTse never calls the API for an active or bound terminal`() =
        runTest(dispatcher) {
            loaded(
                TerminalTestFixtures.summary(6, active = true, tse = true),
                TerminalTestFixtures.summary(7, active = false, tse = true, bound = true),
            )

            vm.deregisterTerminalTse(6L)
            vm.deregisterTerminalTse(7L)
            advanceUntilIdle()

            assertEquals(0, terminalApi.deregisterCallCount)
        }

    @Test
    fun `a 423 on deregister prompts for the PIN, then unlock retries the deregistration`() =
        runTest(dispatcher) {
            loaded(TerminalTestFixtures.summary(8, active = false, tse = true))
            terminalApi.deregisterError = { throw locked() }

            vm.deregisterTerminalTse(8L)
            advanceUntilIdle()

            assertEquals(8L, vm.unlock?.pendingTerminalId)
            assertEquals(PendingTseAdminAction.DEREGISTER_TSE, vm.unlock?.pendingAction)
            assertNull(data().actionError) // the 423 is the prompt, not an error toast

            terminalApi.deregisterError = null
            vm.onUnlockPin("12345")
            vm.unlockAndRetry()
            advanceUntilIdle()

            assertEquals(2, terminalApi.deregisterCallCount)
            assertEquals(0, terminalApi.registerCallCount, "the retry must deregister, never register")
            assertNull(vm.unlock)
        }

    @Test
    fun `a server refusal of deregister surfaces as an actionError`() =
        runTest(dispatcher) {
            loaded(TerminalTestFixtures.summary(3, active = false, tse = true))
            terminalApi.deregisterError = {
                throw PosApiException(PosErrorCode.VALIDATION_FAILED, 409, "last registered client", null)
            }

            vm.deregisterTerminalTse(3L)
            advanceUntilIdle()

            assertEquals(PosErrorCode.VALIDATION_FAILED, assertIs<UiError.FromServer>(data().actionError).code)
            assertNull(data().busyTerminalId)
        }

    // --- browser stations -------------------------------------------------------------------

    @Test
    fun `a browser terminal whose role is not enabled is flagged inactive`() =
        runTest(dispatcher) {
            shopConfig.browserRoles = listOf("CASHIER")
            val cashier = TerminalTestFixtures.summary(1, role = "CASHIER", clientKind = "BROWSER")
            val orderStation = TerminalTestFixtures.summary(2, role = "ORDER_STATION", clientKind = "BROWSER")
            val native = TerminalTestFixtures.summary(3, role = "ORDER_STATION", clientKind = "NATIVE")
            terminalApi.terminals = listOf(cashier, orderStation, native)
            vm.refresh()
            advanceUntilIdle()
            assertEquals(setOf("CASHIER"), data().enabledBrowserRoles)
            assertEquals(false, data().isInactiveBrowserStation(cashier))
            assertEquals(true, data().isInactiveBrowserStation(orderStation))
            // A native till is not governed by the browser-station setting at all.
            assertEquals(false, data().isInactiveBrowserStation(native))
        }

    @Test
    fun `when the enabled set cannot be read no terminal is flagged inactive`() =
        runTest(dispatcher) {
            shopConfig.browserStationsError = PosApiException(PosErrorCode.AUTH_FORBIDDEN, 403, "nope", null)
            val browser = TerminalTestFixtures.summary(1, role = "CASHIER", clientKind = "BROWSER")
            terminalApi.terminals = listOf(browser)
            vm.refresh()
            advanceUntilIdle()
            assertNull(data().enabledBrowserRoles)
            assertEquals(false, data().isInactiveBrowserStation(browser))
            assertNull(data().actionError)
        }

    @Test
    fun `the pairing link is origin slash pos with the code`() {
        assertEquals("http://192.168.1.5:8080/pos/?code=ABC", browserPairingLink("http://192.168.1.5:8080", "ABC"))
        // The address field normalizes to a trailing slash; the link must not double it.
        assertEquals("http://192.168.1.5:8080/pos/?code=ABC", browserPairingLink("http://192.168.1.5:8080/", "ABC"))
    }
}
