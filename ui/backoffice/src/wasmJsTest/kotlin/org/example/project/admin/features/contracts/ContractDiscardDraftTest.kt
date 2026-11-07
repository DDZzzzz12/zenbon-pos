package org.example.project.admin.features.contracts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.admin.features.contracts.presentation.ContractDetailViewModel
import org.example.project.admin.features.contracts.presentation.ContractForm
import org.example.project.admin.features.supplier.FakeSupplierApiClient
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The leave guard must never prompt on an untouched create form, which `isDirty` reports dirty
 * by design; `hasUnsavedEdits` is the guard's signal.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContractDiscardDraftTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun createVm() = ContractDetailViewModel(FakeContractApiClient(), FakeSupplierApiClient()).also { it.load(null) }

    @Test
    fun `an untouched create form has no unsaved edits`() =
        runTest(dispatcher) {
            val vm = createVm()

            assertTrue(vm.state.isDirty)
            assertFalse(vm.state.hasUnsavedEdits)
        }

    @Test
    fun `typing then undoing is clean`() =
        runTest(dispatcher) {
            val vm = createVm()
            vm.onNameChange("Miete")
            assertTrue(vm.state.hasUnsavedEdits)

            vm.onNameChange("")

            assertFalse(vm.state.hasUnsavedEdits)
        }

    @Test
    fun `discard empties a create draft`() =
        runTest(dispatcher) {
            val vm = createVm()
            vm.onNameChange("Miete")

            vm.discardDraft()

            assertFalse(vm.state.hasUnsavedEdits)
            assertEquals(ContractForm(), vm.state.form)
        }
}
