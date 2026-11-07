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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `discardDraft` is what the leave guard calls on Discard. It must leave the state clean, so the
 * Slice 1 `keepDraft` restore cannot bring the discarded text back on the next remount.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DebtorDiscardDraftTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun vm() = DebtorDetailViewModel(FakeDebtorApiClient().apply { debtors = listOf(DebtorDto.ResponseDto(id = 1, name = "Acme GmbH")) })

    @Test
    fun `discard returns the form to what was loaded`() =
        runTest(dispatcher) {
            val vm = vm()
            vm.load(1)
            advanceUntilIdle()
            vm.onNameChange("Etwas anderes")
            assertTrue(vm.state.isDirty)

            vm.discardDraft()

            assertFalse(vm.state.isDirty)
            assertEquals("Acme GmbH", vm.state.form.name)
        }

    @Test
    fun `a discarded draft is not kept on remount`() =
        runTest(dispatcher) {
            val vm = vm()
            vm.load(1)
            advanceUntilIdle()
            vm.onNameChange("Etwas anderes")
            vm.discardDraft()

            assertFalse(vm.state.holdsDraftFor(1))
        }

    @Test
    fun `an untouched form stays clean through discard`() =
        runTest(dispatcher) {
            val vm = vm()
            vm.load(1)
            advanceUntilIdle()

            vm.discardDraft()

            assertFalse(vm.state.isDirty)
        }
}
