package org.example.project.admin.features.kassenbuch

import kotlinx.coroutines.test.runTest
import org.example.project.admin.features.kassenbuch.presentation.BookManagementViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Kassenbuch Beleg-age limit in the book-management dialog: loaded, edited, and never sent when
 * the server would refuse it (1..366, its 422) — the client half of CLAUDE.md rule 8.
 */
class BelegAgeSettingTest {
    private fun viewModel(config: FakeFiscalConfigurationApiClient) =
        BookManagementViewModel(cashBookApiClient = FakeCashBookApiClient(books = emptyList()), fiscalConfigurationApiClient = config)

    @Test
    fun theStoredLimitIsShownAfterLoad() =
        runTest {
            val vm = viewModel(FakeFiscalConfigurationApiClient(belegAgeDays = 45))
            vm.load()

            assertEquals("45", vm.state.belegAgeInput)
            assertFalse(vm.state.hasUnsavedBelegAge)
        }

    @Test
    fun aValidChangeIsSaved() =
        runTest {
            val config = FakeFiscalConfigurationApiClient()
            val vm = viewModel(config)
            vm.load()

            vm.setBelegAgeInput("14")
            assertTrue(vm.state.hasUnsavedBelegAge)
            vm.saveBelegAge()

            assertEquals(listOf(14), config.belegAgeRequests.map { it.maxAgeDays })
            assertEquals(14, vm.state.savedBelegAgeDays)
        }

    @Test
    fun aValueTheServerWouldRefuseIsNeverSent() =
        runTest {
            val config = FakeFiscalConfigurationApiClient()
            val vm = viewModel(config)
            vm.load()

            for (bad in listOf("0", "367", "abc", "-5")) {
                vm.setBelegAgeInput(bad)
                assertTrue(vm.state.belegAgeInputInvalid, "'$bad' must show as invalid")
                vm.saveBelegAge()
            }

            assertEquals(0, config.belegAgeRequests.size)
        }

    @Test
    fun nothingIsSavedBeforeTheStoredValueIsRead() =
        runTest {
            val config = FakeFiscalConfigurationApiClient()
            val vm = viewModel(config)

            vm.setBelegAgeInput("20")
            vm.saveBelegAge()

            assertEquals(0, config.belegAgeRequests.size, "a save before load would overwrite a value nobody saw")
        }
}
