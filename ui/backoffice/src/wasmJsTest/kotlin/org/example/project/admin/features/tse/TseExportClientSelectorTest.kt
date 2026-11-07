package org.example.project.admin.features.tse

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.admin.features.dashboard.FakeTseQueueApiClient
import org.example.project.admin.features.terminal.FakeTerminalApiClient
import org.example.project.admin.features.terminal.TerminalTestFixtures
import org.example.project.core.data.remote.DsfinvkApiClient
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.features.settings.presentation.state.TseExportType
import org.example.project.features.settings.presentation.state.TseSetupTerminalOption
import org.example.project.features.settings.presentation.viewmodel.TseSettingsViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * F69: the time-filtered TSE export's "Kasse" selector. "Alle Kassen" (null) is the default and is
 * what is sent unless the operator picks a register; the registers offered are the terminals with a
 * TSE client — a deactivated one included, its transactions are still on the TSE.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TseExportClientSelectorTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var api: FakeTseSettingsApiClient
    private lateinit var terminalApi: FakeTerminalApiClient
    private lateinit var vm: TseSettingsViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        api = FakeTseSettingsApiClient()
        terminalApi =
            FakeTerminalApiClient().apply {
                terminals =
                    listOf(
                        TerminalTestFixtures.summary(1, tse = true, name = "Bar"),
                        TerminalTestFixtures.summary(2, tse = false, name = "Neu"),
                        TerminalTestFixtures.summary(3, tse = true, active = false),
                    )
            }
        vm = TseSettingsViewModel(api, FakeTseQueueApiClient { error("unused") }, UnusedDsfinvkApi, terminalApi)
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun selectTimeFilteredFor(day: String) {
        vm.updateTseExportType(TseExportType.TIME_FILTERED)
        vm.updateTseExportDateRange(day, day)
    }

    @Test
    fun offers_all_registers_by_default_plus_every_terminal_with_a_tse_client() =
        runTest(dispatcher) {
            selectTimeFilteredFor("2026-09-01")
            advanceUntilIdle()

            val state = vm.uiState.value.tseExportState
            assertNull(state.selectedClientId, "\"Alle Kassen\" is the default")
            assertEquals(
                listOf(TseSetupTerminalOption("T001", "Bar"), TseSetupTerminalOption("T003", null)),
                state.clientOptions,
                "only terminals with a TSE client; the deactivated T003 still counts",
            )
            assertEquals("Bar (T001)", state.clientOptions.first().displayName)
        }

    @Test
    fun the_default_export_sends_no_client_so_the_server_exports_all() =
        runTest(dispatcher) {
            selectTimeFilteredFor("2026-09-01")
            advanceUntilIdle()

            vm.exportTse()
            advanceUntilIdle()

            assertEquals(1, api.timeFilteredRequests.size)
            assertNull(api.timeFilteredRequests.single().clientId)
        }

    @Test
    fun a_selected_register_is_sent_and_choosing_all_again_clears_it() =
        runTest(dispatcher) {
            selectTimeFilteredFor("2026-09-01")
            advanceUntilIdle()

            vm.selectTseExportClient("T003")
            vm.exportTse()
            advanceUntilIdle()
            vm.selectTseExportClient(null)
            vm.exportTse()
            advanceUntilIdle()

            assertEquals(listOf("T003", null), api.timeFilteredRequests.map { it.clientId })
            assertEquals("2026-09-01", api.timeFilteredRequests.first().startDay)
        }
}

private object UnusedDsfinvkApi : DsfinvkApiClient {
    override suspend fun exportZip(request: DsfinvkExportDto.ExportRequestDto): ByteArray = error("unused")

    override suspend fun validate(
        startDate: String,
        endDate: String,
    ): DsfinvkExportDto.ValidationResultDto = error("unused")

    override suspend fun getStatistics(
        startDate: String,
        endDate: String,
    ): DsfinvkExportDto.ExportStatisticsDto = error("unused")
}
