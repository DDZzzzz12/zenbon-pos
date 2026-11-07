package org.example.project.admin.features.kassenbuch

import kotlinx.coroutines.test.runTest
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.kassenbuch.presentation.BookManagementViewModel
import org.example.project.admin.features.kassenbuch.presentation.CashBookField
import org.example.project.admin.features.kassenbuch.presentation.CashBookFormState
import org.example.project.core.presentation.strings.keys.CommonKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The cash-book form's Save is never disabled by the input: the click validates, and an empty
 * required field becomes an error only from then on (an untouched form stays quiet).
 */
class CashBookFormValidationTest {
    private fun viewModel(api: FakeCashBookApiClient) =
        BookManagementViewModel(
            cashBookApiClient = api,
            fiscalConfigurationApiClient = FakeFiscalConfigurationApiClient(),
        )

    @Test
    fun anUntouchedForm_showsNoErrors() {
        assertTrue(CashBookFormState().fieldErrors.isEmpty())
    }

    @Test
    fun submittingAnEmptyCreate_flagsNameAndAccount_andMakesNoRequest() =
        runTest {
            val api = FakeCashBookApiClient(books = emptyList())
            val vm = viewModel(api)
            vm.load()
            vm.openCreateForm()

            vm.submitBook()

            val form = assertNotNull(vm.state.form)
            assertEquals(1, form.validationAttempt)
            assertEquals(
                mapOf(
                    CashBookField.NAME to AdminStringKeys.KassenbuchBookNameInvalid,
                    CashBookField.DATEV_ACCOUNT to CommonKeys.fieldRequired,
                ),
                form.fieldErrors,
            )
            assertTrue(api.createRequests.isEmpty())
            assertFalse(vm.state.isDirty, "a refused Save on a pristine form leaves it clean")

            vm.setBookName("Zweitkasse")
            vm.setBookDatevAccount("1020")
            assertTrue(assertNotNull(vm.state.form).fieldErrors.isEmpty())
            vm.submitBook()
            assertEquals("Zweitkasse", api.createRequests.single().name)
        }

    @Test
    fun anEmptiedSortOrder_isRequiredAfterAnAttempt() {
        val form = CashBookFormState(name = "Tresor", datevAccount = "1010", sortOrder = "")
        assertTrue(form.fieldErrors.isEmpty(), "before the first Save an empty field is not an error")
        assertEquals(mapOf(CashBookField.SORT_ORDER to CommonKeys.fieldRequired), form.copy(validationAttempt = 1).fieldErrors)
        assertEquals(
            mapOf(CashBookField.SORT_ORDER to AdminStringKeys.KassenbuchBookSortOrderInvalid),
            form.copy(sortOrder = "-1").fieldErrors,
        )
    }
}
