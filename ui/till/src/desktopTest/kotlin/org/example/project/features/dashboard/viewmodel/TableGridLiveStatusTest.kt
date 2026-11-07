package org.example.project.features.dashboard.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.core.data.cache.CacheChangeBus
import org.example.project.core.data.models.RTable
import org.example.project.core.data.provider.RTableDataProvider
import org.example.project.core.domain.dto.table.LocationTypeDto
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.features.dashboard.presentation.viewmodel.TableGridViewModel
import org.example.project.features.notifications.presentation.viewmodel.NotificationUiEvent
import org.example.project.features.settings.data.remote.LocationTypeApiClient
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * F55: a table-status push for a table another device changed must reach the dashboard grid.
 *
 * The provider here answers like the wasm build's server-backed one — no cache, and nothing
 * emits on the [CacheChangeBus] — so the only route to the grid is the pushed event itself.
 * Without [TableGridViewModel.onTableStatusChanged] the grid kept the load-time rows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TableGridLiveStatusTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `remote pay and remote open update status and amount in the grid`() =
        runTest(dispatcher) {
            val vm = TableGridViewModel(ServerTables(), OneLocationType(), CacheChangeBus())
            vm.selectTableType(INDOOR)
            advanceUntilIdle()
            assertEquals("OCCUPIED", vm.row(2L).tableStatus)
            assertEquals("FREE", vm.row(6L).tableStatus)

            // Table 2 paid on the till; table number 5 (id 6) opened there with 4,50 €.
            vm.onTableStatusChanged(event(tableId = 2L, status = TableStatus.FREE, openAmount = null))
            vm.onTableStatusChanged(event(tableId = 6L, status = TableStatus.OCCUPIED, openAmount = "4.50", sessionId = 77L))
            advanceUntilIdle()

            assertEquals("FREE", vm.row(2L).tableStatus)
            assertNull(vm.row(2L).openAmount)
            assertNull(vm.row(2L).currentSessionId)
            assertEquals("OCCUPIED", vm.row(6L).tableStatus)
            assertEquals("4.50", vm.row(6L).openAmount)
            assertEquals(77L, vm.row(6L).currentSessionId)
            // The event keys by id, not by the printed number: table number "6" is untouched.
            assertEquals("FREE", vm.row(7L).tableStatus)
        }

    @Test
    fun `a push for a table outside the shown location changes nothing`() =
        runTest(dispatcher) {
            val vm = TableGridViewModel(ServerTables(), OneLocationType(), CacheChangeBus())
            vm.selectTableType(INDOOR)
            advanceUntilIdle()
            val before = vm.uiState.value.tablesByType

            vm.onTableStatusChanged(event(tableId = 999L, status = TableStatus.OCCUPIED, openAmount = "1.00"))

            assertEquals(before, vm.uiState.value.tablesByType)
        }

    private fun TableGridViewModel.row(id: Long): RTable = uiState.value.tablesByType.single { it.id == id }

    private fun event(
        tableId: Long,
        status: TableStatus,
        openAmount: String?,
        sessionId: Long? = null,
    ) = NotificationUiEvent.TableStatusChanged(
        message = "",
        tableId = tableId,
        status = status,
        openAmount = openAmount,
        employeeNumber = "07",
        sessionId = sessionId,
    )

    private class ServerTables : RTableDataProvider {
        private val tables =
            listOf(
                table(id = 2L, number = "2", status = "OCCUPIED", openAmount = "6.00", sessionId = 40L),
                table(id = 6L, number = "5", status = "FREE"),
                table(id = 7L, number = "6", status = "FREE"),
            )

        override suspend fun getAllRTables(): List<RTable> = tables

        override suspend fun getRTablesByLocationTypeId(
            locationTypeId: Long,
            includeInactive: Boolean,
        ): List<RTable> = tables.filter { it.locationTypeId == locationTypeId }

        override suspend fun getRTableById(id: Long): RTable? = tables.firstOrNull { it.id == id }

        override suspend fun searchRTables(query: String): List<RTable> = emptyList()

        override suspend fun refresh(
            locationTypeId: Long,
            includeInactive: Boolean,
        ) = Unit

        override suspend fun ensureSeeded(
            locationTypeId: Long,
            includeInactive: Boolean,
        ) = Unit

        override fun resetSeed() = Unit
    }

    private class OneLocationType : LocationTypeApiClient {
        override suspend fun getAllLocationTypes() =
            LocationTypeDto.ListResponseDto(
                listOf(LocationTypeDto.ResponseDto(INDOOR, "Innen", "Indoor", "Indoor", false, false, 0)),
            )

        override suspend fun getLocationTypeById(id: Long) = error("unused")

        override suspend fun createLocationType(request: LocationTypeDto.CreateRequestDto) = error("unused")

        override suspend fun updateLocationType(
            id: Long,
            request: LocationTypeDto.UpdateRequestDto,
        ) = error("unused")

        override suspend fun deleteLocationType(id: Long) = error("unused")
    }

    private companion object {
        const val INDOOR = 1L

        fun table(
            id: Long,
            number: String,
            status: String,
            openAmount: String? = null,
            sessionId: Long? = null,
        ) = RTable(
            id = id,
            tableNumber = number,
            seats = 4,
            locationTypeId = INDOOR,
            locationTypeName = "Innen",
            tableStatus = status,
            currentSessionId = sessionId,
            gridX = null,
            gridY = null,
            rotation = null,
            openAmount = openAmount,
        )
    }
}
