package org.example.project.admin.features.dataexport

import kotlinx.coroutines.test.runTest
import org.example.project.admin.core.presentation.navigation.AdminNavItem
import org.example.project.admin.features.dataexport.presentation.DsfinvkExportViewModel
import org.example.project.core.auth.CapabilityBundles
import org.example.project.core.data.remote.DsfinvkApiClient
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.network.error.NetworkApiException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The DSFinV-K export reachable from Compliance › Datenexport (T5-5): its view model needs only
 * `DsfinvkApiClient` — no TSE client, so constructing it cannot fire a `TSE_ADMIN` route — and the
 * destination hosting it is gated on the same `DSFINVK_EXPORT` the routes require.
 */
class DsfinvkExportViewModelTest {
    private val validation =
        DsfinvkExportDto.ValidationResultDto(
            isValid = true,
            canExport = true,
            errors = emptyList(),
            warnings = emptyList(),
            missingTseCount = 0,
            unclosedZBonCount = 0,
            dateRange = "2026-07-01..2026-07-31",
        )

    @Test
    fun constructionCallsNothing() {
        val api = RecordingDsfinvkApiClient()
        val viewModel = DsfinvkExportViewModel(api)

        assertTrue(api.calls.isEmpty())
        assertTrue(viewModel.state.hasValidDateRange(), "opens on first-of-month..today")
    }

    @Test
    fun validateAsksTheServerForTheSelectedRange() =
        runTest {
            val api = RecordingDsfinvkApiClient(validation = validation)
            val viewModel = DsfinvkExportViewModel(api)
            viewModel.updateDateRange("2026-07-01", "2026-07-31")

            viewModel.validate()

            assertEquals(listOf("validate 2026-07-01..2026-07-31"), api.calls)
            assertEquals(validation, viewModel.state.validationResult)
            assertFalse(viewModel.state.isLoadingValidation)
        }

    @Test
    fun anInvertedRangeIsNotSent() =
        runTest {
            val api = RecordingDsfinvkApiClient()
            val viewModel = DsfinvkExportViewModel(api)
            viewModel.updateDateRange("2026-07-31", "2026-07-01")

            viewModel.validate()
            viewModel.loadStatistics()
            viewModel.export()

            assertTrue(api.calls.isEmpty())
        }

    @Test
    fun aFailingCallSurfacesATypedErrorAndStopsTheSpinner() =
        runTest {
            val api = RecordingDsfinvkApiClient(failWith = NetworkApiException(IllegalStateException("boom")))
            val viewModel = DsfinvkExportViewModel(api)

            viewModel.loadStatistics()

            assertNotNull(viewModel.error)
            assertFalse(viewModel.state.isLoadingStatistics)
            viewModel.clearError()
            assertNull(viewModel.error)
        }

    @Test
    fun theDatenexportDestinationIsGatedOnTheCapabilityTheRoutesRequire() {
        assertEquals(Capability.DSFINVK_EXPORT, AdminNavItem.DATA_EXPORT.requiredCapability)
        val auditor = requireNotNull(CapabilityBundles.forGroup("AUDITOR"))
        // The case that motivated moving the export: reachable without the TSE screen.
        assertTrue(Capability.DSFINVK_EXPORT in auditor)
        assertFalse(Capability.TSE_ADMIN in auditor)
    }
}

private class RecordingDsfinvkApiClient(
    private val validation: DsfinvkExportDto.ValidationResultDto? = null,
    private val failWith: Throwable? = null,
) : DsfinvkApiClient {
    val calls = mutableListOf<String>()

    override suspend fun exportZip(request: DsfinvkExportDto.ExportRequestDto): ByteArray {
        calls += "export ${request.startDate}..${request.endDate}"
        failWith?.let { throw it }
        return ByteArray(0)
    }

    override suspend fun validate(
        startDate: String,
        endDate: String,
    ): DsfinvkExportDto.ValidationResultDto {
        calls += "validate $startDate..$endDate"
        failWith?.let { throw it }
        return requireNotNull(validation)
    }

    override suspend fun getStatistics(
        startDate: String,
        endDate: String,
    ): DsfinvkExportDto.ExportStatisticsDto {
        calls += "statistics $startDate..$endDate"
        failWith?.let { throw it }
        throw NotImplementedError()
    }
}
