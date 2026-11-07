package org.example.project.admin.features.kassenbuch

import kotlinx.coroutines.test.runTest
import org.example.project.admin.features.kassenbuch.presentation.BookManagementState
import org.example.project.admin.features.kassenbuch.presentation.BookManagementViewModel
import org.example.project.core.domain.dto.kassenbuch.CashBookDto
import org.example.project.core.domain.enums.kassenbuch.CashBookCloseInterval
import org.example.project.core.network.error.NetworkApiException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
 * The list half of the book-management dialog: it must not claim "no books" before the first load
 * has answered, and a book created in the dialog must be in the dialog's own list afterwards.
 */
class BookManagementListStateTest {
    private fun viewModel(api: FakeCashBookApiClient) =
        BookManagementViewModel(
            cashBookApiClient = api,
            fiscalConfigurationApiClient = FakeFiscalConfigurationApiClient(),
        )

    @Test
    fun theDialogIsNotLoadedBeforeTheFirstLoad() {
        assertFalse(BookManagementState().isLoaded, "an empty list before the first answer is 'loading', not 'no books'")
    }

    @Test
    fun theStateIsLoadedOnceTheBooksHaveArrived() =
        runTest {
            val vm = viewModel(FakeCashBookApiClient(books = listOf(book(1, "Ladenkasse", isFiscal = true))))
            vm.load()
            assertTrue(vm.state.isLoaded)
            assertFalse(vm.state.isLoading)
        }

    @Test
    fun aFailedFirstLoadStaysNotLoaded() =
        runTest {
            val api = FakeCashBookApiClient().also { it.listFailure = NetworkApiException(RuntimeException("down")) }
            val vm = viewModel(api)
            vm.load()
            assertFalse(vm.state.isLoaded, "an error must not be rendered as 'no books'")
        }

    @Test
    fun reopeningTheDialogDropsTheLoadedFlagUntilTheNewAnswer() =
        runTest {
            val api = FakeCashBookApiClient(books = listOf(book(1, "Ladenkasse", isFiscal = true)))
            val vm = viewModel(api)
            vm.load()
            vm.resetForOpen()
            assertFalse(vm.state.isLoaded)
        }

    @Test
    fun aCreatedBookIsListedInTheDialogImmediately() =
        runTest {
            val api = FakeCashBookApiClient(books = listOf(book(1, "Ladenkasse", isFiscal = true)))
            api.createJoinsList = true
            val vm = viewModel(api)
            vm.load()
            vm.openCreateForm()
            vm.setBookName("Tresor")
            vm.setBookDatevAccount("1020")
            vm.submitBook()

            assertEquals(listOf("Ladenkasse", "Tresor"), vm.state.listedBooks.map { it.name })
            assertTrue(vm.state.isLoaded, "the post-save refresh must not blank the list")
        }

    @Test
    fun aDeactivatedBookStaysInTheDialogListSoItCanBeReactivated() =
        runTest {
            val api = FakeCashBookApiClient(books = listOf(book(1, "Ladenkasse", isFiscal = true), book(2, "Tresor")))
            val vm = viewModel(api)
            vm.load()
            vm.openEditForm(2)
            vm.setBookActive(false)
            vm.submitBook()

            assertEquals(true, api.lastIncludeInactive, "the dialog must ask the server for deactivated books too")
            assertEquals(
                listOf("Ladenkasse" to true, "Tresor" to false),
                vm.state.listedBooks.map { it.name to it.isActive },
                "a deactivated book is listed (after the active ones), not dropped",
            )
        }
}
