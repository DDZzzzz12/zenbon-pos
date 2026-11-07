package org.example.project.admin.features.documents.capture

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.admin.features.documents.FakeDocumentManagementApiClient
import org.example.project.admin.features.documents.presentation.capture.InvoiceCaptureViewModel
import org.example.project.admin.features.invoices.FakeInvoiceListApiClient
import org.example.project.admin.features.supplier.FakeSupplierApiClient
import org.example.project.admin.features.supplier.presentation.SupplierForm
import org.example.project.core.domain.dto.supplier.SupplierDto
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.PosApiException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun supplier(
    id: Long,
    name: String,
    vatId: String? = null,
) = SupplierDto.ResponseDto(id = id, name = name, vatId = vatId)

/**
 * Attaching a hand-captured invoice to a creditor the catalogue already holds.
 *
 * A manual capture carries no supplier id: the server resolves the creditor from the invoice's own
 * seller fields and **creates one when nothing matches**. Typing a spelling the catalogue does not
 * hold therefore mints a near-duplicate silently, and that supplier's open total then splits across
 * two records. These tests cover the half of that the client can do — offer what exists, write the
 * catalogue's own spelling when one is picked, and say which outcome is coming.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CaptureSupplierLookupTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(api: FakeSupplierApiClient) = InvoiceCaptureViewModel(FakeDocumentManagementApiClient(), FakeInvoiceListApiClient(), api)

    @Test
    fun `typing a name offers the creditors that match it`() =
        runTest(dispatcher) {
            val api = FakeSupplierApiClient().apply { suppliers = listOf(supplier(1, "Metro AG", "DE123456789")) }
            val vm = viewModel(api)

            vm.updateForm { it.copy(sellerName = "Metro") }
            advanceUntilIdle()

            assertEquals("Metro", api.lastSearch, "the term must reach the server, not filter a page here")
            assertEquals(listOf("Metro AG"), vm.state.supplierSuggestions.map { it.name })
        }

    /**
     * The lookup is debounced, so a name typed letter by letter is one request and not one per key.
     *
     * Asserted by advancing less than the debounce and finding nothing sent — an `advanceUntilIdle`
     * alone would pass whether or not the delay existed.
     */
    @Test
    fun `keystrokes inside the debounce window send nothing`() =
        runTest(dispatcher) {
            val api = FakeSupplierApiClient()
            val vm = viewModel(api)

            vm.updateForm { it.copy(sellerName = "M") }
            advanceTimeBy(100)
            vm.updateForm { it.copy(sellerName = "Me") }
            advanceTimeBy(100)
            vm.updateForm { it.copy(sellerName = "Met") }
            advanceTimeBy(100)

            assertTrue(api.listCalls.isEmpty(), "a request went out mid-word")

            advanceUntilIdle()
            assertEquals(1, api.listCalls.size, "the pause should have produced exactly one lookup")
            assertEquals("Met", api.lastSearch)
        }

    /**
     * Picking a creditor writes its spelling verbatim — that is the whole mechanism.
     *
     * The link is resolved server-side from the name, so "Metro" left as typed would reach a
     * different creditor (or a new one) than the "Metro AG" the operator chose from the list.
     */
    @Test
    fun `picking a creditor writes its own spelling into the field`() =
        runTest(dispatcher) {
            val api = FakeSupplierApiClient().apply { suppliers = listOf(supplier(1, "Metro AG", "DE123456789")) }
            val vm = viewModel(api)
            vm.updateForm { it.copy(sellerName = "metro") }
            advanceUntilIdle()

            vm.pickSupplier(supplier(1, "Metro AG", "DE123456789"))

            assertEquals("Metro AG", vm.state.form.sellerName)
        }

    /** The VAT id is the document's assertion, not ours; picking a creditor must not invent one. */
    @Test
    fun `picking a creditor does not put our VAT id on the invoice`() =
        runTest(dispatcher) {
            val api = FakeSupplierApiClient()
            val vm = viewModel(api)

            vm.pickSupplier(supplier(1, "Metro AG", "DE123456789"))

            assertEquals("Metro AG", vm.state.form.sellerName)
            assertTrue(
                vm.state.form.sellerName
                    .isNotBlank() &&
                    vm.state.invoice == null,
                "nothing beyond the name is written by a pick",
            )
        }

    @Test
    fun `an exact name is reported as a match, whatever its casing`() =
        runTest(dispatcher) {
            val api = FakeSupplierApiClient().apply { suppliers = listOf(supplier(1, "Metro AG")) }
            val vm = viewModel(api)

            vm.updateForm { it.copy(sellerName = "  metro ag  ") }
            advanceUntilIdle()

            assertEquals(1, vm.state.matchedSupplier?.id, "trailing space and casing are not a different creditor")
        }

    @Test
    fun `a name nothing matches reports no creditor`() =
        runTest(dispatcher) {
            val api = FakeSupplierApiClient().apply { suppliers = emptyList() }
            val vm = viewModel(api)

            vm.updateForm { it.copy(sellerName = "Noch Nie Gesehen GmbH") }
            advanceUntilIdle()

            assertNull(vm.state.matchedSupplier)
            assertTrue(vm.state.supplierSearchAnswered, "the screen may only say 'none' once a search has answered")
        }

    /** Before an answer arrives, "no creditor by that name" would be a claim about nothing. */
    @Test
    fun `nothing is claimed until a lookup has answered`() =
        runTest(dispatcher) {
            val vm = viewModel(FakeSupplierApiClient())

            vm.updateForm { it.copy(sellerName = "Metro") }
            advanceTimeBy(100)

            assertTrue(!vm.state.supplierSearchAnswered)
        }

    /** Clearing the field clears the finding with it, rather than leaving a stale verdict on screen. */
    @Test
    fun `emptying the field drops the suggestions and the verdict`() =
        runTest(dispatcher) {
            val api = FakeSupplierApiClient().apply { suppliers = listOf(supplier(1, "Metro AG")) }
            val vm = viewModel(api)
            vm.updateForm { it.copy(sellerName = "Metro AG") }
            advanceUntilIdle()

            vm.updateForm { it.copy(sellerName = "") }
            advanceUntilIdle()

            assertEquals(emptyList(), vm.state.supplierSuggestions)
            assertTrue(!vm.state.supplierSearchAnswered)
        }

    /**
     * Creating a creditor attaches the capture to it in the same move.
     *
     * The created supplier is pushed into the suggestions so the screen stops saying "no creditor by
     * that name" about one that now exists — otherwise the operator would have to retype the name to
     * clear a notice that is already wrong.
     */
    @Test
    fun `creating a creditor names it in the field and reports it as matched`() =
        runTest(dispatcher) {
            val api = FakeSupplierApiClient().apply { createResponse = supplier(9, "Bäckerei Schmidt", "DE333333333") }
            val vm = viewModel(api)
            vm.updateForm { it.copy(sellerName = "Bäckerei Schmid") }
            advanceUntilIdle()

            vm.createSupplier(SupplierForm(name = "Bäckerei Schmidt", vatId = "DE333333333"))
            advanceUntilIdle()

            assertEquals("Bäckerei Schmidt", vm.state.form.sellerName)
            assertEquals(9, vm.state.matchedSupplier?.id, "the new creditor must read as matched at once")
            assertNull(vm.state.supplierCreateError)
        }

    /** What was typed seeds the request, so the operator does not retype the name they just entered. */
    @Test
    fun `the creditor is created with the fields the dialog collected`() =
        runTest(dispatcher) {
            val api = FakeSupplierApiClient().apply { createResponse = supplier(9, "Zeta Logistik") }
            val vm = viewModel(api)

            vm.createSupplier(SupplierForm(name = "Zeta Logistik", vatId = "DE444444444", city = "Köln"))
            advanceUntilIdle()

            val sent = api.creates.single()
            assertEquals("Zeta Logistik", sent.name)
            assertEquals("DE444444444", sent.vatId)
            assertEquals("Köln", sent.city)
        }

    /**
     * A taken VAT id comes back 409 and belongs in the dialog, not behind it.
     *
     * Escalating it would close the dialog to show a toast and discard everything already typed, and
     * the field that has to change is the one the operator can no longer see.
     */
    @Test
    fun `a rejected creation is reported in the dialog and not as a screen error`() =
        runTest(dispatcher) {
            val api =
                FakeSupplierApiClient().apply {
                    failWith = PosApiException(PosErrorCode.SUPPLIER_VAT_ID_TAKEN, 409, "taken", null)
                }
            val vm = viewModel(api)

            vm.createSupplier(SupplierForm(name = "Metro AG", vatId = "DE123456789"))
            advanceUntilIdle()

            assertTrue(vm.state.supplierCreateError != null, "the dialog must say why")
            assertNull(vm.state.error, "it must not be escalated over the dialog")
            assertTrue(!vm.state.isCreatingSupplier)
        }

    /**
     * A failed lookup must not block the capture.
     *
     * The field is free text and always was; losing the catalogue costs the operator a convenience,
     * not the ability to file the invoice, so it is logged and swallowed rather than raised.
     */
    @Test
    fun `a failed lookup leaves the form usable and raises no error`() =
        runTest(dispatcher) {
            val api =
                FakeSupplierApiClient().apply {
                    failWith = PosApiException(PosErrorCode.INTERNAL_ERROR, 500, "boom", null)
                }
            val vm = viewModel(api)

            vm.updateForm { it.copy(sellerName = "Metro") }
            advanceUntilIdle()

            assertEquals("Metro", vm.state.form.sellerName)
            assertNull(vm.state.error, "a creditor lookup is not worth failing a capture over")
            assertTrue(!vm.state.supplierSearchAnswered, "a failed search has found nothing, and knows nothing")
        }
}
