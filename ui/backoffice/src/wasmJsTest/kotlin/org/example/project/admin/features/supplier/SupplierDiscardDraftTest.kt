package org.example.project.admin.features.supplier

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.admin.features.supplier.presentation.SupplierDetailViewModel
import org.example.project.core.domain.dto.supplier.SupplierDto
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `discardDraft` is what the leave guard calls on Discard; the state must come back clean. */
@OptIn(ExperimentalCoroutinesApi::class)
class SupplierDiscardDraftTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `discard returns the form to what was loaded`() =
        runTest(dispatcher) {
            val api = FakeSupplierApiClient()
            api.suppliers = listOf(SupplierDto.ResponseDto(id = 1, name = "Metro AG"))
            val vm = SupplierDetailViewModel(api)
            vm.load(1)
            advanceUntilIdle()
            vm.onNameChange("Etwas anderes")
            assertTrue(vm.state.isDirty)

            vm.discardDraft()

            assertFalse(vm.state.isDirty)
            assertEquals("Metro AG", vm.state.form.name)
        }
}
