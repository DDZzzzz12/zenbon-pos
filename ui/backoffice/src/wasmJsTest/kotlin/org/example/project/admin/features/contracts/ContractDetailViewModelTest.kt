package org.example.project.admin.features.contracts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.admin.features.contracts.presentation.ContractDetailViewModel
import org.example.project.admin.features.contracts.presentation.ContractFormField
import org.example.project.admin.features.supplier.FakeSupplierApiClient
import org.example.project.core.domain.dto.contract.ContractDto
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun instalment(
    id: Long,
    dueDate: String,
    status: String,
) = ContractDto.InstalmentDto(
    id = id,
    contractId = 1,
    dueDate = dueDate,
    expectedAmount = "1200.00",
    openAmount = if (status == "PAID") "0.00" else "1200.00",
    status = status,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ContractDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun filledForm(vm: ContractDetailViewModel) {
        vm.onNameChange("Miete Ladenlokal")
        vm.onIntervalChange("MONTHLY")
        vm.onDueDayChange("1")
        vm.onStartDateChange("2026-01-01")
        vm.onExpectedAmountChange("1200,00")
    }

    @Test
    fun `a null id seeds an empty form and makes no request`() =
        runTest(dispatcher) {
            val api = FakeContractApiClient()
            val vm = ContractDetailViewModel(api, FakeSupplierApiClient())

            vm.load(null)
            advanceUntilIdle()

            assertTrue(api.getCalls.isEmpty(), "create form must not fetch a contract")
            assertTrue(api.instalmentCalls.isEmpty(), "a contract with no id has no instalments")
            assertNull(vm.state.id)
            assertEquals("", vm.state.form.name)
            assertTrue(vm.state.instalments.isEmpty())
        }

    @Test
    fun `loading an existing contract fills the form and its instalments`() =
        runTest(dispatcher) {
            val api = FakeContractApiClient()
            api.contracts = listOf(contract(1))
            api.instalmentsByContract =
                mapOf(
                    1L to
                        listOf(
                            instalment(10, "2026-01-01", "PAID"),
                            instalment(11, "2026-02-01", "OVERDUE"),
                        ),
                )

            val vm = ContractDetailViewModel(api, FakeSupplierApiClient())
            vm.load(1)
            advanceUntilIdle()

            assertEquals(listOf(1L), api.getCalls)
            assertEquals(listOf(1L), api.instalmentCalls)
            assertEquals("Miete Ladenlokal", vm.state.form.name)
            assertEquals(2, vm.state.instalments.size)
        }

    @Test
    fun `saving a new contract posts it`() =
        runTest(dispatcher) {
            val api = FakeContractApiClient()
            val vm = ContractDetailViewModel(api, FakeSupplierApiClient())
            vm.load(null)
            advanceUntilIdle()
            filledForm(vm)

            vm.save()
            advanceUntilIdle()

            assertEquals(1, api.created.size)
            assertTrue(api.updated.isEmpty())
            // The typed comma is canonicalised before dispatch — money never leaves as "1200,00".
            assertEquals("1200.00", api.created.single().expectedAmount)
            assertEquals(1L, vm.state.id)
        }

    @Test
    fun `saving an existing contract puts it`() =
        runTest(dispatcher) {
            val api = FakeContractApiClient()
            api.contracts = listOf(contract(7))
            val vm = ContractDetailViewModel(api, FakeSupplierApiClient())
            vm.load(7)
            advanceUntilIdle()
            vm.onNameChange("Miete Ladenlokal (neu)")

            vm.save()
            advanceUntilIdle()

            assertEquals(listOf(7L), api.updated.map { it.first })
            assertTrue(api.created.isEmpty())
        }

    @Test
    fun `an invalid dueDay dispatches nothing`() =
        runTest(dispatcher) {
            val api = FakeContractApiClient()
            val vm = ContractDetailViewModel(api, FakeSupplierApiClient())
            vm.load(null)
            advanceUntilIdle()
            filledForm(vm)
            vm.onDueDayChange("32")

            vm.save()
            advanceUntilIdle()

            assertTrue(api.created.isEmpty(), "the client half must stop the operator before the 400")
            assertNotNull(vm.state.dueDayError)
        }

    @Test
    fun `a blank name and an unparseable amount are each refused before dispatch`() =
        runTest(dispatcher) {
            val api = FakeContractApiClient()
            val vm = ContractDetailViewModel(api, FakeSupplierApiClient())
            vm.load(null)
            advanceUntilIdle()
            filledForm(vm)
            vm.onNameChange("  ")
            vm.onExpectedAmountChange("zwölfhundert")

            vm.save()
            advanceUntilIdle()

            assertTrue(api.created.isEmpty())
            assertNotNull(vm.state.nameError)
            assertNotNull(vm.state.amountError)
        }

    @Test
    fun `an empty create form is refused field by field and each press asks to reveal the first`() =
        runTest(dispatcher) {
            val api = FakeContractApiClient()
            val vm = ContractDetailViewModel(api, FakeSupplierApiClient())
            vm.load(null)
            advanceUntilIdle()

            vm.save()
            advanceUntilIdle()
            vm.save()
            advanceUntilIdle()

            assertTrue(api.created.isEmpty())
            assertEquals(2, vm.state.validationAttempt)
            assertEquals(
                ContractFormField.NAME,
                vm.state.errors
                    .byField()
                    .keys
                    .first(),
                "the name field is focused first",
            )
            assertTrue(ContractFormField.START_DATE in vm.state.errors.byField(), "a blank start date is required")

            vm.onNameChange("Miete")
            assertNull(vm.state.nameError, "typing into a flagged field clears it")
            assertNotNull(vm.state.dateError, "the other flagged fields stay flagged")
        }

    @Test
    fun `a malformed end date is flagged on the end date, not the start date`() =
        runTest(dispatcher) {
            val api = FakeContractApiClient()
            val vm = ContractDetailViewModel(api, FakeSupplierApiClient())
            vm.load(null)
            advanceUntilIdle()
            filledForm(vm)
            vm.onEndDateChange("31.02.2026")

            vm.save()
            advanceUntilIdle()

            assertTrue(api.created.isEmpty())
            assertNull(vm.state.dateError)
            assertNotNull(vm.state.endDateError)
        }

    @Test
    fun `an unknown interval is refused before dispatch`() =
        runTest(dispatcher) {
            val api = FakeContractApiClient()
            val vm = ContractDetailViewModel(api, FakeSupplierApiClient())
            vm.load(null)
            advanceUntilIdle()
            filledForm(vm)
            vm.onIntervalChange("BIWEEKLY")

            vm.save()
            advanceUntilIdle()

            assertTrue(api.created.isEmpty())
            assertNotNull(vm.state.intervalError)
        }

    @Test
    fun `selecting an instalment cell exposes it, and selecting it again clears it`() =
        runTest(dispatcher) {
            val api = FakeContractApiClient()
            api.contracts = listOf(contract(1))
            api.instalmentsByContract = mapOf(1L to listOf(instalment(10, "2026-01-01", "PARTIAL")))
            val vm = ContractDetailViewModel(api, FakeSupplierApiClient())
            vm.load(1)
            advanceUntilIdle()

            vm.selectInstalment(10)
            assertEquals(10L, vm.state.selectedInstalmentId)
            assertEquals("PARTIAL", vm.state.selectedInstalment?.status)

            vm.selectInstalment(10)
            assertNull(vm.state.selectedInstalmentId)
        }
}
