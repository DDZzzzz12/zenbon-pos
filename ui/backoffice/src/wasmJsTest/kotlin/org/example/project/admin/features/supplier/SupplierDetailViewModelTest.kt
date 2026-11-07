package org.example.project.admin.features.supplier

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.supplier.presentation.SupplierDetailViewModel
import org.example.project.core.domain.dto.supplier.SupplierDto
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

@OptIn(ExperimentalCoroutinesApi::class)
class SupplierDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun loaded(api: FakeSupplierApiClient): SupplierDetailViewModel {
        val vm = SupplierDetailViewModel(api)
        vm.load(1)
        return vm
    }

    @Test
    fun `a freshly loaded supplier is not dirty`() =
        runTest(dispatcher) {
            val api = FakeSupplierApiClient()
            api.suppliers = listOf(SupplierDto.ResponseDto(id = 1, name = "Metro AG", vatId = "DE123456789"))

            val vm = loaded(api)
            advanceUntilIdle()

            assertEquals("Metro AG", vm.state.form.name)
            assertFalse(vm.state.isDirty)
        }

    @Test
    fun `editing a field makes it dirty and editing back makes it clean`() =
        runTest(dispatcher) {
            val api = FakeSupplierApiClient()
            api.suppliers = listOf(SupplierDto.ResponseDto(id = 1, name = "Metro AG"))

            val vm = loaded(api)
            advanceUntilIdle()
            vm.onNameChange("Metro Deutschland")
            assertTrue(vm.state.isDirty)
            vm.onNameChange("Metro AG")
            assertFalse(vm.state.isDirty)
        }

    @Test
    fun `a provisional supplier can be confirmed unchanged and a confirmed one cannot`() =
        runTest(dispatcher) {
            val api = FakeSupplierApiClient()
            api.suppliers = listOf(SupplierDto.ResponseDto(id = 1, name = "Metro AG", isProvisional = true))

            val vm = loaded(api)
            advanceUntilIdle()
            assertFalse(vm.state.isDirty)
            assertTrue(vm.state.canSave, "an unchanged provisional supplier must be confirmable")

            vm.save()
            advanceUntilIdle()

            assertEquals(1, api.updates.size)
            assertFalse(vm.state.isProvisional)
            assertFalse(vm.state.canSave, "once confirmed and clean there is nothing to save")
        }

    @Test
    fun `a blank name blocks save and reports a field error`() =
        runTest(dispatcher) {
            val api = FakeSupplierApiClient()
            api.suppliers = listOf(SupplierDto.ResponseDto(id = 1, name = "Metro AG"))

            val vm = loaded(api)
            advanceUntilIdle()
            vm.onNameChange("   ")
            vm.save()
            advanceUntilIdle()

            assertEquals(AdminStringKeys.SupplierErrorNameRequired, vm.state.nameError)
            assertTrue(api.updates.isEmpty(), "save must not dispatch with an invalid form")
        }

    /** NEW-20: the supplier e-mail shares `business_partner.email` with debtors and the same check. */
    @Test
    fun `a malformed e-mail blocks save, editing it clears the error`() =
        runTest(dispatcher) {
            val api = FakeSupplierApiClient()
            api.suppliers = listOf(SupplierDto.ResponseDto(id = 1, name = "Metro AG"))

            val vm = loaded(api)
            advanceUntilIdle()
            vm.onEmailChange("kaputt")
            vm.save()
            advanceUntilIdle()

            assertEquals(AdminStringKeys.PartnerEmailInvalid, vm.state.emailError)
            assertNull(vm.state.nameError)
            assertTrue(api.updates.isEmpty(), "save must not dispatch with an invalid e-mail")

            vm.onEmailChange("einkauf@metro.de")
            assertNull(vm.state.emailError)
            vm.save()
            advanceUntilIdle()
            assertEquals(1, api.updates.size)
        }

    @Test
    fun `saving sends every edited field and re-pins pristine`() =
        runTest(dispatcher) {
            val api = FakeSupplierApiClient()
            api.suppliers = listOf(SupplierDto.ResponseDto(id = 1, name = "Metro AG", isProvisional = true))

            val vm = loaded(api)
            advanceUntilIdle()
            vm.onNameChange("Metro AG")
            vm.onCreditorNoChange("70001")
            vm.save()
            advanceUntilIdle()

            assertEquals(1, api.updates.size)
            val (_, sent) = api.updates.first()
            assertEquals("70001", sent.creditorNo)
            assertFalse(vm.state.isDirty, "pristine must be re-pinned after a successful save")
        }

    @Test
    fun `an empty numeric field is sent as null, not zero`() =
        runTest(dispatcher) {
            val api = FakeSupplierApiClient()
            api.suppliers = listOf(SupplierDto.ResponseDto(id = 1, name = "Metro AG", paymentTermsDays = 30))

            val vm = loaded(api)
            advanceUntilIdle()
            vm.onPaymentTermsChange("")
            vm.save()
            advanceUntilIdle()

            val (_, sent) = api.updates.first()
            assertNull(sent.paymentTermsDays)
        }

    @Test
    fun `a failed save leaves the form dirty`() =
        runTest(dispatcher) {
            val api = FakeSupplierApiClient()
            api.suppliers = listOf(SupplierDto.ResponseDto(id = 1, name = "Metro AG"))

            val vm = loaded(api)
            advanceUntilIdle()
            vm.onNameChange("Metro Deutschland")
            // Set after the load has settled: the fake throws failWith from get() too, so setting
            // it earlier would fail the load instead of the save.
            api.failWith = PosApiException(PosErrorCode.INTERNAL_ERROR, 500, "boom", null)
            vm.save()
            advanceUntilIdle()

            // pristine must NOT be re-pinned on failure, or the operator's unsaved edit silently
            // becomes "saved" in the UI while the server never received it.
            assertTrue(vm.state.isDirty)
            assertNotNull(vm.state.error)
        }

    @Test
    fun `a taken VAT id surfaces the actionable message, not a generic failure`() =
        runTest(dispatcher) {
            val api = FakeSupplierApiClient()
            api.suppliers = listOf(SupplierDto.ResponseDto(id = 1, name = "Metro AG"))

            val vm = loaded(api)
            advanceUntilIdle()
            vm.onVatIdChange("DE123456789")
            api.failWith = PosApiException(PosErrorCode.SUPPLIER_VAT_ID_TAKEN, 409, "taken", null)
            vm.save()
            advanceUntilIdle()

            // messageKey is declared on the UiError base, so this asserts the mapping itself, not
            // merely that some error exists — the generic branch produces a different key.
            assertEquals(AdminStringKeys.SupplierErrorVatIdTaken, vm.state.error?.messageKey)
        }
}
