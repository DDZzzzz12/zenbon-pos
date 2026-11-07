package org.example.project.admin.features.supplier

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.admin.features.supplier.presentation.SuppliersViewModel
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

private fun supplier(
    id: Long,
    name: String = "Metro AG",
    provisional: Boolean = false,
) = SupplierDto.ResponseDto(id = id, name = name, isProvisional = provisional)

@OptIn(ExperimentalCoroutinesApi::class)
class SuppliersViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `load fills the list and clears the loading flag`() =
        runTest(dispatcher) {
            val api = FakeSupplierApiClient()
            api.suppliers = listOf(supplier(1), supplier(2, "Getränke Müller"))
            api.total = 2

            val vm = SuppliersViewModel(api)
            vm.load()
            advanceUntilIdle()

            assertEquals(2, vm.state.items.size)
            assertEquals(2, vm.state.total)
            assertFalse(vm.state.isLoading)
            assertNull(vm.state.error)
        }

    @Test
    fun `the provisional filter is sent to the server, not applied client-side`() =
        runTest(dispatcher) {
            val api = FakeSupplierApiClient()
            api.suppliers = listOf(supplier(1), supplier(2, provisional = true))
            api.total = 2

            val vm = SuppliersViewModel(api)
            vm.load()
            advanceUntilIdle()
            vm.setProvisionalOnly(true)
            advanceUntilIdle()

            assertEquals(listOf(false, true), api.listCalls.map { it.first })
            assertEquals(1, vm.state.items.size)
            assertTrue(vm.state.provisionalOnly)
        }

    @Test
    fun `selecting the filter already applied does not re-request`() =
        runTest(dispatcher) {
            val api = FakeSupplierApiClient()
            val vm = SuppliersViewModel(api)
            vm.load()
            advanceUntilIdle()
            vm.setProvisionalOnly(false)
            advanceUntilIdle()

            assertEquals(1, api.listCalls.size)
        }

    @Test
    fun `a failure surfaces as a typed error and stops loading`() =
        runTest(dispatcher) {
            val api = FakeSupplierApiClient()
            // Deliberately a PosApiException, not a bare IllegalStateException: runCatchingApi
            // catches only PosApiException/NetworkApiException (the two the Ktor clients throw),
            // so any other type escapes to viewModelScope and never reaches state.error.
            api.failWith = PosApiException(PosErrorCode.INTERNAL_ERROR, 500, "boom", null)

            val vm = SuppliersViewModel(api)
            vm.load()
            advanceUntilIdle()

            assertNotNull(vm.state.error)
            assertFalse(vm.state.isLoading)
        }
}
