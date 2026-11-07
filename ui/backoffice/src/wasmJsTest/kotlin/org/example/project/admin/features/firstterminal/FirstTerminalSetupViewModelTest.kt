package org.example.project.admin.features.firstterminal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.firstterminal.presentation.FirstTerminalSetupViewModel
import org.example.project.admin.features.firstterminal.presentation.FirstTerminalStep
import org.example.project.admin.features.terminal.FakeTerminalApiClient
import org.example.project.admin.features.tse.FakeTseSettingsApiClient
import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.terminal.TerminalRole
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.NetworkApiException
import org.example.project.core.network.error.PosApiException
import org.example.project.core.presentation.error.UiError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TEST_SERVER_URL = "http://localhost:8080"

private fun summary(
    id: Long,
    registered: Boolean = false,
) = TerminalDto.TerminalSummaryDto(
    id = id,
    terminalId = "TER-00$id",
    terminalBrand = null,
    terminalModel = null,
    isActive = true,
    tseClientRegistered = registered,
    lastSeenAt = "2026-07-26T10:00:00",
    role = "CASHIER",
    fiscal = true,
)

private fun response(
    id: Long,
    fiscal: Boolean,
    role: String = "CASHIER",
) = TerminalDto.TerminalResponseDto(
    id = id,
    deviceId = "",
    terminalId = "TER-00$id",
    terminalBrand = null,
    terminalModel = null,
    terminalSerialNr = null,
    terminalSwBrand = "POS",
    terminalSwVersion = null,
    isActive = true,
    tseClientRegistered = false,
    registeredAt = "2026-07-26T10:00:00",
    lastSeenAt = "2026-07-26T10:00:00",
    role = role,
    fiscal = fiscal,
)

class FirstTerminalSetupViewModelTest {
    private fun vm(
        terminal: FakeTerminalApiClient = FakeTerminalApiClient(),
        tse: FakeTseSettingsApiClient = FakeTseSettingsApiClient(),
    ) = FirstTerminalSetupViewModel(
        terminalApi = terminal,
        tseSettingsApi = tse,
        serverUrlProvider = { TEST_SERVER_URL },
    )

    private fun tseInMode(mode: TseMode) = FakeTseSettingsApiClient().apply { configResult = TseConfigDto.TseConfig(mode = mode, timeAdminPin = "") }

    @Test
    fun evaluate_noTerminals_flagsNeedsFirstTerminal_andCapturesServerUrl() =
        runTest {
            val terminal = FakeTerminalApiClient().apply { terminals = emptyList() }
            val viewModel = vm(terminal = terminal)

            viewModel.evaluateFirstRun()

            val s = viewModel.state
            assertTrue(s.needsFirstTerminal)
            assertTrue(s.evaluated)
            assertEquals(TEST_SERVER_URL, s.serverUrl)
            assertEquals(FirstTerminalStep.CREATE_TERMINAL, s.step)
        }

    @Test
    fun evaluate_existingTerminals_doesNotFlag() =
        runTest {
            val terminal = FakeTerminalApiClient().apply { terminals = listOf(summary(1)) }
            val viewModel = vm(terminal = terminal)

            viewModel.evaluateFirstRun()

            assertFalse(viewModel.state.needsFirstTerminal)
            assertTrue(viewModel.state.evaluated)
        }

    @Test
    fun evaluate_swallows403_andDoesNotFlag() =
        runTest {
            val terminal =
                FakeTerminalApiClient().apply {
                    getAllTerminalsError = { throw PosApiException(PosErrorCode.AUTH_FORBIDDEN, 403, "forbidden", null) }
                }
            val viewModel = vm(terminal = terminal)

            viewModel.evaluateFirstRun()

            assertFalse(viewModel.state.needsFirstTerminal)
            assertTrue(viewModel.state.evaluated)
            assertNull(viewModel.state.error)
        }

    @Test
    fun evaluate_runsProbeOnlyOnce() =
        runTest {
            val terminal = FakeTerminalApiClient().apply { terminals = emptyList() }
            val viewModel = vm(terminal = terminal)

            viewModel.evaluateFirstRun()
            viewModel.evaluateFirstRun()

            assertEquals(1, terminal.getAllTerminalsCallCount)
        }

    @Test
    fun reset_clearsState_andReArmsProbe() =
        runTest {
            val terminal = FakeTerminalApiClient().apply { terminals = emptyList() }
            val viewModel = vm(terminal = terminal)
            viewModel.evaluateFirstRun()

            viewModel.reset()

            assertFalse(viewModel.state.needsFirstTerminal)
            assertFalse(viewModel.state.evaluated)

            viewModel.evaluateFirstRun()
            assertEquals(2, terminal.getAllTerminalsCallCount)
        }

    /** A fiscal terminal on an operating TSE, created in Step 1 — the wizard now stands on COMMISSION_TSE. */
    private fun commissionReady(
        terminal: FakeTerminalApiClient = FakeTerminalApiClient().apply { createResult = response(7, fiscal = true) },
        tse: FakeTseSettingsApiClient = FakeTseSettingsApiClient(),
    ): FirstTerminalSetupViewModel =
        vm(terminal = terminal, tse = tse).apply {
            loadTseMode()
            createTerminal()
        }

    /**
     * Owner decision 2026-10-04: the back office is no terminal, so commissioning before a terminal
     * existed answered 404 on a fresh server. The terminal now comes first and setup registers it.
     */
    @Test
    fun commission_registersTheCreatedTerminal_savesTheTimeAdminPinFirst_andSkipsRegistration() =
        runTest {
            val tse = FakeTseSettingsApiClient().apply { setupResult = TseDto.AdminResultDto(success = true, message = "commissioned") }
            val viewModel = commissionReady(tse = tse)
            assertEquals(FirstTerminalStep.COMMISSION_TSE, viewModel.state.step)
            viewModel.showTseSetup()

            viewModel.commissionTse("80417", "804172", "seed-xyz", "61023")

            val s = viewModel.state
            assertTrue(s.tseCommissioned)
            assertTrue(s.tseClientRegistered, "setup registered the terminal — Step 3 has nothing left to do")
            assertFalse(s.showTseSetupDialog)
            assertFalse(s.busy)
            assertEquals(FirstTerminalStep.ISSUE_CODE, s.step)
            assertEquals("61023", tse.updateConfigRequests.single().timeAdminPin)
            val credentials = tse.lastSetupCredentials
            assertEquals("TER-007", credentials?.terminalId)
            assertEquals("80417", credentials?.adminPin)
            assertEquals("seed-xyz", credentials?.credentialSeed)
        }

    @Test
    fun commission_successFalse_setsError_staysOnCommission() =
        runTest {
            val tse = FakeTseSettingsApiClient().apply { setupResult = TseDto.AdminResultDto(success = false, message = "bad seed") }
            val viewModel = commissionReady(tse = tse)

            viewModel.commissionTse("1", "2", "3", "61023")

            val s = viewModel.state
            assertFalse(s.tseCommissioned)
            assertEquals(FirstTerminalStep.COMMISSION_TSE, s.step)
            val error = assertIs<UiError.FromServer>(s.error)
            assertEquals(PosErrorCode.TSE_SETUP_FAILED, error.code)
            assertFalse(s.busy)
        }

    @Test
    fun commission_transportFailure_surfacesNetworkError() =
        runTest {
            val tse = FakeTseSettingsApiClient().apply { setupError = { throw NetworkApiException(RuntimeException("offline")) } }
            val viewModel = commissionReady(tse = tse)

            viewModel.commissionTse("1", "2", "3", "61023")

            assertIs<UiError.Network>(viewModel.state.error)
            assertFalse(viewModel.state.busy)
        }

    @Test
    fun commission_refusedConfigSave_neverSendsSetup() =
        runTest {
            val tse =
                FakeTseSettingsApiClient().apply {
                    updateConfigError = { throw PosApiException(PosErrorCode.VALIDATION_FAILED, 400, "TimeAdmin PIN required", null) }
                }
            val viewModel = commissionReady(tse = tse)

            viewModel.commissionTse("1", "2", "3", "61023")

            assertEquals(PosErrorCode.VALIDATION_FAILED, viewModel.state.error?.code)
            assertEquals(0, tse.setupCallCount)
            assertFalse(viewModel.state.busy)
            assertEquals(FirstTerminalStep.COMMISSION_TSE, viewModel.state.step)
        }

    @Test
    fun commission_blankOrPlaceholderTimeAdminPin_sendsNothing() =
        runTest {
            val tse = FakeTseSettingsApiClient()
            val viewModel = commissionReady(tse = tse)

            viewModel.commissionTse("80417", "804172", "seed", " ")
            viewModel.commissionTse("80417", "804172", "seed", "12345")

            assertTrue(tse.updateConfigRequests.isEmpty())
            assertEquals(0, tse.setupCallCount)
        }

    @Test
    fun commission_beforeAnyTerminalExists_isNoOp() =
        runTest {
            val tse = FakeTseSettingsApiClient()
            val viewModel = vm(tse = tse)

            viewModel.commissionTse("80417", "804172", "seed", "61023")

            assertEquals(0, tse.setupCallCount)
            assertNull(viewModel.state.error)
        }

    @Test
    fun commission_alreadyCommissioned_continuesToRegisterTheTerminal() =
        runTest {
            val tse =
                FakeTseSettingsApiClient().apply {
                    setupError = { throw PosApiException(PosErrorCode.TSE_ALREADY_INITIALIZED, 409, "already commissioned", null) }
                }
            val viewModel = commissionReady(tse = tse)

            viewModel.commissionTse("80417", "804172", "seed", "61023")

            assertEquals(FirstTerminalStep.REGISTER_TSE, viewModel.state.step)
            assertNull(viewModel.state.error)
            assertFalse(viewModel.state.showTseSetupDialog)
        }

    @Test
    fun skipTseCommission_goesToRegister_withoutCallingSetup() =
        runTest {
            val tse = FakeTseSettingsApiClient()
            val viewModel = commissionReady(tse = tse)

            viewModel.skipTseCommission()

            assertEquals(FirstTerminalStep.REGISTER_TSE, viewModel.state.step)
            assertEquals(0, tse.setupCallCount)
        }

    @Test
    fun skipTseCommission_beforeTheTerminalExists_isIgnored() =
        runTest {
            val viewModel = vm()

            viewModel.skipTseCommission()

            assertEquals(FirstTerminalStep.CREATE_TERMINAL, viewModel.state.step)
        }

    @Test
    fun create_fiscal_advancesToCommission_sendsRoleAndName() =
        runTest {
            val terminal = FakeTerminalApiClient().apply { createResult = response(7, fiscal = true, role = "CASHIER") }
            val viewModel = vm(terminal = terminal)
            viewModel.updateTerminalName("Front Till")
            viewModel.updateTerminalRole(TerminalRole.CASHIER)
            viewModel.loadTseMode()

            viewModel.createTerminal()

            val s = viewModel.state
            assertEquals(7L, s.createdTerminal?.id)
            assertEquals(FirstTerminalStep.COMMISSION_TSE, s.step)
            assertFalse(s.busy)
            assertEquals("Front Till", terminal.lastCreateRequest?.name)
            assertEquals("CASHIER", terminal.lastCreateRequest?.role)
            assertNull(terminal.lastCreateRequest?.fiscal) // fiscal defaults from role server-side
        }

    @Test
    fun create_nonFiscal_skipsRegistration_goesToIssue() =
        runTest {
            val terminal = FakeTerminalApiClient().apply { createResult = response(8, fiscal = false, role = "KITCHEN") }
            val viewModel = vm(terminal = terminal)
            viewModel.updateTerminalRole(TerminalRole.KITCHEN)
            viewModel.loadTseMode()

            viewModel.createTerminal()

            assertEquals(FirstTerminalStep.ISSUE_CODE, viewModel.state.step)
            assertEquals("KITCHEN", terminal.lastCreateRequest?.role)
        }

    @Test
    fun create_blankName_sendsNullName() =
        runTest {
            val terminal = FakeTerminalApiClient().apply { createResult = response(9, fiscal = true) }
            val viewModel = vm(terminal = terminal)
            viewModel.loadTseMode()

            viewModel.createTerminal()

            assertNull(terminal.lastCreateRequest?.name)
        }

    @Test
    fun create_failure_surfacesError() =
        runTest {
            val terminal = FakeTerminalApiClient().apply { createError = { throw NetworkApiException(RuntimeException("offline")) } }
            val viewModel = vm(terminal = terminal)
            viewModel.loadTseMode()

            viewModel.createTerminal()

            assertIs<UiError.Network>(viewModel.state.error)
            assertNull(viewModel.state.createdTerminal)
        }

    @Test
    fun register_success_marksRegistered_advancesToIssue() =
        runTest {
            val terminal =
                FakeTerminalApiClient().apply {
                    createResult = response(5, fiscal = true)
                    registerResult = summary(5, registered = true)
                }
            val viewModel = vm(terminal = terminal)
            viewModel.loadTseMode()
            viewModel.createTerminal()
            viewModel.skipTseCommission() // TSE already commissioned → Step 3

            viewModel.registerTse()

            val s = viewModel.state
            assertTrue(s.tseClientRegistered)
            assertEquals(FirstTerminalStep.ISSUE_CODE, s.step)
            assertEquals(5L, terminal.lastRegisterId)
        }

    @Test
    fun register_on423_raisesUnlockDialog_staysOnRegister() =
        runTest {
            val terminal =
                FakeTerminalApiClient().apply {
                    createResult = response(6, fiscal = true)
                    registerError = { throw PosApiException(PosErrorCode.TSE_ADMIN_LOCKED, 423, "locked", null) }
                }
            val viewModel = vm(terminal = terminal)
            viewModel.loadTseMode()
            viewModel.createTerminal()
            viewModel.skipTseCommission() // TSE already commissioned → Step 3

            viewModel.registerTse()

            val s = viewModel.state
            assertTrue(s.showAdminUnlockDialog)
            assertFalse(s.tseClientRegistered)
            assertEquals(FirstTerminalStep.REGISTER_TSE, s.step)
            assertNull(s.error) // the 423 is the prompt, not an error banner
        }

    @Test
    fun hideAdminUnlockDialog_afterLockedRegisterFailure_setsRegisterError_andDeferStillWorks() =
        runTest {
            val terminal =
                FakeTerminalApiClient().apply {
                    createResult = response(6, fiscal = true)
                    registerError = { throw PosApiException(PosErrorCode.TSE_ADMIN_LOCKED, 423, "locked", null) }
                }
            val viewModel = vm(terminal = terminal)
            viewModel.loadTseMode()
            viewModel.createTerminal()
            viewModel.skipTseCommission() // TSE already commissioned → Step 3
            viewModel.registerTse() // 423 -> opens the unlock dialog

            viewModel.hideAdminUnlockDialog()

            val afterHide = viewModel.state
            assertFalse(afterHide.showAdminUnlockDialog)
            assertEquals(FirstTerminalStep.REGISTER_TSE, afterHide.step)
            val error = assertIs<UiError.FromServer>(afterHide.registerError)
            assertEquals(PosErrorCode.TSE_ADMIN_LOCKED, error.code)
            // The card has no PIN field and nothing retries automatically — a dedicated hint, not
            // the unlock-dialog's own message, which would mislead a MANAGER with no TSE admin PIN.
            assertEquals(AdminStringKeys.TerminalSetupTseLockedHint, error.messageKey)

            viewModel.deferTseRegistration()

            assertEquals(FirstTerminalStep.ISSUE_CODE, viewModel.state.step)
        }

    @Test
    fun hideAdminUnlockDialog_afterSuccessfulUnlock_doesNotSetRegisterError() =
        runTest {
            val terminal =
                FakeTerminalApiClient().apply {
                    createResult = response(6, fiscal = true)
                    registerError = { throw PosApiException(PosErrorCode.TSE_ADMIN_LOCKED, 423, "locked", null) }
                }
            val tse = FakeTseSettingsApiClient()
            val viewModel = vm(terminal = terminal, tse = tse)
            viewModel.loadTseMode()
            viewModel.createTerminal()
            viewModel.skipTseCommission() // TSE already commissioned → Step 3
            viewModel.registerTse() // 423 -> opens the unlock dialog
            tse.unlockResult = TseDto.TseAdminSessionStatusDto(unlocked = true, idleTimeoutSeconds = 300)
            terminal.registerError = null
            terminal.registerResult = summary(6, registered = true)
            viewModel.updateAdminUnlockPin("99999")

            viewModel.unlockAndRetryRegister() // success path never routes through hideAdminUnlockDialog()

            val s = viewModel.state
            assertFalse(s.showAdminUnlockDialog)
            assertTrue(s.tseClientRegistered)
            assertEquals(FirstTerminalStep.ISSUE_CODE, s.step)
            assertNull(s.registerError)
        }

    @Test
    fun unlockAndRetry_unlocks_thenRegisters_andAdvances() =
        runTest {
            val terminal =
                FakeTerminalApiClient().apply {
                    createResult = response(6, fiscal = true)
                    registerError = { throw PosApiException(PosErrorCode.TSE_ADMIN_LOCKED, 423, "locked", null) }
                }
            val tse = FakeTseSettingsApiClient()
            val viewModel = vm(terminal = terminal, tse = tse)
            viewModel.loadTseMode()
            viewModel.createTerminal()
            viewModel.skipTseCommission() // TSE already commissioned → Step 3
            viewModel.registerTse() // 423 → opens the unlock dialog

            tse.unlockResult = TseDto.TseAdminSessionStatusDto(unlocked = true, idleTimeoutSeconds = 300)
            terminal.registerError = null
            terminal.registerResult = summary(6, registered = true)
            viewModel.updateAdminUnlockPin("99999")

            viewModel.unlockAndRetryRegister()

            val s = viewModel.state
            assertFalse(s.showAdminUnlockDialog)
            assertTrue(s.tseClientRegistered)
            assertEquals(FirstTerminalStep.ISSUE_CODE, s.step)
            assertEquals("99999", tse.lastUnlockPin)
            assertEquals(2, terminal.registerCallCount) // first 423, then retry
        }

    @Test
    fun unlockAndRetry_wrongPin_keepsDialogOpen() =
        runTest {
            val terminal =
                FakeTerminalApiClient().apply {
                    createResult = response(6, fiscal = true)
                    registerError = { throw PosApiException(PosErrorCode.TSE_ADMIN_LOCKED, 423, "locked", null) }
                }
            val tse = FakeTseSettingsApiClient()
            val viewModel = vm(terminal = terminal, tse = tse)
            viewModel.loadTseMode()
            viewModel.createTerminal()
            viewModel.skipTseCommission() // TSE already commissioned → Step 3
            viewModel.registerTse() // 423 → opens the unlock dialog

            tse.unlockResult = TseDto.TseAdminSessionStatusDto(unlocked = false, idleTimeoutSeconds = 300)
            viewModel.updateAdminUnlockPin("00000")
            viewModel.unlockAndRetryRegister()

            val s = viewModel.state
            assertFalse(s.tseClientRegistered)
            assertFalse(s.unlocking)
            assertTrue(s.showAdminUnlockDialog)
            val error = assertIs<UiError.FromServer>(s.error)
            assertEquals(PosErrorCode.TSE_INVALID_PIN, error.code)
            assertEquals(1, terminal.registerCallCount) // no retry on a failed unlock
        }

    @Test
    fun issue_storesCode_advancesToDone_clearsNeedsFlag() =
        runTest {
            val terminal =
                FakeTerminalApiClient().apply {
                    createResult = response(3, fiscal = true)
                    pairingCodeResult =
                        TerminalDto.PairingCodeResponseDto(
                            terminalDbId = 3,
                            terminalId = "TER-003",
                            code = "ABC123",
                            expiresAt = "2026-07-26T10:10:00",
                        )
                }
            val viewModel = vm(terminal = terminal)
            viewModel.loadTseMode()
            viewModel.createTerminal()

            viewModel.issuePairingCode()

            val s = viewModel.state
            assertEquals("ABC123", s.pairingCode)
            assertEquals(FirstTerminalStep.DONE, s.step)
            assertFalse(s.needsFirstTerminal) // the server now has a terminal — banner disappears
            assertEquals(3L, terminal.lastPairingCodeId)
        }

    @Test
    fun issue_failure_surfacesError_staysOnIssue() =
        runTest {
            val terminal =
                FakeTerminalApiClient().apply {
                    createResult = response(3, fiscal = true)
                    pairingCodeError = { throw NetworkApiException(RuntimeException("offline")) }
                }
            val viewModel = vm(terminal = terminal)
            viewModel.loadTseMode()
            viewModel.createTerminal()

            viewModel.issuePairingCode()

            assertNull(viewModel.state.pairingCode)
            assertIs<UiError.Network>(viewModel.state.error)
        }

    // --- TSE mode probe: a fresh install starts with the TSE DISABLED ---

    @Test
    fun loadTseMode_disabled_flagsDisabled_staysOnCreate() =
        runTest {
            val tse = tseInMode(TseMode.DISABLED)
            val viewModel = vm(tse = tse)

            viewModel.loadTseMode()

            val s = viewModel.state
            assertTrue(s.tseModeChecked)
            assertTrue(s.tseDisabled)
            assertEquals(FirstTerminalStep.CREATE_TERMINAL, s.step)
            assertEquals(1, tse.getConfigCallCount)
            assertEquals(0, tse.setupCallCount)
        }

    @Test
    fun loadTseMode_normal_staysOnCreate() =
        runTest {
            val viewModel = vm(tse = tseInMode(TseMode.NORMAL))

            viewModel.loadTseMode()

            val s = viewModel.state
            assertTrue(s.tseModeChecked)
            assertFalse(s.tseDisabled)
            assertEquals(FirstTerminalStep.CREATE_TERMINAL, s.step)
        }

    @Test
    fun loadTseMode_failure_keepsFullFlow_withoutBlocking() =
        runTest {
            val tse =
                FakeTseSettingsApiClient().apply {
                    configError = { throw PosApiException(PosErrorCode.AUTH_FORBIDDEN, 403, "forbidden", null) }
                }
            val viewModel = vm(tse = tse)

            viewModel.loadTseMode()

            val s = viewModel.state
            assertTrue(s.tseModeChecked)
            assertFalse(s.tseDisabled)
            assertEquals(FirstTerminalStep.CREATE_TERMINAL, s.step)
            assertNull(s.error)
        }

    @Test
    fun loadTseMode_afterTerminalCreated_doesNotProbe() =
        runTest {
            val terminal = FakeTerminalApiClient().apply { createResult = response(4, fiscal = true) }
            val tse = tseInMode(TseMode.NORMAL)
            val viewModel = vm(terminal = terminal, tse = tse)
            viewModel.loadTseMode()
            viewModel.createTerminal()

            // If the probe re-ran here it would see DISABLED and could rewrite the already-reached step.
            tse.configResult = TseConfigDto.TseConfig(mode = TseMode.DISABLED, timeAdminPin = "")
            viewModel.loadTseMode()

            assertEquals(1, tse.getConfigCallCount)
            assertEquals(FirstTerminalStep.COMMISSION_TSE, viewModel.state.step)
        }

    @Test
    fun loadTseMode_failedProbeAfterDisabled_clearsStaleDisabledFlag_beforeCreate() =
        runTest {
            // The VM is app-scoped: an earlier visit read DISABLED, this visit's probe fails. The
            // stale tseDisabled=true must not survive, or createTerminal() skips commissioning for a
            // fiscal terminal on a now-working TSE.
            val terminal = FakeTerminalApiClient().apply { createResult = response(9, fiscal = true) }
            val tse = tseInMode(TseMode.DISABLED)
            val viewModel = vm(terminal = terminal, tse = tse)
            viewModel.loadTseMode()
            assertTrue(viewModel.state.tseDisabled)

            tse.configError = { throw PosApiException(PosErrorCode.AUTH_FORBIDDEN, 403, "forbidden", null) }
            viewModel.loadTseMode()
            assertFalse(viewModel.state.tseDisabled)

            viewModel.createTerminal()

            assertEquals(FirstTerminalStep.COMMISSION_TSE, viewModel.state.step)
            assertEquals(0, terminal.registerCallCount) // not called yet, but the step must be reachable
        }

    @Test
    fun loadTseMode_twoQuickCalls_endsInTheSecondCallsResult() =
        runTest {
            val tse = tseInMode(TseMode.NORMAL)
            val viewModel = vm(tse = tse)

            viewModel.loadTseMode()
            tse.configResult = TseConfigDto.TseConfig(mode = TseMode.DISABLED, timeAdminPin = "")
            viewModel.loadTseMode()

            val s = viewModel.state
            assertTrue(s.tseModeChecked)
            assertTrue(s.tseDisabled)
            assertEquals(FirstTerminalStep.CREATE_TERMINAL, s.step)
            assertEquals(2, tse.getConfigCallCount)
        }

    /**
     * QA BO-29 (2026-10-06): DISABLED is the fresh-install mode and setup commissions from it, so a
     * fiscal terminal on a new server must still reach Step 2 — skipping it there meant the wizard
     * could never commission the TSE it exists to commission.
     */
    @Test
    fun create_fiscal_withTseDisabled_stillOffersCommissioning() =
        runTest {
            val terminal = FakeTerminalApiClient().apply { createResult = response(7, fiscal = true) }
            val viewModel = vm(terminal = terminal, tse = tseInMode(TseMode.DISABLED))
            viewModel.loadTseMode()

            viewModel.createTerminal()

            assertEquals(FirstTerminalStep.COMMISSION_TSE, viewModel.state.step)
            assertTrue(viewModel.state.tseDisabled)
            assertEquals(0, terminal.registerCallCount)
        }

    @Test
    fun continueWithoutTse_whileDisabled_skipsBothTseSteps_goesToIssue() =
        runTest {
            val terminal = FakeTerminalApiClient().apply { createResult = response(7, fiscal = true) }
            val tse = tseInMode(TseMode.DISABLED)
            val viewModel = vm(terminal = terminal, tse = tse)
            viewModel.loadTseMode()
            viewModel.createTerminal()

            viewModel.continueWithoutTse()

            assertEquals(FirstTerminalStep.ISSUE_CODE, viewModel.state.step)
            assertTrue(viewModel.state.tseDisabled, "the DONE note still says the TSE is disabled")
            assertEquals(0, tse.setupCallCount)
            assertEquals(0, terminal.registerCallCount)
        }

    @Test
    fun continueWithoutTse_withTseEnabled_isRefused() =
        runTest {
            val viewModel = commissionReady(tse = tseInMode(TseMode.NORMAL))

            viewModel.continueWithoutTse()

            assertEquals(FirstTerminalStep.COMMISSION_TSE, viewModel.state.step)
        }

    @Test
    fun commission_fromDisabled_clearsTheDisabledFlag() =
        runTest {
            val tse =
                tseInMode(TseMode.DISABLED).apply { setupResult = TseDto.AdminResultDto(success = true, message = "commissioned") }
            val viewModel = commissionReady(tse = tse)
            assertTrue(viewModel.state.tseDisabled)

            viewModel.commissionTse("80417", "804172", "seed-xyz", "61023")

            val s = viewModel.state
            assertEquals(FirstTerminalStep.ISSUE_CODE, s.step)
            assertTrue(s.tseCommissioned)
            assertFalse(s.tseDisabled, "setup saved the mode as NORMAL — the DONE step must not call it disabled")
            assertEquals("TER-007", tse.lastSetupCredentials?.terminalId)
        }

    @Test
    fun register_tseDisabled409_skipsRegistration_advancesToIssue() =
        runTest {
            val terminal =
                FakeTerminalApiClient().apply {
                    createResult = response(6, fiscal = true)
                    registerError = { throw PosApiException(PosErrorCode.TSE_DISABLED, 409, "TSE is disabled", null) }
                }
            val viewModel = vm(terminal = terminal)
            viewModel.loadTseMode()
            viewModel.createTerminal()
            viewModel.skipTseCommission() // TSE already commissioned → Step 3

            viewModel.registerTse()

            val s = viewModel.state
            assertTrue(s.tseDisabled)
            assertEquals(FirstTerminalStep.ISSUE_CODE, s.step)
            assertNull(s.registerError)
            assertNull(s.error)
            assertFalse(s.busy)
        }

    @Test
    fun register_otherFailure_staysOnRegister_andKeepsTheErrorVisible() =
        runTest {
            val terminal =
                FakeTerminalApiClient().apply {
                    createResult = response(6, fiscal = true)
                    registerError = { throw PosApiException(PosErrorCode.INTERNAL_ERROR, 500, "TSE rejected", null) }
                }
            val viewModel = vm(terminal = terminal)
            viewModel.loadTseMode()
            viewModel.createTerminal()
            viewModel.skipTseCommission() // TSE already commissioned → Step 3

            viewModel.registerTse()

            val s = viewModel.state
            assertEquals(FirstTerminalStep.REGISTER_TSE, s.step)
            val error = assertIs<UiError.FromServer>(s.registerError)
            assertEquals(PosErrorCode.INTERNAL_ERROR, error.code)
            assertNull(s.error) // shown inline on Step 3, not toasted away by PosErrorWatcher
            assertFalse(s.showAdminUnlockDialog)
            assertFalse(s.busy)
        }

    @Test
    fun register_retryAfterFailure_clearsRegisterError_onSuccess() =
        runTest {
            val terminal =
                FakeTerminalApiClient().apply {
                    createResult = response(6, fiscal = true)
                    registerError = { throw NetworkApiException(RuntimeException("offline")) }
                }
            val viewModel = vm(terminal = terminal)
            viewModel.loadTseMode()
            viewModel.createTerminal()
            viewModel.skipTseCommission() // TSE already commissioned → Step 3
            viewModel.registerTse()

            terminal.registerError = null
            terminal.registerResult = summary(6, registered = true)
            viewModel.registerTse()

            val s = viewModel.state
            assertNull(s.registerError)
            assertTrue(s.tseClientRegistered)
            assertEquals(FirstTerminalStep.ISSUE_CODE, s.step)
            assertEquals(2, terminal.registerCallCount)
        }

    @Test
    fun deferTseRegistration_afterFailure_movesToIssue_andClearsTheError() =
        runTest {
            val terminal =
                FakeTerminalApiClient().apply {
                    createResult = response(6, fiscal = true)
                    registerError = { throw NetworkApiException(RuntimeException("offline")) }
                }
            val viewModel = vm(terminal = terminal)
            viewModel.loadTseMode()
            viewModel.createTerminal()
            viewModel.skipTseCommission() // TSE already commissioned → Step 3
            viewModel.registerTse()

            viewModel.deferTseRegistration()

            val s = viewModel.state
            assertEquals(FirstTerminalStep.ISSUE_CODE, s.step)
            assertTrue(s.tseRegistrationDeferred)
            assertFalse(s.tseClientRegistered)
            // The DONE step's note is driven by tseRegistrationDeferred, not registerError — the
            // original failure is not shown again once the operator has moved past it.
            assertNull(s.registerError)
        }

    @Test
    fun deferTseRegistration_outsideRegisterStep_isIgnored() =
        runTest {
            val viewModel = vm()

            viewModel.deferTseRegistration()

            assertEquals(FirstTerminalStep.CREATE_TERMINAL, viewModel.state.step)
            assertFalse(viewModel.state.tseRegistrationDeferred)
        }

    // --- re-probing on re-entry must not race a stale tseDisabled against the Create button ---

    @Test
    fun createTerminal_whileTseModeProbeInFlight_isNoOp() =
        runTest {
            val terminal = FakeTerminalApiClient().apply { createResult = response(11, fiscal = true) }
            val tse = FakeTseSettingsApiClient().apply { configGate = CompletableDeferred() }
            val viewModel = vm(terminal = terminal, tse = tse)
            viewModel.loadTseMode() // suspends inside getConfig() — tseModeChecked stays false

            viewModel.createTerminal()

            assertFalse(viewModel.state.tseModeChecked)
            assertNull(viewModel.state.createdTerminal)
            assertEquals(0, terminal.createTerminalCallCount)
        }

    // --- a busy VM must ignore a second call, the same guard deferTseRegistration() already has ---

    @Test
    fun registerTse_whileBusy_isNoOp() =
        runTest {
            val terminal =
                FakeTerminalApiClient().apply {
                    createResult = response(6, fiscal = true)
                    registerGate = CompletableDeferred()
                }
            val viewModel = vm(terminal = terminal)
            viewModel.loadTseMode()
            viewModel.createTerminal()
            viewModel.skipTseCommission() // TSE already commissioned → Step 3
            viewModel.registerTse() // suspends inside registerTerminalTse() — state.busy stays true

            viewModel.registerTse()

            assertEquals(1, terminal.registerCallCount)
        }

    // --- Step 4: register at the TSE while pairing (F42) ---

    private fun deferredOnReadyTse(): Pair<FakeTerminalApiClient, FirstTerminalSetupViewModel> {
        val terminal =
            FakeTerminalApiClient().apply {
                createResult = response(6, fiscal = true)
                registerError = { throw NetworkApiException(RuntimeException("offline")) }
                tsePairingStatus = TerminalDto.TsePairingStatusDto(mode = TseMode.NORMAL, initialized = true, available = true)
            }
        val viewModel = vm(terminal = terminal)
        viewModel.loadTseMode()
        viewModel.createTerminal()
        viewModel.skipTseCommission()
        viewModel.registerTse()
        viewModel.deferTseRegistration()
        return terminal to viewModel
    }

    @Test
    fun issuePairingCode_afterDeferral_onReadyTse_offersTheTseBoxTicked_andSendsIt() =
        runTest {
            val (terminal, viewModel) = deferredOnReadyTse()

            viewModel.issuePairingCode()

            assertEquals(true, viewModel.state.pairingOptions?.registerTseClient)
            assertEquals(0, terminal.generatePairingCodeCallCount, "the operator chooses before a code exists")

            viewModel.confirmIssuePairingCode()

            assertEquals(true, terminal.lastPairingCodeRegisterTse)
            assertEquals(FirstTerminalStep.DONE, viewModel.state.step)
            assertFalse(viewModel.state.tseRegistrationDeferred, "a code that registers on pairing settles the deferral")
        }

    @Test
    fun issuePairingCode_afterDeferral_lockedSitting_unlocksThenReissuesTheCode() =
        runTest {
            val (terminal, viewModel) = deferredOnReadyTse()
            viewModel.issuePairingCode()
            terminal.pairingCodeError = { throw PosApiException(PosErrorCode.TSE_ADMIN_LOCKED, 423, "locked", null) }

            viewModel.confirmIssuePairingCode()

            assertTrue(viewModel.state.showAdminUnlockDialog)
            val registerCallsBefore = terminal.registerCallCount
            terminal.pairingCodeError = null
            viewModel.updateAdminUnlockPin("1234") // the fake's correct PIN
            viewModel.unlockAndRetryRegister()

            assertEquals(FirstTerminalStep.DONE, viewModel.state.step)
            assertEquals(2, terminal.generatePairingCodeCallCount)
            assertEquals(true, terminal.lastPairingCodeRegisterTse)
            assertEquals(registerCallsBefore, terminal.registerCallCount, "the retry re-issues the code, not tse-register")
        }

    @Test
    fun issuePairingCode_withTheTseNotReady_issuesAPlainCodeStraightAway() =
        runTest {
            val (terminal, viewModel) = deferredOnReadyTse()
            terminal.tsePairingStatus = TerminalDto.TsePairingStatusDto(mode = TseMode.SIMULATION, initialized = true, available = false)

            viewModel.issuePairingCode()

            assertNull(viewModel.state.pairingOptions)
            assertEquals(false, terminal.lastPairingCodeRegisterTse)
            assertEquals(FirstTerminalStep.DONE, viewModel.state.step)
        }
}
