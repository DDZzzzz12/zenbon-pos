package org.example.project.admin.features.contracts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.admin.features.contracts.presentation.ContractsViewModel
import org.example.project.core.domain.dto.contract.ContractDto
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.PosApiException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal fun contract(
    id: Long,
    name: String = "Miete Ladenlokal",
    active: Boolean = true,
) = ContractDto.ResponseDto(
    id = id,
    name = name,
    interval = "MONTHLY",
    dueDay = 1,
    startDate = "2026-01-01",
    expectedAmount = "1200.00",
    isActive = active,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ContractsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `load fills the list and clears the loading flag`() =
        runTest(dispatcher) {
            val api = FakeContractApiClient()
            api.contracts = listOf(contract(1), contract(2, "Strom"))
            api.total = 2

            val vm = ContractsViewModel(api)
            vm.load()
            advanceUntilIdle()

            assertEquals(2, vm.state.items.size)
            assertEquals(2, vm.state.total)
            assertFalse(vm.state.isLoading)
            assertNull(vm.state.error)
        }

    @Test
    fun `the inactive toggle re-requests with the flag rather than filtering locally`() =
        runTest(dispatcher) {
            val api = FakeContractApiClient()
            api.contracts = listOf(contract(1), contract(2, "Alter Leasingvertrag", active = false))
            api.total = 2

            val vm = ContractsViewModel(api)
            vm.load()
            advanceUntilIdle()
            assertEquals(1, vm.state.items.size)

            vm.setIncludeInactive(true)
            advanceUntilIdle()

            // The flag reaching the fake is the assertion that matters: the server owns the
            // predicate, so a client-side filter would hide rows beyond the current page.
            assertEquals(listOf(false, true), api.listCalls)
            assertEquals(2, vm.state.items.size)
            assertTrue(vm.state.includeInactive)
        }

    @Test
    fun `toggling to the value already applied does not re-request`() =
        runTest(dispatcher) {
            val api = FakeContractApiClient()
            val vm = ContractsViewModel(api)
            vm.load()
            advanceUntilIdle()
            vm.setIncludeInactive(false)
            advanceUntilIdle()

            assertEquals(1, api.listCalls.size)
        }

    @Test
    fun `a failure surfaces as a typed error and stops loading`() =
        runTest(dispatcher) {
            val api = FakeContractApiClient()
            // A PosApiException deliberately: runCatchingApi catches only PosApiException and
            // NetworkApiException, so any other type escapes to viewModelScope and never reaches
            // state.error.
            api.failWith = PosApiException(PosErrorCode.INTERNAL_ERROR, 500, "boom", null)

            val vm = ContractsViewModel(api)
            vm.load()
            advanceUntilIdle()

            assertNotNull(vm.state.error)
            assertFalse(vm.state.isLoading)
        }
}
