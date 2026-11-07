package org.example.project.admin.features.dashboard.sections

import kotlinx.coroutines.test.runTest
import org.example.project.admin.features.dashboard.FakeStatisticsApiClient
import org.example.project.admin.features.dashboard.FakeZBonApiClient
import org.example.project.admin.features.dashboard.presentation.sections.DayFiguresViewModel
import org.example.project.core.domain.dto.statistics.StatisticsDto
import org.example.project.core.network.error.NetworkApiException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun overview(
    revenue: String = "1240.50",
    bills: Int = 47,
) = StatisticsDto.SalesOverviewResponseDto(
    period = "2026-08-28",
    totalRevenue = revenue,
    billedRevenue = revenue,
    canceledRevenue = "0.00",
    openItemAmount = "120.00",
    totalTips = "86.00",
    totalBillCount = bills,
    currency = "EUR",
)

class DayFiguresViewModelTest {
    @Test
    fun load_populatesOverviewAndReportsRevenue() =
        runTest {
            val vm =
                DayFiguresViewModel(
                    FakeStatisticsApiClient({ overview() }),
                    FakeZBonApiClient(latestEndTime = "2026-08-28T04:00:00"),
                )
            vm.refresh()
            assertFalse(vm.state.isLoading)
            assertFalse(vm.state.failed)
            assertTrue(vm.state.hasRevenue)
            assertEquals("1240.50", vm.state.overview?.totalRevenue)
        }

    @Test
    fun zeroRevenue_isNotFailedAndHasNoRevenue() =
        runTest {
            val vm =
                DayFiguresViewModel(
                    FakeStatisticsApiClient({ overview(revenue = "0.00", bills = 0) }),
                    FakeZBonApiClient(latestEndTime = "2026-08-28T04:00:00"),
                )
            vm.refresh()
            assertFalse(vm.state.failed)
            assertFalse(vm.state.hasRevenue)
        }

    @Test
    fun failedLoad_setsFailedAndLeavesOverviewNull() =
        runTest {
            val vm =
                DayFiguresViewModel(
                    FakeStatisticsApiClient({ throw NetworkApiException(RuntimeException("down")) }),
                    FakeZBonApiClient(latestEndTime = "2026-08-28T04:00:00"),
                )
            vm.refresh()
            assertTrue(vm.state.failed)
            assertFalse(vm.state.isLoading)
            assertEquals(null, vm.state.overview)
        }
}
