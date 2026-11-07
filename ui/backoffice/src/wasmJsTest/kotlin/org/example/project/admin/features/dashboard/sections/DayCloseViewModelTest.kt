package org.example.project.admin.features.dashboard.sections

import kotlinx.coroutines.test.runTest
import org.example.project.admin.features.dashboard.FakeStatisticsApiClient
import org.example.project.admin.features.dashboard.FakeZBonApiClient
import org.example.project.admin.features.dashboard.presentation.sections.DayCloseViewModel
import org.example.project.core.domain.dto.statistics.StatisticsDto
import org.example.project.core.network.error.NetworkApiException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun overview() =
    StatisticsDto.SalesOverviewResponseDto(
        period = "2026-08-28",
        totalRevenue = "0.00",
        billedRevenue = "0.00",
        canceledRevenue = "0.00",
        openItemAmount = "0.00",
        totalTips = "0.00",
        totalBillCount = 0,
    )

class DayCloseViewModelTest {
    @Test
    fun lastZBonPresent_isNotEmpty() =
        runTest {
            val vm =
                DayCloseViewModel(
                    FakeZBonApiClient(latestEndTime = "2026-08-27T23:14:00"),
                    FakeStatisticsApiClient(::overview),
                )
            vm.refresh()
            assertFalse(vm.state.isEmpty)
            assertEquals("2026-08-27T23:14:00", vm.state.lastZBonEndTime)
            assertEquals(0, vm.state.openSessionCount)
        }

    @Test
    fun noZBonAndNoOpenSessions_isEmpty() =
        runTest {
            val vm =
                DayCloseViewModel(
                    FakeZBonApiClient(latestEndTime = null),
                    FakeStatisticsApiClient(::overview),
                )
            vm.refresh()
            assertFalse(vm.state.failed)
            assertTrue(vm.state.isEmpty)
        }

    @Test
    fun theNeverClosedSentinelCountsAsNoZBon() =
        runTest {
            // The server answers GET /api/z-bon/latest/datetime with this sentinel rather than null
            // when no Z-Bon has ever been closed (ZBonRoutes.kt), so a null check never fires and an
            // operator who has never cashed up was shown "Letzter Z-Bon: 01.01.2020".
            // "2020-01-01T00:00", NOT "...T00:00:00" — the route builds the sentinel from a literal
            // with seconds, but kotlinx-datetime's toString() omits zero seconds, so this shorter
            // form is what actually crosses the wire. Captured from a running server.
            val vm =
                DayCloseViewModel(
                    FakeZBonApiClient(latestEndTime = "2020-01-01T00:00"),
                    FakeStatisticsApiClient(::overview),
                )
            vm.refresh()
            assertFalse(vm.state.hasZBon)
            assertTrue(vm.state.isEmpty)
        }

    @Test
    fun theSentinelIsAlsoRecognisedWithExplicitSeconds() =
        runTest {
            // Guards the other direction: if the server ever serialises the sentinel with its zero
            // seconds, it must still read as "never closed" rather than a real 2020 close.
            val vm =
                DayCloseViewModel(
                    FakeZBonApiClient(latestEndTime = "2020-01-01T00:00:00"),
                    FakeStatisticsApiClient(::overview),
                )
            vm.refresh()
            assertFalse(vm.state.hasZBon)
        }

    @Test
    fun aRealCloseIsNotMistakenForTheSentinel() =
        runTest {
            val vm =
                DayCloseViewModel(
                    FakeZBonApiClient(latestEndTime = "2026-08-27T23:14:00"),
                    FakeStatisticsApiClient(::overview),
                )
            vm.refresh()
            assertTrue(vm.state.hasZBon)
            assertFalse(vm.state.isEmpty)
        }

    @Test
    fun failedLoad_setsFailedAndIsNotLoading() =
        runTest {
            val vm =
                DayCloseViewModel(
                    FakeZBonApiClient(latestEndTime = null),
                    FakeStatisticsApiClient(
                        overview = ::overview,
                        sessions = { throw NetworkApiException(RuntimeException("down")) },
                    ),
                )
            vm.refresh()
            assertTrue(vm.state.failed)
            assertFalse(vm.state.isLoading)
        }
}
