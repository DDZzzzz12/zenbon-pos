package org.example.project.admin.features.kassenbuch

import kotlinx.coroutines.test.runTest
import org.example.project.admin.features.kassenbuch.presentation.BookManagementViewModel
import org.example.project.admin.features.kassenbuch.presentation.DeactivationBlock
import org.example.project.core.domain.dto.kassenbuch.CashBookDto
import org.example.project.core.domain.enums.kassenbuch.CashBookCloseInterval
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val FISCAL_BOOK_ID = 21L
private const val EMPTY_TRESOR_ID = 22L
private const val FULL_TRESOR_ID = 23L

private fun managedBook(
    id: Long,
    name: String,
    isFiscal: Boolean,
    balance: String,
    isActive: Boolean = true,
) = CashBookDto.ResponseDto(
    id = id,
    name = name,
    isFiscal = isFiscal,
    datevAccount = "1600",
    closeInterval = CashBookCloseInterval.MANUAL,
    isActive = isActive,
    sortOrder = 0,
    currentBalance = balance,
    currency = "EUR",
)

/**
 * The client half of both deactivation guards (CLAUDE.md rule 8).
 *
 * `CashBookServiceImpl.requireDeactivatable` refuses two things with a 422: deactivating the fiscal
 * Ladenkasse at all, and deactivating a non-fiscal book that still holds cash. These cases pin that
 * the client never *asks* — a guard that only greys out a switch still leaves every other caller
 * free to produce an error the operator has to interpret.
 *
 * The zero-balance check is asserted through `"0.0000"` on purpose: that is the shape the server's
 * `DECIMAL(19,4)` column produces, and a string comparison against `"0"` would wrongly block it.
 */
class CashBookDeactivationGuardTest {
    private fun viewModel(cashBooks: FakeCashBookApiClient) =
        BookManagementViewModel(
            cashBookApiClient = cashBooks,
            fiscalConfigurationApiClient = FakeFiscalConfigurationApiClient(),
        )

    private fun allBooks() =
        FakeCashBookApiClient(
            books =
                listOf(
                    managedBook(FISCAL_BOOK_ID, "Ladenkasse", isFiscal = true, balance = "0.0000"),
                    managedBook(EMPTY_TRESOR_ID, "Tresor", isFiscal = false, balance = "0.0000"),
                    managedBook(FULL_TRESOR_ID, "Nebenkasse", isFiscal = false, balance = "128.5000"),
                ),
        )

    @Test
    fun theFiscalBookIsNeverDeactivated() =
        runTest {
            val api = allBooks()
            val vm = viewModel(api)
            vm.load()
            vm.openEditForm(FISCAL_BOOK_ID)

            assertEquals(DeactivationBlock.FISCAL, assertNotNull(vm.state.form).deactivationBlock)

            vm.setBookActive(false)
            assertTrue(assertNotNull(vm.state.form).isActive, "the switch must not move for the fiscal book")

            // Saving the form is still legitimate — a rename is — so what must never happen is not
            // "a request" but "a request that deactivates it".
            vm.submitBook()
            assertTrue(
                api.updateRequests.none { (_, request) -> !request.isActive },
                "the fiscal Ladenkasse must never be sent as inactive",
            )
        }

    @Test
    fun aBookWithCashInItIsNeverDeactivated() =
        runTest {
            val api = allBooks()
            val vm = viewModel(api)
            vm.load()
            vm.openEditForm(FULL_TRESOR_ID)

            assertEquals(DeactivationBlock.NON_ZERO_BALANCE, assertNotNull(vm.state.form).deactivationBlock)

            vm.setBookActive(false)
            assertTrue(assertNotNull(vm.state.form).isActive, "a book still holding cash keeps its switch on")

            vm.submitBook()
            assertTrue(
                api.updateRequests.none { (_, request) -> !request.isActive },
                "a non-zero balance must be refused before the request",
            )
        }

    @Test
    fun aDeactivationIsRefusedWhenTheBookTookCashWhileTheFormWasOpen() =
        runTest {
            // The second level of the guard, and the reason it re-reads the loaded books instead of
            // trusting the form: the switch was legitimately flipped while the book was empty, and
            // a deposit landed before the operator saved. Nothing may reach the server.
            val api = allBooks()
            val vm = viewModel(api)
            vm.load()
            vm.openEditForm(EMPTY_TRESOR_ID)
            vm.setBookActive(false)

            api.books = listOf(managedBook(EMPTY_TRESOR_ID, "Tresor", isFiscal = false, balance = "40.0000"))
            vm.load()

            vm.submitBook()

            assertTrue(api.updateRequests.isEmpty(), "zero requests: the book is no longer deactivatable")
        }

    @Test
    fun anEmptyNonFiscalBookIsDeactivatable() =
        runTest {
            val api = allBooks()
            val vm = viewModel(api)
            vm.load()
            vm.openEditForm(EMPTY_TRESOR_ID)

            // "0.0000", not "0" — the guard must compare in Decimal, never textually.
            assertNull(assertNotNull(vm.state.form).deactivationBlock)

            vm.setBookActive(false)
            vm.submitBook()

            val (bookId, request) = api.updateRequests.single()
            assertEquals(EMPTY_TRESOR_ID, bookId)
            assertEquals(false, request.isActive)
            assertNull(vm.state.form, "a saved book closes its form")
        }

    @Test
    fun reactivatingIsNeverBlocked() =
        runTest {
            val api =
                FakeCashBookApiClient(
                    books = listOf(managedBook(EMPTY_TRESOR_ID, "Tresor", isFiscal = false, balance = "0.00", isActive = false)),
                )
            val vm = viewModel(api)
            vm.load()
            vm.openEditForm(EMPTY_TRESOR_ID)

            vm.setBookActive(true)

            assertTrue(assertNotNull(vm.state.form).isActive, "bringing a book back strands nothing")
        }

    @Test
    fun createNeverSendsAFiscalBook() =
        runTest {
            val api = allBooks()
            val vm = viewModel(api)
            vm.load()
            vm.openCreateForm()

            val form = assertNotNull(vm.state.form)
            assertEquals(false, form.isFiscal, "a new book is never fiscal — the request DTO has no such field")

            vm.setBookName("Zweitkasse")
            vm.setBookDatevAccount("1620")
            vm.submitBook()

            assertEquals("Zweitkasse", api.createRequests.single().name)
            assertTrue(api.updateRequests.isEmpty())
        }

    @Test
    fun anIncompleteFormIsNeverSent() =
        runTest {
            val api = allBooks()
            val vm = viewModel(api)
            vm.load()
            vm.openCreateForm()

            vm.setBookName("Zweitkasse")
            vm.setBookDatevAccount("abc")
            vm.submitBook()

            assertTrue(api.createRequests.isEmpty(), "a malformed DATEV account is refused before the request")
        }
}
