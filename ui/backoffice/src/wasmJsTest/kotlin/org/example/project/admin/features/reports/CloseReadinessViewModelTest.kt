package org.example.project.admin.features.reports

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.example.project.admin.features.reports.presentation.CloseReadinessViewModel
import org.example.project.core.domain.enums.setting.DatevExportCadence
import org.example.project.core.network.error.NetworkApiException
import org.example.project.features.reports.presentation.ReportsGranularity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloseReadinessViewModelTest {
    @Test
    fun pastMonth_isRequestedAsAHalfOpenWindow() =
        runTest {
            val api = FakeMonthlyCloseApiClient()
            val vm = CloseReadinessViewModel(api)

            vm.load(ReportsGranularity.MONTH, LocalDate(2026, 7, 15))

            // Half-open: the end is the first instant of August, not 2026-07-31T23:59:59.
            assertEquals("2026-07-01T00:00", api.lastPeriodStart)
            assertEquals("2026-08-01T00:00", api.lastPeriodEnd)
        }

    @Test
    fun weekCarriesTheWeeklyCadence_andMonthTheMonthlyOne() =
        runTest {
            val api = FakeMonthlyCloseApiClient()
            val vm = CloseReadinessViewModel(api)

            vm.load(ReportsGranularity.WEEK, LocalDate(2026, 7, 15))
            assertEquals(DatevExportCadence.WEEKLY, api.lastCadence)

            vm.load(ReportsGranularity.MONTH, LocalDate(2026, 7, 15))
            assertEquals(DatevExportCadence.MONTHLY, api.lastCadence)
        }

    @Test
    fun successfulLoad_landsThePayloadAndClearsTheSpinner() =
        runTest {
            val api = FakeMonthlyCloseApiClient(response = monthlyClose(kpis = listOf(grossRevenueKpi())))
            val vm = CloseReadinessViewModel(api)

            vm.load(ReportsGranularity.MONTH, LocalDate(2026, 7, 15))

            assertEquals(false, vm.state.isLoading)
            assertNull(vm.state.error)
            assertEquals("1000.00", assertNotNull(vm.state.data).kpis.single().value)
        }

    @Test
    fun failure_setsATypedErrorAndKeepsThePreviousFigures() =
        runTest {
            val api = FakeMonthlyCloseApiClient()
            val vm = CloseReadinessViewModel(api)
            vm.load(ReportsGranularity.MONTH, LocalDate(2026, 7, 15))

            api.failWith = NetworkApiException(IllegalStateException("boom"))
            vm.load(ReportsGranularity.MONTH, LocalDate(2026, 6, 15))

            assertNotNull(vm.state.error)
            assertEquals(false, vm.state.isLoading)
            // Retained, not blanked — the same contract ReportsState has.
            assertNotNull(vm.state.data)
        }

    @Test
    fun clearError_dropsTheErrorWithoutRefetching() =
        runTest {
            val api = FakeMonthlyCloseApiClient(failWith = NetworkApiException(IllegalStateException("boom")))
            val vm = CloseReadinessViewModel(api)
            vm.load(ReportsGranularity.MONTH, LocalDate(2026, 7, 15))
            assertTrue(api.callCount == 1)

            vm.clearError()

            assertNull(vm.state.error)
            assertEquals(1, api.callCount)
        }
}
