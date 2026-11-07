package org.example.project.admin.features.kassenbuch

import kotlinx.coroutines.test.runTest
import org.example.project.admin.features.kassenbuch.presentation.BookManagementViewModel
import org.example.project.core.domain.dto.kassenbuch.CashBookDto
import org.example.project.core.domain.enums.kassenbuch.CashBookCloseInterval
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.PosApiException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun book(
    id: Long,
    name: String,
    isFiscal: Boolean = false,
) = CashBookDto.ResponseDto(
    id = id,
    name = name,
    isFiscal = isFiscal,
    datevAccount = "1010",
    closeInterval = CashBookCloseInterval.MANUAL,
    isActive = true,
    sortOrder = 0,
    currentBalance = "0.0000",
    currency = "EUR",
)

/**
 * The client half of the duplicate-name guard (checklist 21.1, CLAUDE.md rule 8).
 *
 * `CashBookServiceImpl` answers 409 `CASH_BOOK_NAME_ALREADY_TAKEN` for a name another book already
 * carries — trimmed and case-insensitive. The form refuses the same names before a request is made,
 * and a 409 the list could not have predicted (a deactivated book) flags the field instead of
 * raising the generic error dialog.
 */
class CashBookNameGuardTest {
    private fun viewModel(api: FakeCashBookApiClient) =
        BookManagementViewModel(
            cashBookApiClient = api,
            fiscalConfigurationApiClient = FakeFiscalConfigurationApiClient(),
        )

    private fun shop() = FakeCashBookApiClient(books = listOf(book(1, "Ladenkasse", isFiscal = true), book(2, "Tresor")))

    @Test
    fun anExistingNameBlocksTheFormAndNoRequestIsMade() =
        runTest {
            val api = shop()
            val vm = viewModel(api)
            vm.load()
            vm.openCreateForm()
            vm.setBookName("  tresor ")
            vm.setBookDatevAccount("1020")

            val form = assertNotNull(vm.state.form)
            assertTrue(form.isNameTaken, "the name is compared trimmed and case-insensitively")
            assertFalse(form.canSubmit)

            vm.submitBook()
            assertTrue(api.createRequests.isEmpty(), "a taken name must be refused before the request")
        }

    @Test
    fun aBookMayKeepItsOwnName() =
        runTest {
            val vm = viewModel(shop())
            vm.load()
            vm.openEditForm(2)
            vm.setBookName("TRESOR")

            assertFalse(assertNotNull(vm.state.form).isNameTaken)
        }

    @Test
    fun aServerRefusalFlagsTheFieldInsteadOfTheErrorDialog() =
        runTest {
            val api = shop()
            // A deactivated "Nebenkasse" the loaded list does not carry.
            api.createFailure =
                PosApiException(
                    code = PosErrorCode.CASH_BOOK_NAME_ALREADY_TAKEN,
                    httpStatus = 409,
                    message = "A cash book named 'Nebenkasse' already exists (id=3, deactivated)",
                    traceId = null,
                )
            val vm = viewModel(api)
            vm.load()
            vm.openCreateForm()
            vm.setBookName("Nebenkasse")
            vm.setBookDatevAccount("1020")
            vm.submitBook()

            assertEquals(1, api.createRequests.size)
            val form = assertNotNull(vm.state.form, "the form stays open with what the operator typed")
            assertTrue(form.isNameTaken)
            assertFalse(form.isSubmitting)
            assertNull(vm.state.error, "a known refusal is shown on the field, not as a generic error")

            vm.setBookName("Nebenkasse 2")
            assertFalse(assertNotNull(vm.state.form).isNameTaken, "a different name clears the flag")
        }
}
