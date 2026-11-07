package org.example.project.features.employee

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.core.domain.dto.employee.EmployeeDto
import org.example.project.core.network.error.NetworkApiException
import org.example.project.features.employee.presentation.EmployeePickerViewModel
import org.example.project.features.employee.presentation.EmployeeSummaryProvider
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun summary(
    id: String,
    number: String,
    role: String = "CASHIER",
) = EmployeeDto.EmployeeSummaryResponseDto(
    id = id,
    username = "user$number",
    employeeNumber = number,
    role = role,
    employmentStatus = "ACTIVE",
    isActive = true,
    hasEmployeeInfo = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class EmployeePickerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var api: FakeEmployeeSummaryApi
    private lateinit var vm: EmployeePickerViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        api = FakeEmployeeSummaryApi()
        vm = EmployeePickerViewModel(api)
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun load_populatesRoster() =
        runTest(dispatcher) {
            api.employees = listOf(summary("uuid-1", "07"), summary("uuid-2", "02"))
            vm.load(activeOnly = true)
            advanceUntilIdle()
            // sorted by employee number, not in the server's order
            assertEquals(listOf("uuid-2", "uuid-1"), vm.state.employees.map { it.id })
            assertTrue(vm.state.loaded)
            assertNull(vm.state.error)
        }

    /**
     * TERMINAL_DEVICE is a device identity, not a person — filtering vouchers/reports by it is
     * meaningless, so the picker excludes it while keeping real staff. Filtered by role, not by
     * name-matching, so a differently-named terminal account is still excluded.
     */
    @Test
    fun load_excludesTerminalDeviceRoleButKeepsRealEmployees() =
        runTest(dispatcher) {
            api.employees =
                listOf(
                    summary("uuid-1", "07", role = "CASHIER"),
                    summary("uuid-2", "99", role = "TERMINAL_DEVICE"),
                    summary("uuid-3", "01", role = "MANAGER"),
                )
            vm.load(activeOnly = true)
            advanceUntilIdle()
            assertEquals(listOf("uuid-3", "uuid-1"), vm.state.employees.map { it.id })
        }

    @Test
    fun load_isGuardedAgainstRefetch() =
        runTest(dispatcher) {
            api.employees = listOf(summary("uuid-1", "07"))
            vm.load(activeOnly = true)
            advanceUntilIdle()
            vm.load(activeOnly = true)
            advanceUntilIdle()
            assertEquals(1, api.callCount)
        }

    /**
     * The guard remembers which `activeOnly` produced the roster, so `LaunchedEffect(activeOnly)`'s
     * key is honoured. Previously a changed value was silently ignored and the caller kept the
     * active-only roster.
     */
    @Test
    fun load_refetchesWhenActiveOnlyChanges() =
        runTest(dispatcher) {
            api.employees = listOf(summary("uuid-1", "07"))
            vm.load(activeOnly = true)
            advanceUntilIdle()
            vm.load(activeOnly = false)
            advanceUntilIdle()
            assertEquals(2, api.callCount)
        }

    /**
     * adminApp's ViewModels survive logout, so without an explicit reset the next session would see
     * the previous session's roster — the fetch-once guard fires once per ViewModel lifetime, not
     * once per login.
     */
    @Test
    fun reset_allowsTheNextSessionToRefetch() =
        runTest(dispatcher) {
            api.employees = listOf(summary("uuid-1", "07"))
            vm.load(activeOnly = true)
            advanceUntilIdle()

            vm.reset()
            assertTrue(vm.state.employees.isEmpty())

            vm.load(activeOnly = true)
            advanceUntilIdle()
            assertEquals(2, api.callCount)
            assertEquals(1, vm.state.employees.size)
        }

    @Test
    fun load_failureLeavesEmptyRosterAndAnError() =
        runTest(dispatcher) {
            api.failure = { throw NetworkApiException(RuntimeException("down")) }
            vm.load(activeOnly = true)
            advanceUntilIdle()
            assertTrue(vm.state.employees.isEmpty())
            assertIs<org.example.project.core.presentation.error.UiError.Network>(vm.state.error)
        }
}

private class FakeEmployeeSummaryApi : EmployeeSummaryProvider {
    var employees: List<EmployeeDto.EmployeeSummaryResponseDto> = emptyList()
    var failure: (() -> Nothing)? = null
    var callCount = 0

    override suspend fun getEmployeeSummaries(
        limit: Int?,
        offset: Int?,
        active: Boolean?,
    ): List<EmployeeDto.EmployeeSummaryResponseDto> {
        callCount++
        failure?.invoke()
        return employees
    }
}
