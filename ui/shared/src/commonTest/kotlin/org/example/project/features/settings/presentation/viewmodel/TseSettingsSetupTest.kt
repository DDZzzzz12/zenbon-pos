package org.example.project.features.settings.presentation.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.core.data.remote.DsfinvkApiClient
import org.example.project.core.data.remote.TseQueueApiClient
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.domain.dto.tse.TseSigningQueueDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.core.error.FieldError
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.PosApiException
import org.example.project.core.presentation.strings.keys.TseSettingsKeys
import org.example.project.features.settings.presentation.state.OperationResult
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Commissioning from the TSE settings screen. Found on a real Swissbit TSE: a fresh shop had no
 * TimeAdmin PIN, the config save that precedes setup was refused with a 400, and the dialog spun on
 * "TSE operation in progress" forever — setup never sent, the reason never shown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TseSettingsSetupTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var api: FakeTseSettingsApi
    private lateinit var terminalApi: FakeTerminalApi
    private lateinit var vm: TseSettingsViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        api = FakeTseSettingsApi()
        terminalApi = FakeTerminalApi().apply { terminals = listOf(terminal("TER-001", name = "Bar")) }
        vm = newViewModel()
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun newViewModel() = TseSettingsViewModel(api, unusedQueueApi, unusedDsfinvkApi, terminalApi)

    @Test
    fun a_refused_config_save_ends_the_spinner_shows_the_reason_and_never_sends_setup() =
        runTest(dispatcher) {
            vm.showSetupDialog()
            advanceUntilIdle()
            api.updateConfigError =
                PosApiException(PosErrorCode.TSE_INVALID_PARAMETERS, 400, "Keep-alive interval must be positive", "trace-1")
            vm.setupTse(ADMIN_PIN, ADMIN_PUK, SEED, TIME_ADMIN_PIN)
            advanceUntilIdle()

            val state = vm.uiState.value
            assertFalse(state.isPerformingOperation, "the spinner must end")
            assertTrue(state.showSetupDialog, "the dialog stays open so the operator can correct it")
            assertEquals(PosErrorCode.TSE_INVALID_PARAMETERS, state.setupError?.code)
            assertEquals("trace-1", state.setupError?.traceId)
            assertNull(state.error, "reported in the dialog, not a second screen-wide error dialog")
            assertEquals(1, api.updateRequests.size)
            assertTrue(api.setupCalls.isEmpty(), "setup must not run on a config that was not saved")
        }

    @Test
    fun the_setup_pre_save_on_an_uncommissioned_tse_shows_no_apply_result_and_setup_still_runs() =
        runTest(dispatcher) {
            vm.showSetupDialog()
            advanceUntilIdle()
            // What the server answers before commissioning (real Swissbit TSE): it used to say
            // AUTH_FAILED, which surfaced as "check the admin PIN" in the middle of a setup that worked.
            api.applyStatus = TseConfigDto.TseModeApplyStatus.UNREACHABLE
            api.applyOutcome = TseConfigDto.TseModeApplyStatus.NOT_COMMISSIONED

            vm.setupTse(ADMIN_PIN, ADMIN_PUK, SEED, TIME_ADMIN_PIN)
            advanceUntilIdle()

            val state = vm.uiState.value
            assertNull(state.applyResult, "setup's own pre-save must not raise the apply-result dialog")
            assertFalse(state.saveSuccess, "nor a separate saved toast: setup reports its own outcome")
            assertEquals(1, api.setupCalls.size, "the setup itself still runs")
        }

    @Test
    fun the_setup_pre_save_hides_even_a_login_failure_dialog_because_setup_reports_its_own_outcome() =
        runTest(dispatcher) {
            vm.showSetupDialog()
            advanceUntilIdle()
            api.applyStatus = TseConfigDto.TseModeApplyStatus.AUTH_FAILED

            vm.setupTse(ADMIN_PIN, ADMIN_PUK, SEED, TIME_ADMIN_PIN)
            advanceUntilIdle()

            assertNull(vm.uiState.value.applyResult)
            assertEquals(1, api.setupCalls.size)
        }

    @Test
    fun a_standalone_save_on_an_uncommissioned_tse_surfaces_not_commissioned_not_a_login_failure() =
        runTest(dispatcher) {
            advanceUntilIdle()
            api.applyStatus = TseConfigDto.TseModeApplyStatus.UNREACHABLE
            api.applyOutcome = TseConfigDto.TseModeApplyStatus.NOT_COMMISSIONED

            vm.saveConfig()
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(TseConfigDto.TseModeApplyStatus.NOT_COMMISSIONED, state.applyResult?.effectiveApplyStatus)
            assertFalse(state.saveSuccess, "not operational, so no plain saved toast")
        }

    /**
     * QA 2026-10-06: a SIMULATION save on a shop with no TimeAdmin PIN went to the server and came back
     * as the generic "unexpected error" dialog. The server now reports whether a PIN is stored, and the
     * screen refuses the save before sending, in the operator's language.
     */
    @Test
    fun a_save_without_any_time_admin_pin_is_refused_before_sending_with_the_localized_hint() =
        runTest(dispatcher) {
            api.storedConfig = TseConfigDto.TseConfig(mode = TseMode.DISABLED, timeAdminPin = "", timeAdminPinConfigured = false)
            vm = newViewModel()
            advanceUntilIdle()
            vm.startEditing()
            vm.updateFormField("mode", TseMode.SIMULATION.name)

            vm.saveConfig()
            advanceUntilIdle()

            val state = vm.uiState.value
            assertTrue(api.updateRequests.isEmpty(), "nothing is sent")
            assertEquals(TseSettingsKeys.timeAdminPinRequiredForSave, state.error?.messageKey)
            assertFalse(state.isSaving)
            assertTrue(state.isEditing, "the form stays open so the PIN can be entered")
        }

    @Test
    fun a_save_that_supplies_the_time_admin_pin_is_sent() =
        runTest(dispatcher) {
            api.storedConfig = TseConfigDto.TseConfig(mode = TseMode.DISABLED, timeAdminPin = "", timeAdminPinConfigured = false)
            vm = newViewModel()
            advanceUntilIdle()
            vm.startEditing()
            vm.updateFormField("mode", TseMode.SIMULATION.name)
            vm.updateFormField("timeAdminPin", TIME_ADMIN_PIN)

            vm.saveConfig()
            advanceUntilIdle()

            assertEquals(TIME_ADMIN_PIN, api.updateRequests.single().timeAdminPin)
            assertNull(vm.uiState.value.error)
        }

    @Test
    fun the_servers_time_admin_pin_refusal_reads_as_the_localized_hint() =
        runTest(dispatcher) {
            advanceUntilIdle() // older server: no timeAdminPinConfigured flag, so the server decides
            api.updateConfigError = PosApiException(PosErrorCode.TSE_TIME_ADMIN_PIN_REQUIRED, 400, "A TimeAdmin PIN is required.", "trace-9")

            vm.saveConfig()
            advanceUntilIdle()

            assertEquals(1, api.updateRequests.size)
            val error = vm.uiState.value.error
            assertEquals(TseSettingsKeys.timeAdminPinRequiredForSave, error?.messageKey)
        }

    @Test
    fun a_server_refusal_naming_the_time_admin_pin_reads_as_the_localized_hint() =
        runTest(dispatcher) {
            vm.showSetupDialog()
            advanceUntilIdle()
            api.updateConfigError =
                PosApiException(
                    code = PosErrorCode.VALIDATION_FAILED,
                    httpStatus = 400,
                    message = "A TimeAdmin PIN is required.",
                    traceId = "trace-2",
                    fieldErrors = listOf(FieldError("timeAdminPin", "REQUIRED")),
                )

            vm.setupTse(ADMIN_PIN, ADMIN_PUK, SEED, TIME_ADMIN_PIN)
            advanceUntilIdle()

            val error = vm.uiState.value.setupError
            assertEquals(TseSettingsKeys.timeAdminPinRequired, error?.messageKey)
            assertEquals("trace-2", error?.traceId, "the trace id survives for the error report")
            assertFalse(vm.uiState.value.isPerformingOperation)
            assertTrue(api.setupCalls.isEmpty())
        }

    @Test
    fun a_blank_time_admin_pin_is_refused_before_anything_is_sent() =
        runTest(dispatcher) {
            vm.showSetupDialog()
            advanceUntilIdle()

            vm.setupTse(ADMIN_PIN, ADMIN_PUK, SEED, "  ")
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(TseSettingsKeys.timeAdminPinRequired, state.setupError?.messageKey)
            assertFalse(state.isPerformingOperation)
            assertTrue(api.updateRequests.isEmpty())
            assertTrue(api.setupCalls.isEmpty())
        }

    @Test
    fun a_factory_placeholder_time_admin_pin_is_refused_before_anything_is_sent() =
        runTest(dispatcher) {
            vm.showSetupDialog()
            advanceUntilIdle()

            vm.setupTse(ADMIN_PIN, ADMIN_PUK, SEED, "12345")
            advanceUntilIdle()

            assertEquals(
                TseSettingsKeys.placeholderCredential,
                vm.uiState.value.setupError
                    ?.messageKey,
            )
            assertTrue(api.updateRequests.isEmpty())
            assertTrue(api.setupCalls.isEmpty())
        }

    @Test
    fun the_happy_path_saves_the_time_admin_pin_then_commissions_once() =
        runTest(dispatcher) {
            vm.showSetupDialog()
            advanceUntilIdle()
            vm.setupTse(ADMIN_PIN, ADMIN_PUK, SEED, TIME_ADMIN_PIN)
            advanceUntilIdle()

            assertEquals(TIME_ADMIN_PIN, api.updateRequests.single().timeAdminPin)
            val credentials = api.setupCalls.single()
            assertEquals(ADMIN_PIN, credentials.adminPin)
            assertEquals(ADMIN_PUK, credentials.adminPuk)
            assertEquals(SEED, credentials.credentialSeed)
            assertEquals("TER-001", credentials.terminalId, "the chosen terminal is registered as the TSE's first client")
            val state = vm.uiState.value
            assertFalse(state.isPerformingOperation)
            assertFalse(state.showSetupDialog)
            assertNull(state.setupError)
            assertIs<OperationResult.Success>(state.operationResult)
        }

    /**
     * Found on a real Swissbit TSE: a fresh install is DISABLED and setup failed until the operator
     * switched the mode by hand. The dialog now commissions straight from DISABLED, and afterwards
     * shows the NORMAL the server saved — a stale DISABLED form would write it back on the next save.
     */
    @Test
    fun a_fresh_disabled_install_commissions_without_a_mode_change_and_the_form_then_shows_normal() =
        runTest(dispatcher) {
            vm.showSetupDialog()
            advanceUntilIdle()
            assertEquals("DISABLED", vm.uiState.value.formState.mode)

            vm.setupTse(ADMIN_PIN, ADMIN_PUK, SEED, TIME_ADMIN_PIN)
            advanceUntilIdle()

            assertEquals(TseMode.DISABLED, api.updateRequests.single().mode, "the operator changed nothing")
            assertEquals(1, api.setupCalls.size)
            val state = vm.uiState.value
            assertEquals("NORMAL", state.formState.mode)
            assertEquals(TseMode.NORMAL, state.config?.mode)
        }

    /**
     * QA BO-30 (2026-10-06): in DISABLED — the fresh-install mode — the server never opens the TSE,
     * so the ping says "unavailable" and "TSE initialisieren" was hidden on exactly the installs that
     * need it. The operator could only commission after switching the mode by hand.
     */
    @Test
    fun a_fresh_disabled_install_offers_setup_although_the_tse_does_not_answer() =
        runTest(dispatcher) {
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(TseMode.DISABLED, state.config?.mode)
            assertFalse(state.isTseAvailable)
            assertTrue(state.canOfferSetup())
        }

    /** The dialog then explains the refusal; without the button an operator in SIMULATION got no hint at all. */
    @Test
    fun a_simulation_mode_shop_is_offered_setup_so_the_dialog_can_refuse_it() =
        runTest(dispatcher) {
            api.storedConfig = TseConfigDto.TseConfig(mode = TseMode.SIMULATION, timeAdminPin = "")
            vm = newViewModel()
            advanceUntilIdle()

            assertTrue(vm.uiState.value.canOfferSetup())
        }

    @Test
    fun a_normal_mode_tse_that_does_not_answer_offers_no_setup() =
        runTest(dispatcher) {
            api.storedConfig = TseConfigDto.TseConfig(mode = TseMode.NORMAL, timeAdminPin = "")
            vm = newViewModel()
            advanceUntilIdle()

            assertFalse(vm.uiState.value.canOfferSetup(), "a NORMAL TSE that does not answer is missing, not uncommissioned")
        }

    @Test
    fun after_commissioning_from_disabled_the_screen_asks_the_tse_again() =
        runTest(dispatcher) {
            advanceUntilIdle()
            val pingsBefore = api.pingCount
            vm.showSetupDialog()
            advanceUntilIdle()
            api.available = true

            vm.setupTse(ADMIN_PIN, ADMIN_PUK, SEED, TIME_ADMIN_PIN)
            advanceUntilIdle()

            assertEquals(pingsBefore + 1, api.pingCount, "setup opened the TSE; the screen must not keep calling it unreachable")
            assertTrue(vm.uiState.value.isTseAvailable)
        }

    @Test
    fun a_simulation_mode_form_is_refused_before_anything_is_sent() =
        runTest(dispatcher) {
            vm.showSetupDialog()
            advanceUntilIdle()
            vm.updateFormField("mode", "SIMULATION")

            vm.setupTse(ADMIN_PIN, ADMIN_PUK, SEED, TIME_ADMIN_PIN)
            advanceUntilIdle()

            assertEquals(
                TseSettingsKeys.setupSimulationMode,
                vm.uiState.value.setupError
                    ?.messageKey,
            )
            assertFalse(vm.uiState.value.isPerformingOperation)
            assertTrue(api.updateRequests.isEmpty())
            assertTrue(api.setupCalls.isEmpty())
        }

    @Test
    fun reopening_the_dialog_clears_the_previous_failure() =
        runTest(dispatcher) {
            vm.showSetupDialog()
            advanceUntilIdle()
            vm.setupTse(ADMIN_PIN, ADMIN_PUK, SEED, "")
            vm.hideSetupDialog()
            vm.showSetupDialog()
            assertNull(vm.uiState.value.setupError)
        }

    // ---- Which terminal becomes the TSE's first client (owner decision 2026-10-04) ----

    /** The back office is no terminal: its own lookup 404s, so the first fiscal terminal is chosen. */
    @Test
    fun the_back_office_lists_only_active_fiscal_terminals_and_preselects_the_first() =
        runTest(dispatcher) {
            terminalApi.terminals =
                listOf(
                    terminal("TER-001", fiscal = false),
                    terminal("TER-002", active = false),
                    terminal("TER-003", name = "Bar"),
                    terminal("TER-004", name = "Terrasse"),
                )

            vm.showSetupDialog()
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(listOf("TER-003", "TER-004"), state.setupTerminals.map { it.clientId })
            assertEquals("Bar (TER-003)", state.setupTerminals.first().displayName)
            assertEquals("TER-003", state.selectedSetupTerminalId)
            assertFalse(state.isLoadingSetupTerminals)
        }

    /** A till commissions with itself, as it always did. */
    @Test
    fun a_till_preselects_its_own_terminal() =
        runTest(dispatcher) {
            terminalApi.terminals = listOf(terminal("TER-001"), terminal("TER-002"))
            terminalApi.ownClientId = "TER-002"

            vm.showSetupDialog()
            advanceUntilIdle()

            assertEquals("TER-002", vm.uiState.value.selectedSetupTerminalId)
        }

    @Test
    fun the_operator_can_choose_another_terminal_and_setup_sends_it() =
        runTest(dispatcher) {
            terminalApi.terminals = listOf(terminal("TER-001"), terminal("TER-002"))
            vm.showSetupDialog()
            advanceUntilIdle()

            vm.selectSetupTerminal("TER-002")
            vm.selectSetupTerminal("TER-999") // not offered — ignored
            vm.setupTse(ADMIN_PIN, ADMIN_PUK, SEED, TIME_ADMIN_PIN)
            advanceUntilIdle()

            assertEquals("TER-002", api.setupCalls.single().terminalId)
        }

    /** No usable terminal yet: the dialog says to create or pair one, and nothing is sent. */
    @Test
    fun with_no_fiscal_terminal_nothing_is_selected_and_setup_is_refused_before_anything_is_sent() =
        runTest(dispatcher) {
            terminalApi.terminals = listOf(terminal("TER-001", fiscal = false))
            vm.showSetupDialog()
            advanceUntilIdle()

            val loaded = vm.uiState.value
            assertTrue(loaded.setupTerminals.isEmpty())
            assertNull(loaded.selectedSetupTerminalId)

            vm.setupTse(ADMIN_PIN, ADMIN_PUK, SEED, TIME_ADMIN_PIN)
            advanceUntilIdle()

            assertEquals(
                TseSettingsKeys.setupNoTerminal,
                vm.uiState.value.setupError
                    ?.messageKey,
            )
            assertTrue(api.updateRequests.isEmpty(), "not even the config is saved")
            assertTrue(api.setupCalls.isEmpty())
        }

    @Test
    fun with_no_terminal_at_all_the_dialog_shows_the_create_a_terminal_state() =
        runTest(dispatcher) {
            terminalApi.terminals = emptyList()
            vm.showSetupDialog()
            advanceUntilIdle()

            val state = vm.uiState.value
            assertTrue(state.setupTerminals.isEmpty())
            assertNull(state.selectedSetupTerminalId)
            assertNull(state.setupError, "an empty list is a state, not an error")
        }

    /** A refused listing is shown as what it is, never read as "no terminal exists". */
    @Test
    fun a_failed_terminal_listing_is_reported_in_the_dialog() =
        runTest(dispatcher) {
            terminalApi.listError = PosApiException(PosErrorCode.AUTH_FORBIDDEN, 403, "forbidden", "trace-9")
            vm.showSetupDialog()
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(PosErrorCode.AUTH_FORBIDDEN, state.setupError?.code)
            assertFalse(state.isLoadingSetupTerminals)
        }

    /** The server's own refusal of a setup without a usable terminal reads as the dialog's sentence. */
    @Test
    fun the_servers_no_terminal_refusal_reads_as_the_create_a_terminal_sentence() =
        runTest(dispatcher) {
            api.setupError = PosApiException(PosErrorCode.TSE_SETUP_TERMINAL_REQUIRED, 404, "Terminal not found for deviceId=web", "trace-3")
            vm.showSetupDialog()
            advanceUntilIdle()

            vm.setupTse(ADMIN_PIN, ADMIN_PUK, SEED, TIME_ADMIN_PIN)
            advanceUntilIdle()

            assertEquals(
                TseSettingsKeys.setupNoTerminal,
                vm.uiState.value.setupError
                    ?.messageKey,
            )
            assertTrue(vm.uiState.value.showSetupDialog)
        }

    private companion object {
        const val ADMIN_PIN = "80417"
        const val ADMIN_PUK = "804172"
        const val SEED = "seed"
        const val TIME_ADMIN_PIN = "61023"

        fun terminal(
            clientId: String,
            name: String? = null,
            fiscal: Boolean = true,
            active: Boolean = true,
        ) = TerminalDto.TerminalSummaryDto(
            id = clientId.takeLast(3).toLong(),
            terminalId = clientId,
            terminalBrand = null,
            terminalModel = null,
            isActive = active,
            lastSeenAt = "2026-10-04T10:00:00",
            fiscal = fiscal,
            name = name,
        )

        val unusedQueueApi =
            object : TseQueueApiClient {
                override suspend fun getQueueStatus(): TseSigningQueueDto.QueueStatusDto = TODO()

                override suspend fun getQueueEntries(
                    status: TseSigningStatus?,
                    limit: Int,
                    offset: Int,
                ): List<TseSigningQueueDto.QueueEntryResponseDto> = TODO()

                override suspend fun retryEntry(entryId: Long): TseSigningQueueDto.EntryOperationResponseDto = TODO()

                override suspend fun cancelEntry(
                    entryId: Long,
                    reason: String,
                ): TseSigningQueueDto.EntryOperationResponseDto = TODO()

                override suspend fun pauseService(): TseSigningQueueDto.ServiceControlResponseDto = TODO()

                override suspend fun resumeService(): TseSigningQueueDto.ServiceControlResponseDto = TODO()

                override suspend fun triggerRetry(): TseSigningQueueDto.TriggerRetryResponseDto = TODO()

                override suspend fun cleanupOldEntries(olderThanDays: Int): TseSigningQueueDto.CleanupResultDto = TODO()
            }

        val unusedDsfinvkApi =
            object : DsfinvkApiClient {
                override suspend fun exportZip(request: DsfinvkExportDto.ExportRequestDto): ByteArray = TODO()

                override suspend fun validate(
                    startDate: String,
                    endDate: String,
                ): DsfinvkExportDto.ValidationResultDto = TODO()

                override suspend fun getStatistics(
                    startDate: String,
                    endDate: String,
                ): DsfinvkExportDto.ExportStatisticsDto = TODO()
            }
    }
}
