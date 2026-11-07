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
import org.example.project.core.data.remote.DsfinvkApiClient
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.PosApiException
import org.example.project.features.settings.presentation.viewmodel.TseSettingsViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real-TSE QA rt14: "Archiv registrieren" enters a TAR already in the server's TSE export folder in
 * the archive register, so a DSFinV-K export can cover a period erased from the TSE. An
 * export-and-delete whose archive the server could not register offers that file name.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TseArchiveRegistrationTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var api: FakeTseSettingsApiClient
    private lateinit var vm: TseSettingsViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        api = FakeTseSettingsApiClient()
        vm = TseSettingsViewModel(api, FakeTseQueueApiClient { error("unused") }, NoDsfinvkApi, FakeTerminalApiClient())
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun the_register_button_needs_a_file_name() =
        runTest(dispatcher) {
            assertFalse(
                vm.uiState.value.tseExportState
                    .canRegisterArchive(),
            )

            vm.registerExportArchive()
            advanceUntilIdle()

            assertTrue(api.registeredFileNames.isEmpty(), "nothing is sent without a name")
            vm.updateArchiveFileName("  ")
            assertFalse(
                vm.uiState.value.tseExportState
                    .canRegisterArchive(),
            )
        }

    @Test
    fun a_named_archive_is_registered_and_the_result_shown() =
        runTest(dispatcher) {
            vm.updateArchiveFileName(" tse_export_before_delete_20260506_172401_874.tar ")

            vm.registerExportArchive()
            advanceUntilIdle()

            assertEquals(listOf("tse_export_before_delete_20260506_172401_874.tar"), api.registeredFileNames, "the name is sent trimmed")
            val state = vm.uiState.value.tseExportState
            assertEquals("tse_export_before_delete_20260506_172401_874.tar", assertNotNull(state.registeredArchive).fileName)
            assertEquals("", state.archiveFileName, "the field is cleared after a registration")
            assertFalse(state.isRegisteringArchive)
        }

    @Test
    fun a_refused_registration_surfaces_the_error_and_keeps_the_name() =
        runTest(dispatcher) {
            api.registerError = {
                throw PosApiException(PosErrorCode.TSE_ARCHIVE_ALREADY_REGISTERED, 409, "already registered", null)
            }
            vm.updateArchiveFileName("a.tar")

            vm.registerExportArchive()
            advanceUntilIdle()

            assertNotNull(vm.uiState.value.error)
            val state = vm.uiState.value.tseExportState
            assertNull(state.registeredArchive)
            assertEquals("a.tar", state.archiveFileName)
            assertFalse(state.isRegisteringArchive)
        }

    @Test
    fun an_export_and_delete_archive_the_server_could_not_register_is_offered_for_registration() =
        runTest(dispatcher) {
            api.deleteResult = deleteResult(registered = false)

            vm.confirmDeleteExportedData()
            advanceUntilIdle()

            val state = vm.uiState.value.tseExportState
            assertEquals("tse_export_before_delete_20260506_172401_874.tar", state.archiveFileName)
            assertEquals(
                "tse_export_before_delete_20260506_172401_874.tar",
                state.unregisteredArchiveFileName,
                "the operator is warned to register the archive by hand",
            )

            vm.registerExportArchive()
            advanceUntilIdle()

            assertNull(vm.uiState.value.tseExportState.unregisteredArchiveFileName, "registering it clears the warning")
        }

    @Test
    fun a_registered_export_and_delete_archive_leaves_the_field_alone() =
        runTest(dispatcher) {
            api.deleteResult = deleteResult(registered = true)

            vm.confirmDeleteExportedData()
            advanceUntilIdle()

            assertEquals("", vm.uiState.value.tseExportState.archiveFileName)
            assertNull(vm.uiState.value.tseExportState.unregisteredArchiveFileName)
        }

    private fun deleteResult(registered: Boolean) =
        TseDto.ExportAndDeleteResultDto(
            success = true,
            archiveFileName = "tse_export_before_delete_20260506_172401_874.tar",
            archiveFilePath = "/srv/exports/tse/tse_export_before_delete_20260506_172401_874.tar",
            archiveSizeBytes = 782_336,
            usedBlocksBefore = 1526,
            exportSizeBytesBefore = 782_336,
            message = "erased",
            archiveRegistered = registered,
        )
}

private object NoDsfinvkApi : DsfinvkApiClient {
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
