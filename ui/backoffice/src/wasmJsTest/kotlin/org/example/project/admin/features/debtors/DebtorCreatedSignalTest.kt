package org.example.project.admin.features.debtors

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.admin.features.debtors.presentation.DebtorDetailViewModel
import org.example.project.core.domain.dto.debtor.DebtorDto
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.PosApiException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * NEW-20: a successful create announces the new id once, so the create screen confirms it and leaves
 * for that debtor's own route instead of staying on a form that a second Save would PUT onto.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DebtorCreatedSignalTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a create announces the new id and leaves the form clean`() =
        runTest(dispatcher) {
            val vm = DebtorDetailViewModel(FakeDebtorApiClient())
            vm.load(null)
            vm.onNameChange("QA Neuanlage")

            vm.save()
            advanceUntilIdle()

            assertEquals(99L, vm.state.createdDebtorId)
            assertFalse(vm.state.isDirty, "the save re-pinned pristine, so leaving never prompts")
        }

    @Test
    fun `consuming the signal spends it`() =
        runTest(dispatcher) {
            val vm = DebtorDetailViewModel(FakeDebtorApiClient())
            vm.load(null)
            vm.onNameChange("QA Neuanlage")
            vm.save()
            advanceUntilIdle()

            vm.consumeCreated()

            assertNull(vm.state.createdDebtorId)
        }

    @Test
    fun `an update of an existing debtor does not announce a create`() =
        runTest(dispatcher) {
            val api = FakeDebtorApiClient().apply { debtors = listOf(DebtorDto.ResponseDto(id = 1, name = "Acme GmbH")) }
            val vm = DebtorDetailViewModel(api)
            vm.load(1)
            advanceUntilIdle()

            vm.onNameChange("Acme Umbenannt GmbH")
            vm.save()
            advanceUntilIdle()

            assertNull(vm.state.createdDebtorId)
        }

    @Test
    fun `a failed create announces nothing`() =
        runTest(dispatcher) {
            val api = FakeDebtorApiClient().apply { saveThrows = PosApiException(PosErrorCode.INTERNAL_ERROR, 500, "boom", null) }
            val vm = DebtorDetailViewModel(api)
            vm.load(null)
            vm.onNameChange("QA Neuanlage")

            vm.save()
            advanceUntilIdle()

            assertNull(vm.state.createdDebtorId)
            assertNull(vm.state.id, "still the create form")
        }
}
