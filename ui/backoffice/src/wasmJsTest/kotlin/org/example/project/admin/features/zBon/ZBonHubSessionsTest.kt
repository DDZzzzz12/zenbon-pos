package org.example.project.admin.features.zBon

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDateTime
import org.example.project.admin.features.dailyreport.FakeBillApiClient
import org.example.project.admin.features.dashboard.FakeZBonApiClient
import org.example.project.admin.features.zBon.presentation.ZBonHubViewModel
import org.example.project.core.data.remote.TABLE_SESSIONS_PAGE_SIZE
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.dto.table.TableSessionDto
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun zBon() =
    ZBonDto.ZBonResponseDto(
        id = 146,
        shopInfoId = 1,
        shopName = "Testladen",
        employeeNumber = "M001",
        startTime = "2026-09-20T21:58:00",
        endTime = "2026-09-21T21:47:00",
        cancelledAmount = "0.00",
        totalRevenue = "0.00",
        openingBalance = "0.00",
        expenses = "0.00",
        closingBalance = "0.00",
        createdAt = "2026-09-21T21:47:00",
    )

private fun session(id: Long) =
    TableSessionDto.TableSessionResponseDto(
        id = id,
        tableNumber = "1",
        employeeId = "emp-1",
        employeeNumber = "K001",
        isDelivery = false,
        customer = null,
        deliveryAddress = null,
        startTime = LocalDateTime(2026, 9, 21, 12, 0),
        endTime = LocalDateTime(2026, 9, 21, 13, 0),
        completed = true,
    )

/**
 * admin17 FAIL-7: the Z-Bon "Sitzungen" tab asked `GET /api/tables/sessions` without a limit and
 * showed the server's default page of 50 (#146: 50 of 58). The tab must list every session.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ZBonHubSessionsTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun hub(sessions: FakeTableSessionApiClient) =
        ZBonHubViewModel(
            zbonId = 146,
            zBonApiClient = FakeZBonApiClient(byId = { zBon() }),
            billApiClient = FakeBillApiClient(),
            sessionApiClient = sessions,
        )

    @Test
    fun aZBonWithMoreSessionsThanTheServerDefaultLoadsEverySession() =
        runTest(dispatcher) {
            val all = (1L..58L).map { session(it) }
            val sessions = FakeTableSessionApiClient(sessions = all)
            val vm = hub(sessions)

            vm.load()
            advanceUntilIdle()

            assertNull(vm.state.sessionsError)
            assertEquals(all.map { it.id }, vm.state.sessions.map { it.id }, "no silent cap at the default 50")
            assertEquals<List<Pair<Int?, Int?>>>(listOf(0 to TABLE_SESSIONS_PAGE_SIZE), sessions.listCalls, "an explicit limit, one short page")
        }

    @Test
    fun anExactMultipleOfThePageSizeStopsOnTheEmptyPage() =
        runTest(dispatcher) {
            val all = (1L..(2L * TABLE_SESSIONS_PAGE_SIZE)).map { session(it) }
            val sessions = FakeTableSessionApiClient(sessions = all)
            val vm = hub(sessions)

            vm.load()
            advanceUntilIdle()

            assertEquals(all.size, vm.state.sessions.size)
            assertEquals(
                listOf(0, TABLE_SESSIONS_PAGE_SIZE, 2 * TABLE_SESSIONS_PAGE_SIZE),
                sessions.listCalls.map { it.first },
                "two full pages, then one empty page ends the walk",
            )
        }
}
