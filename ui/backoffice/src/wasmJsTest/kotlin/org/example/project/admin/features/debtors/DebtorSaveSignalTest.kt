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

/**
 * `savedRevision` — how the Debitoren pane learns a save actually landed.
 *
 * This exists because the first version inferred it from `isSaving` falling back to false, and that
 * silently never fired: the PUT succeeded and the list beside the form kept showing the pre-edit
 * name. A counter can be asserted; a flag transition cannot.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DebtorSaveSignalTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun api(existing: DebtorDto.ResponseDto) = FakeDebtorApiClient().apply { debtors = listOf(existing) }

    @Test
    fun `a successful save bumps the revision`() =
        runTest(dispatcher) {
            val vm = DebtorDetailViewModel(api(DebtorDto.ResponseDto(id = 1, name = "Acme GmbH")))
            vm.load(1)
            advanceUntilIdle()
            assertEquals(0, vm.state.savedRevision, "loading is not saving")

            vm.onNameChange("Acme Umbenannt GmbH")
            vm.save()
            advanceUntilIdle()

            assertEquals(1, vm.state.savedRevision)
        }

    /** Two saves must be two signals, or the second edit never reaches the list. */
    @Test
    fun `each save bumps it again`() =
        runTest(dispatcher) {
            val vm = DebtorDetailViewModel(api(DebtorDto.ResponseDto(id = 1, name = "Acme GmbH")))
            vm.load(1)
            advanceUntilIdle()

            vm.onNameChange("Erste Änderung")
            vm.save()
            advanceUntilIdle()
            vm.onNameChange("Zweite Änderung")
            vm.save()
            advanceUntilIdle()

            assertEquals(2, vm.state.savedRevision)
        }

    /** A save that failed has changed nothing, so there is nothing for the list to re-read. */
    @Test
    fun `a failed save does not bump it`() =
        runTest(dispatcher) {
            val client = api(DebtorDto.ResponseDto(id = 1, name = "Acme GmbH"))
            val vm = DebtorDetailViewModel(client)
            vm.load(1)
            advanceUntilIdle()

            client.saveThrows = PosApiException(PosErrorCode.INTERNAL_ERROR, 500, "boom", null)
            vm.onNameChange("Wird nicht gespeichert")
            vm.save()
            advanceUntilIdle()

            assertEquals(0, vm.state.savedRevision)
        }

    /** A blank name is refused before dispatch, so it is not a save either. */
    @Test
    fun `a rejected name does not bump it`() =
        runTest(dispatcher) {
            val vm = DebtorDetailViewModel(api(DebtorDto.ResponseDto(id = 1, name = "Acme GmbH")))
            vm.load(1)
            advanceUntilIdle()

            vm.onNameChange("")
            vm.save()
            advanceUntilIdle()

            assertEquals(0, vm.state.savedRevision)
        }

    /** Loading a different debtor must not look like a save to whoever is watching the counter. */
    @Test
    fun `loading another debtor does not bump it`() =
        runTest(dispatcher) {
            val client =
                FakeDebtorApiClient().apply {
                    debtors =
                        listOf(
                            DebtorDto.ResponseDto(id = 1, name = "Acme GmbH"),
                            DebtorDto.ResponseDto(id = 2, name = "Beta AG"),
                        )
                }
            val vm = DebtorDetailViewModel(client)
            vm.load(1)
            advanceUntilIdle()
            vm.onNameChange("Geändert")
            vm.save()
            advanceUntilIdle()
            val afterSave = vm.state.savedRevision

            vm.load(2)
            advanceUntilIdle()

            assertEquals(afterSave, vm.state.savedRevision, "a load is not a save")
        }
}
