package org.example.project.admin.features.debtors

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.admin.features.debtors.presentation.DebtorDetailViewModel
import org.example.project.admin.features.debtors.presentation.DebtorFormField
import org.example.project.admin.features.debtors.presentation.DebtorsViewModel
import org.example.project.admin.features.salesinvoices.FakeSalesInvoiceApiClient
import org.example.project.core.domain.dto.debtor.DebtorDto
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

private fun debtor(
    id: Long,
    name: String = "Musterfirma GmbH",
    active: Boolean = true,
    invoices: Long = 0,
) = DebtorDto.ResponseDto(id = id, name = name, isActive = active, salesInvoiceCount = invoices)

@OptIn(ExperimentalCoroutinesApi::class)
class DebtorsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `load fills the list and clears the loading flag`() =
        runTest(dispatcher) {
            val api = FakeDebtorApiClient()
            api.debtors = listOf(debtor(1), debtor(2, "Catering Nord"))
            api.total = 2

            val vm = DebtorsViewModel(api, FakeSalesInvoiceApiClient())
            vm.load()
            advanceUntilIdle()

            assertEquals(2, vm.state.items.size)
            assertEquals(2, vm.state.total)
            assertFalse(vm.state.isLoading)
            assertNull(vm.state.error)
        }

    @Test
    fun `the active filter is sent to the server, not applied client-side`() =
        runTest(dispatcher) {
            val api = FakeDebtorApiClient()
            api.debtors = listOf(debtor(1), debtor(2, active = false))
            api.total = 2

            val vm = DebtorsViewModel(api, FakeSalesInvoiceApiClient())
            vm.load()
            advanceUntilIdle()
            assertEquals(false, api.lastActiveOnly)

            vm.setActiveOnly(true)
            advanceUntilIdle()

            assertEquals(true, api.lastActiveOnly, "the filter must reach the request, or paging lies")
            assertEquals(1, vm.state.items.size)
        }

    @Test
    fun `a failed load surfaces the error and stops loading`() =
        runTest(dispatcher) {
            val api = FakeDebtorApiClient()
            api.listThrows = PosApiException(PosErrorCode.INTERNAL_ERROR, 500, "boom", null)

            val vm = DebtorsViewModel(api, FakeSalesInvoiceApiClient())
            vm.load()
            advanceUntilIdle()

            assertNotNull(vm.state.error)
            assertFalse(vm.state.isLoading)
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DebtorDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a null id starts an empty create form and saves with a POST`() =
        runTest(dispatcher) {
            val api = FakeDebtorApiClient()
            val vm = DebtorDetailViewModel(api)

            vm.load(null)
            vm.onNameChange("Catering Nord")
            vm.save()
            advanceUntilIdle()

            assertEquals("Catering Nord", api.created?.name)
            assertNull(api.updated, "a new debtor must not be PUT to an id it does not have")
            assertEquals(99, vm.state.id)
            assertFalse(vm.state.isDirty, "a successful save re-pins the pristine snapshot")
        }

    @Test
    fun `a blank name never reaches the server`() =
        runTest(dispatcher) {
            val api = FakeDebtorApiClient()
            val vm = DebtorDetailViewModel(api)

            vm.load(null)
            vm.onNameChange("   ")
            vm.save()
            advanceUntilIdle()

            assertNull(api.created)
            assertEquals(setOf(DebtorFormField.NAME), vm.state.fieldErrors.keys)
            assertEquals(1, vm.state.validationAttempt, "each refused Save scrolls to the first error again")

            vm.onNameChange("Catering Nord")
            assertTrue(vm.state.fieldErrors.isEmpty(), "typing into the flagged field clears its error")
            vm.save()
            advanceUntilIdle()
            assertEquals("Catering Nord", api.created?.name)
        }

    @Test
    fun `payment terms that are not a whole number are flagged instead of silently dropped`() =
        runTest(dispatcher) {
            val api = FakeDebtorApiClient()
            val vm = DebtorDetailViewModel(api)

            vm.load(null)
            vm.onNameChange("Catering Nord")
            vm.onPaymentTermsChange("14 Tage")
            vm.save()
            advanceUntilIdle()

            assertNull(api.created)
            assertEquals(setOf(DebtorFormField.PAYMENT_TERMS), vm.state.fieldErrors.keys)
            assertFalse(vm.state.isSaving, "a refused save leaves no stuck spinner")
        }

    @Test
    fun `a failed save keeps the screen dirty`() =
        runTest(dispatcher) {
            val api = FakeDebtorApiClient()
            api.debtors = listOf(debtor(1))
            api.saveThrows = PosApiException(PosErrorCode.DEBTOR_NUMBER_TAKEN, 409, "taken", null)
            val vm = DebtorDetailViewModel(api)

            vm.load(1)
            advanceUntilIdle()
            vm.onDebtorNoChange("10247")
            vm.save()
            advanceUntilIdle()

            assertNotNull(vm.state.error)
            assertFalse(vm.state.isSaving)
            assertTrue(vm.state.isDirty, "the operator's edit must survive a refused save")
        }

    @Test
    fun `delete is not even attempted for a debtor an invoice references`() =
        runTest(dispatcher) {
            val api = FakeDebtorApiClient()
            api.debtors = listOf(debtor(1, invoices = 3))
            val vm = DebtorDetailViewModel(api)

            vm.load(1)
            advanceUntilIdle()
            vm.delete()
            advanceUntilIdle()

            assertTrue(api.deletedIds.isEmpty(), "the UI half of the guard stops it before the request")
            assertFalse(vm.state.canDelete)
            assertFalse(vm.state.isDeleted)
        }

    @Test
    fun `an unreferenced debtor is deleted and the screen reports it`() =
        runTest(dispatcher) {
            val api = FakeDebtorApiClient()
            api.debtors = listOf(debtor(1, invoices = 0))
            val vm = DebtorDetailViewModel(api)

            vm.load(1)
            advanceUntilIdle()
            vm.delete()
            advanceUntilIdle()

            assertEquals(listOf(1L), api.deletedIds)
            assertTrue(vm.state.isDeleted)
        }

    @Test
    fun `the booking defaults round-trip through the form`() =
        runTest(dispatcher) {
            val api = FakeDebtorApiClient()
            api.debtors = listOf(debtor(1).copy(defaultRevenueAccountNo = "8400", defaultBuCode = "100"))
            val vm = DebtorDetailViewModel(api)

            vm.load(1)
            advanceUntilIdle()
            assertEquals("8400", vm.state.form.revenueAccountNo)
            assertEquals("100", vm.state.form.buCode)

            vm.onRevenueAccountChange("8300")
            vm.onBuCodeChange("200")
            vm.save()
            advanceUntilIdle()

            assertEquals("8300", api.updated?.second?.defaultRevenueAccountNo)
            assertEquals("200", api.updated?.second?.defaultBuCode)
        }

    @Test
    fun `blank booking defaults are sent as null, not empty strings`() =
        runTest(dispatcher) {
            val api = FakeDebtorApiClient()
            val vm = DebtorDetailViewModel(api)

            vm.load(null)
            vm.onNameChange("Catering Nord")
            vm.onRevenueAccountChange("   ")
            vm.onBuCodeChange("")
            vm.save()
            advanceUntilIdle()

            assertNull(api.created?.defaultRevenueAccountNo)
            assertNull(api.created?.defaultBuCode)
        }
}
