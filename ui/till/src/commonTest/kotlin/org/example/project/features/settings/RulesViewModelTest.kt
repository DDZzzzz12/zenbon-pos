package org.example.project.features.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.core.data.remote.EcrConfigurationApiClient
import org.example.project.core.data.remote.EmployeeApiClient
import org.example.project.core.domain.dto.employee.EmployeeDto
import org.example.project.core.domain.dto.employee.EmployeeInfoDto
import org.example.project.core.domain.dto.setup.ShopConfigurationDto
import org.example.project.core.presentation.settings.NoOpDeviceSettingsMirror
import org.example.project.features.settings.presentation.viewmodel.RulesViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RulesViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `enabling round-robin disables employee selection`() =
        runTest(testDispatcher) {
            val vm = makeVm()
            advanceUntilIdle()

            vm.setEmployeeSelectionOnFreeTable(true)
            vm.setRoundRobinAssignment(true)

            val s = vm.state.value
            assertTrue(s.roundRobinAssignment)
            assertFalse(s.employeeSelectionOnFreeTable)
        }

    @Test
    fun `enabling employee selection disables round-robin`() =
        runTest(testDispatcher) {
            val vm = makeVm()
            advanceUntilIdle()

            vm.setRoundRobinAssignment(true)
            vm.setEmployeeSelectionOnFreeTable(true)

            val s = vm.state.value
            assertTrue(s.employeeSelectionOnFreeTable)
            assertFalse(s.roundRobinAssignment)
        }

    @Test
    fun `a terminal-role employee is not offered in the rotation pool`() =
        runTest(testDispatcher) {
            val summaries =
                listOf(
                    EmployeeDto.EmployeeSummaryResponseDto(
                        id = "1",
                        username = "cashier1",
                        employeeNumber = "001",
                        role = "CASHIER",
                        employmentStatus = "ACTIVE",
                        isActive = true,
                        hasEmployeeInfo = true,
                        trainingMode = false,
                        inRotationPool = true,
                    ),
                    EmployeeDto.EmployeeSummaryResponseDto(
                        id = "2",
                        username = "station-2",
                        employeeNumber = "T001",
                        role = "TERMINAL_DEVICE",
                        employmentStatus = "ACTIVE",
                        isActive = true,
                        hasEmployeeInfo = false,
                        trainingMode = false,
                        inRotationPool = true,
                    ),
                    EmployeeDto.EmployeeSummaryResponseDto(
                        id = "3",
                        username = "barista1",
                        employeeNumber = "003",
                        role = "BARISTA",
                        employmentStatus = "ACTIVE",
                        isActive = true,
                        hasEmployeeInfo = true,
                        trainingMode = false,
                        inRotationPool = true,
                    ),
                )
            val vm =
                RulesViewModel(
                    configApi = FakeEcrConfigurationApiClient(),
                    employeeApi = FakeEmployeeApiClient(summaries = summaries),
                    deviceMirror = NoOpDeviceSettingsMirror,
                )
            advanceUntilIdle()

            val s = vm.state.value
            assertEquals(listOf("1", "3"), s.rotationPoolEmployees.map { it.id })
        }

    @Test
    fun `rotation pool is listed by employee number whatever order the server returns`() =
        runTest(testDispatcher) {
            fun summary(
                id: String,
                number: String,
            ) = EmployeeDto.EmployeeSummaryResponseDto(
                id = id,
                username = "user-$id",
                employeeNumber = number,
                role = "CASHIER",
                employmentStatus = "ACTIVE",
                isActive = true,
                hasEmployeeInfo = true,
                trainingMode = false,
                inRotationPool = true,
            )
            val vm =
                RulesViewModel(
                    configApi = FakeEcrConfigurationApiClient(),
                    employeeApi =
                        FakeEmployeeApiClient(
                            summaries = listOf(summary("c", "003"), summary("a", "001"), summary("b", "002")),
                        ),
                    deviceMirror = NoOpDeviceSettingsMirror,
                )
            advanceUntilIdle()

            val ids =
                vm.state.value.rotationPoolEmployees
                    .map { it.id }
            assertEquals(listOf("a", "b", "c"), ids)
        }

    private fun makeVm(): RulesViewModel =
        RulesViewModel(
            configApi = FakeEcrConfigurationApiClient(),
            employeeApi = FakeEmployeeApiClient(),
            deviceMirror = NoOpDeviceSettingsMirror,
        )
}

// ─────────────────── Inline fakes ───────────────────

private class FakeEcrConfigurationApiClient : EcrConfigurationApiClient {
    override suspend fun getOrderRules(shopId: Long) =
        ShopConfigurationDto.OrderRulesResponseDto(
            shopId = shopId,
            employeeSelectionOnFreeTable = false,
            roundRobinAssignment = false,
        )

    override suspend fun updateOrderRules(
        shopId: Long,
        request: ShopConfigurationDto.UpdateOrderRulesRequestDto,
    ) = ShopConfigurationDto.OrderRulesResponseDto(
        shopId = shopId,
        employeeSelectionOnFreeTable = request.employeeSelectionOnFreeTable,
        roundRobinAssignment = request.roundRobinAssignment,
    )

    override suspend fun getBillingRules(shopId: Long) =
        ShopConfigurationDto.BillingRulesResponseDto(
            shopId = shopId,
            allowCrossEmployeeBilling = false,
        )

    override suspend fun updateBillingRules(
        shopId: Long,
        request: ShopConfigurationDto.UpdateBillingRulesRequestDto,
    ) = ShopConfigurationDto.BillingRulesResponseDto(
        shopId = shopId,
        allowCrossEmployeeBilling = request.allowCrossEmployeeBilling,
    )

    override suspend fun getVoucherConfiguration(shopId: Long) = throw NotImplementedError()

    override suspend fun updateVoucherConfiguration(
        shopId: Long,
        request: ShopConfigurationDto.UpdateVoucherConfigRequestDto,
    ) = throw NotImplementedError()

    override suspend fun getVatMapping(shopId: Long) = throw NotImplementedError()

    override suspend fun updateVatMapping(
        shopId: Long,
        request: ShopConfigurationDto.UpdateVatMappingRequestDto,
    ) = throw NotImplementedError()

    override suspend fun getPrintSettings(shopId: Long) = throw NotImplementedError()

    override suspend fun updatePrintSettings(
        shopId: Long,
        request: ShopConfigurationDto.UpdatePrintSettingsRequestDto,
    ) = throw NotImplementedError()
}

private class FakeEmployeeApiClient(
    private val summaries: List<EmployeeDto.EmployeeSummaryResponseDto> = emptyList(),
) : EmployeeApiClient {
    override suspend fun getEmployeeSummaries(
        limit: Int?,
        offset: Int?,
        active: Boolean?,
    ): List<EmployeeDto.EmployeeSummaryResponseDto> = summaries

    override suspend fun setRotationPool(
        id: String,
        inRotationPool: Boolean,
    ) = throw NotImplementedError()

    override suspend fun setLanguageForEmployee(languageCode: String) = throw NotImplementedError()

    override suspend fun getActiveEmployees() = throw NotImplementedError()

    override suspend fun getCurrentEmployee() = throw NotImplementedError()

    override suspend fun getEmployeeById(id: String) = throw NotImplementedError()

    override suspend fun createEmployee(request: EmployeeDto.CreateEmployeeRequestDto) = throw NotImplementedError()

    override suspend fun updateEmployee(
        id: String,
        request: EmployeeDto.UpdateEmployeeRequestDto,
    ) = throw NotImplementedError()

    override suspend fun assignPermissionGroup(
        employeeId: String,
        groupCode: String,
    ) = throw NotImplementedError()

    override suspend fun deleteEmployee(id: String) = throw NotImplementedError()

    override suspend fun deactivateEmployee(id: String) = throw NotImplementedError()

    override suspend fun changePassword(
        id: String,
        request: EmployeeDto.PasswordChangeRequestDto,
    ) = throw NotImplementedError()

    override suspend fun getMyEmployeeInfo() = throw NotImplementedError()

    override suspend fun updateMyEmployeeInfo(request: EmployeeInfoDto.UpdateEmployeeInfoRequestDto) = throw NotImplementedError()

    override suspend fun getEmployeeInfoByEmployeeId(employeeId: String) = throw NotImplementedError()

    override suspend fun updateEmployeeInfoByEmployeeId(
        employeeId: String,
        request: EmployeeInfoDto.UpdateEmployeeInfoRequestDto,
    ) = throw NotImplementedError()

    override suspend fun createEmployeeInfo(request: EmployeeInfoDto.CreateEmployeeInfoRequestDto) = throw NotImplementedError()

    override suspend fun changeMyPin(newPin: String) = throw NotImplementedError()

    override suspend fun authenticate(
        username: String,
        password: String,
    ) = throw NotImplementedError()

    override suspend fun resetPassword(
        id: String,
        newPassword: String,
    ) = throw NotImplementedError()

    override suspend fun resetPin(
        id: String,
        newPin: String,
    ) = throw NotImplementedError()
}
