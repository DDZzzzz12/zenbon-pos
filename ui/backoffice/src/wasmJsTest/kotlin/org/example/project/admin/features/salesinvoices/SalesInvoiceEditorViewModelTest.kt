package org.example.project.admin.features.salesinvoices

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.debtors.FakeDebtorApiClient
import org.example.project.admin.features.salesinvoices.presentation.SalesInvoiceEditorFields
import org.example.project.admin.features.salesinvoices.presentation.SalesInvoiceEditorViewModel
import org.example.project.core.domain.dto.debtor.DebtorDto
import org.example.project.core.domain.dto.salesinvoice.SalesInvoiceDto
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

private fun invoice(
    id: Long = 1L,
    status: String = "DRAFT",
    buyerName: String = "Musterfirma GmbH",
    serviceDate: String = "2026-08-16T10:00",
    dueDate: String? = null,
    notes: String? = null,
    paymentTermsText: String? = null,
    revenueAccountNo: String? = null,
    buCode: String? = null,
    lines: List<SalesInvoiceDto.LineDto> = emptyList(),
) = SalesInvoiceDto.ResponseDto(
    id = id,
    invoiceNumber = if (status == "DRAFT") null else "RG-2027-00001",
    status = status,
    revenueOrigin = "STANDALONE",
    invoiceTypeCode = "380",
    precedingInvoiceId = null,
    billId = null,
    debtorId = null,
    buyerName = buyerName,
    buyerVatId = "DE123456789",
    buyerStreet = "Hafenstraße 1",
    buyerZip = "20359",
    buyerCity = "Hamburg",
    buyerCountry = "DE",
    buyerEmail = "office@musterfirma.example",
    sellerName = null,
    sellerVatId = null,
    sellerTaxNumber = null,
    issueDate = "2026-08-16T10:00",
    serviceDate = serviceDate,
    dueDate = dueDate,
    currency = "EUR",
    lineNetTotal = null,
    taxTotal = null,
    taxInclusiveTotal = "57.0000",
    prepaidAmount = null,
    amountDue = null,
    openAmount = null,
    notes = notes,
    paymentTermsText = paymentTermsText,
    revenueAccountNo = revenueAccountNo,
    buCode = buCode,
    documentId = null,
    issuedAt = null,
    cancelledAt = null,
    lines = lines,
    vatBreakdown = emptyList(),
)

private fun lineDto(
    itemName: String = "Beratung",
    quantity: String = "2",
    grossAmount: String = "57.00",
    vatRate: String = "19.00",
) = SalesInvoiceDto.LineDto(
    lineNo = 1,
    itemName = itemName,
    itemDescription = null,
    quantity = quantity,
    grossUnitPrice = "28.50",
    netUnitPrice = "47.90",
    netAmount = "95.80",
    vatAmount = "18.20",
    grossAmount = grossAmount,
    vatRate = vatRate,
)

private fun debtor(
    id: Long,
    name: String = "Musterfirma GmbH",
    paymentTermsDays: Int? = 14,
    revenueAccount: String? = "8400",
    buCode: String? = "100",
) = DebtorDto.ResponseDto(
    id = id,
    name = name,
    street = "Hafenstraße 1",
    zip = "20359",
    city = "Hamburg",
    country = "DE",
    email = "office@musterfirma.example",
    vatId = "DE123456789",
    paymentTermsDays = paymentTermsDays,
    defaultRevenueAccountNo = revenueAccount,
    defaultBuCode = buCode,
)

@OptIn(ExperimentalCoroutinesApi::class)
class SalesInvoiceEditorViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun editorViewModel(
        api: FakeSalesInvoiceApiClient,
        debtors: FakeDebtorApiClient = FakeDebtorApiClient(),
    ) = SalesInvoiceEditorViewModel(api, debtors)

    @Test
    fun `a null id fetches the active debtors and starts an empty form`() =
        runTest(dispatcher) {
            val debtors = FakeDebtorApiClient()
            debtors.debtors = listOf(debtor(1), debtor(2, name = "Catering Nord"))
            val vm = editorViewModel(FakeSalesInvoiceApiClient(), debtors)

            vm.load(null)
            advanceUntilIdle()

            assertEquals(true, debtors.lastActiveOnly, "the picker must hide deactivated debtors")
            assertEquals(2, vm.state.debtors.size)
            assertEquals("", vm.state.buyerName)
            assertEquals(1, vm.state.lines.size)
            assertFalse(vm.state.isLoading)
            assertNull(vm.state.error)
        }

    @Test
    fun `loading an existing invoice pre-fills the form from the wire`() =
        runTest(dispatcher) {
            val api = FakeSalesInvoiceApiClient()
            api.invoices +=
                invoice(
                    id = 7,
                    dueDate = "2026-08-30T00:00",
                    notes = "Vorauszahlung vereinbart",
                    lines = listOf(lineDto()),
                )
            val vm = editorViewModel(api)

            vm.load(7)
            advanceUntilIdle()

            assertEquals(7L, vm.state.invoiceId)
            assertEquals("DRAFT", vm.state.status)
            assertEquals("Musterfirma GmbH", vm.state.buyerName)
            assertEquals("DE123456789", vm.state.buyerVatId)
            assertEquals("Hafenstraße 1", vm.state.buyerStreet)
            assertEquals("20359", vm.state.buyerZip)
            assertEquals("Hamburg", vm.state.buyerCity)
            assertEquals("DE", vm.state.buyerCountry)
            assertEquals("office@musterfirma.example", vm.state.buyerEmail)
            assertEquals("2026-08-16T10:00", vm.state.serviceDate)
            assertEquals("2026-08-30T00:00", vm.state.dueDate)
            assertEquals("Vorauszahlung vereinbart", vm.state.notes)
            assertEquals(1, vm.state.lines.size)
            assertEquals("Beratung", vm.state.lines[0].itemName)
            assertEquals("2", vm.state.lines[0].quantity)
            assertEquals("19.00", vm.state.lines[0].vatRate)
            assertEquals("28.50", vm.state.lines[0].grossUnitPrice, "the gross amount is re-derived per unit")
            assertFalse(vm.state.isLoading)
        }

    @Test
    fun `a failed load surfaces the error and stops loading`() =
        runTest(dispatcher) {
            val api = FakeSalesInvoiceApiClient()
            api.getThrows = PosApiException(PosErrorCode.INTERNAL_ERROR, 500, "boom", null)
            val vm = editorViewModel(api)

            vm.load(7)
            advanceUntilIdle()

            assertNotNull(vm.state.error)
            assertFalse(vm.state.isLoading)
        }

    @Test
    fun `picking a debtor pre-fills the buyer block and the payment and booking defaults`() =
        runTest(dispatcher) {
            val debtors = FakeDebtorApiClient()
            debtors.debtors = listOf(debtor(1, paymentTermsDays = 14, revenueAccount = "8400", buCode = "100"))
            val vm = editorViewModel(FakeSalesInvoiceApiClient(), debtors)

            vm.load(null)
            advanceUntilIdle()
            vm.selectDebtor(1)

            assertEquals("Musterfirma GmbH", vm.state.buyerName)
            assertEquals("DE123456789", vm.state.buyerVatId)
            assertEquals("Hafenstraße 1", vm.state.buyerStreet)
            assertEquals("20359", vm.state.buyerZip)
            assertEquals("Hamburg", vm.state.buyerCity)
            assertEquals("DE", vm.state.buyerCountry)
            assertEquals("office@musterfirma.example", vm.state.buyerEmail)
            assertEquals("8400", vm.state.revenueAccountNo)
            assertEquals("100", vm.state.buCode)
            assertTrue(vm.state.dueDate.endsWith("T00:00"), "the due date is a local date-time")
            assertTrue(vm.state.dueDate.startsWith("20"), "the due date is service date + payment terms days")
        }

    @Test
    fun `lines can be added and removed, never below one`() =
        runTest(dispatcher) {
            val vm = editorViewModel(FakeSalesInvoiceApiClient())

            vm.load(null)
            advanceUntilIdle()
            assertEquals(1, vm.state.lines.size)

            vm.addLine()
            vm.addLine()
            assertEquals(3, vm.state.lines.size)

            vm.updateLine(0) { it.copy(itemName = "Schulung") }
            assertEquals("Schulung", vm.state.lines[0].itemName)

            vm.removeLine(1)
            assertEquals(2, vm.state.lines.size)

            vm.removeLine(0)
            vm.removeLine(0)
            assertEquals(1, vm.state.lines.size, "an invoice needs at least one line")
        }

    @Test
    fun `an issued invoice is read-only and every mutator is a no-op`() =
        runTest(dispatcher) {
            val api = FakeSalesInvoiceApiClient()
            api.invoices += invoice(id = 9, status = "ISSUED", lines = listOf(lineDto(), lineDto()))
            val vm = editorViewModel(api)

            vm.load(9)
            advanceUntilIdle()
            assertTrue(vm.state.readOnly)

            vm.addLine()
            vm.removeLine(0)
            vm.updateLine(0) { it.copy(itemName = "Manipuliert") }
            vm.onBuyerNameChange("Manipuliert")
            vm.onNotesChange("Manipuliert")

            assertEquals(2, vm.state.lines.size)
            assertEquals("Beratung", vm.state.lines[0].itemName)
            assertEquals("Musterfirma GmbH", vm.state.buyerName)
            assertFalse(vm.state.hasChanges)
        }

    @Test
    fun `a draft and a new invoice are not read-only`() =
        runTest(dispatcher) {
            val api = FakeSalesInvoiceApiClient()
            api.invoices += invoice(id = 3)
            val vm = editorViewModel(api)

            vm.load(3)
            advanceUntilIdle()
            assertFalse(vm.state.readOnly)

            vm.load(null)
            advanceUntilIdle()
            assertFalse(vm.state.readOnly)
        }

    @Test
    fun `saving a fresh draft calls createStandalone with the typed gross lines`() =
        runTest(dispatcher) {
            val api = FakeSalesInvoiceApiClient()
            val vm = editorViewModel(api)

            vm.load(null)
            advanceUntilIdle()
            vm.onBuyerNameChange("Musterfirma GmbH")
            vm.onServiceDateChange("2026-08-16T10:00")
            vm.onRevenueAccountChange("8400")
            vm.onBuCodeChange("100")
            vm.onPaymentTermsChange("Zahlbar innerhalb von 14 Tagen")
            vm.onDueDateChange("2026-08-30T00:00")
            vm.updateLine(0) {
                it.copy(itemName = "Beratung", quantity = "2", grossUnitPrice = "28,50", vatRate = "19.00")
            }
            vm.save()
            advanceUntilIdle()

            val request = api.createdStandalone
            assertNotNull(request, "a new invoice is created, not updated")
            assertNull(api.updatedStandalone)
            assertEquals("Musterfirma GmbH", request.buyerName)
            assertEquals("2026-08-16T10:00", request.serviceDate)
            assertEquals("2026-08-30T00:00", request.dueDate)
            assertEquals("Zahlbar innerhalb von 14 Tagen", request.paymentTermsText)
            assertEquals("8400", request.revenueAccountNo)
            assertEquals("100", request.buCode)
            assertEquals(1, request.lines.size)
            assertEquals("Beratung", request.lines[0].itemName)
            assertEquals("2", request.lines[0].quantity)
            assertEquals("28.50", request.lines[0].grossUnitPrice, "the German comma input is canonicalized, not doubled")
            assertEquals("19.00", request.lines[0].vatRate)
            assertEquals(100L, vm.state.invoiceId, "the created id is adopted so a second save updates")
            assertFalse(vm.state.isSaving)
        }

    @Test
    fun `saving an existing draft calls updateStandalone on that id`() =
        runTest(dispatcher) {
            val api = FakeSalesInvoiceApiClient()
            api.invoices += invoice(id = 7, lines = listOf(lineDto()))
            val vm = editorViewModel(api)

            vm.load(7)
            advanceUntilIdle()
            vm.onNotesChange("Neue Notiz")
            vm.save()
            advanceUntilIdle()

            val (id, request) = api.updatedStandalone ?: error("an existing draft is updated, not created")
            assertNull(api.createdStandalone)
            assertEquals(7L, id)
            assertEquals("Neue Notiz", request.notes)
            assertFalse(vm.state.isSaving)
        }

    @Test
    fun `a failed save surfaces the error and leaves the form editable`() =
        runTest(dispatcher) {
            val api = FakeSalesInvoiceApiClient()
            api.saveThrows = PosApiException(PosErrorCode.INTERNAL_ERROR, 500, "boom", null)
            val vm = editorViewModel(api)

            vm.load(null)
            advanceUntilIdle()
            vm.onBuyerNameChange("Musterfirma GmbH")
            vm.onServiceDateChange("2026-08-16T10:00")
            vm.updateLine(0) { it.copy(itemName = "Beratung", quantity = "1", grossUnitPrice = "10.00") }
            vm.save()
            advanceUntilIdle()

            assertNotNull(vm.state.error)
            assertFalse(vm.state.isSaving)
            assertTrue(vm.state.hasChanges, "the operator's edit must survive a refused save")
        }

    @Test
    fun `the empty create form can be saved, and the press marks every missing field`() =
        runTest(dispatcher) {
            val api = FakeSalesInvoiceApiClient()
            val vm = editorViewModel(api)
            vm.load(null)
            advanceUntilIdle()

            assertTrue(vm.state.canSave, "UI-E2-06: Save is not greyed out on an empty create form")
            vm.save()
            advanceUntilIdle()

            assertNull(api.createdStandalone)
            assertEquals(1, vm.state.validationAttempt)
            assertTrue(SalesInvoiceEditorFields.BUYER_NAME in vm.state.fieldErrors)
            assertTrue(SalesInvoiceEditorFields.SERVICE_DATE in vm.state.fieldErrors)
            assertTrue(SalesInvoiceEditorFields.itemName(0) in vm.state.fieldErrors)

            vm.onBuyerNameChange("Musterfirma GmbH")
            assertNull(vm.state.buyerNameError, "fixing a field clears its error")
            assertNotNull(vm.state.serviceDateError, "the other flagged fields stay flagged")

            vm.onDueDateChange("31.02.2026T00:00")
            vm.save()
            advanceUntilIdle()
            assertNotNull(vm.state.dueDateError, "text that is no date is refused on the due date field")
        }

    @Test
    fun `the validation mirrors the server and reports the first failure on its field`() =
        runTest(dispatcher) {
            val api = FakeSalesInvoiceApiClient()
            val vm = editorViewModel(api)
            vm.load(null)
            advanceUntilIdle()

            vm.onBuyerNameChange("   ")
            vm.save()
            advanceUntilIdle()
            assertEquals(AdminStringKeys.SalesInvoiceEditorBuyerNameRequired, vm.state.buyerNameError)
            assertNull(api.createdStandalone, "a blank buyer never reaches the server")

            vm.onBuyerNameChange("Musterfirma GmbH")
            vm.save()
            advanceUntilIdle()
            assertEquals(AdminStringKeys.SalesInvoiceEditorServiceDateRequired, vm.state.serviceDateError)

            vm.onServiceDateChange("2026-08-16T10:00")
            vm.save()
            advanceUntilIdle()
            assertEquals(AdminStringKeys.SalesInvoiceEditorLineItemNameRequired, vm.state.lineError)

            vm.updateLine(0) { it.copy(itemName = "Beratung", quantity = "0") }
            vm.save()
            advanceUntilIdle()
            assertEquals(AdminStringKeys.SalesInvoiceEditorLineQuantityInvalid, vm.state.lineError)

            vm.updateLine(0) { it.copy(quantity = "1", grossUnitPrice = "-5,00") }
            vm.save()
            advanceUntilIdle()
            assertEquals(AdminStringKeys.SalesInvoiceEditorLinePriceInvalid, vm.state.lineError)

            vm.updateLine(0) { it.copy(grossUnitPrice = "10.00") }
            vm.save()
            advanceUntilIdle()
            assertNotNull(api.createdStandalone, "a valid form is dispatched")
            assertNull(vm.state.lineError)
        }

    @Test
    fun `issue only ever fires for a loaded draft and reports completion`() =
        runTest(dispatcher) {
            val api = FakeSalesInvoiceApiClient()
            api.invoices += invoice(id = 7)
            val vm = editorViewModel(api)

            vm.load(7)
            advanceUntilIdle()
            vm.issue()
            advanceUntilIdle()

            assertEquals(listOf(7L), api.issuedIds)
            assertTrue(vm.state.isIssued, "the screen navigates back on this flag")

            val issuedApi = FakeSalesInvoiceApiClient()
            issuedApi.invoices += invoice(id = 8, status = "ISSUED")
            val issuedVm = editorViewModel(issuedApi)
            issuedVm.load(8)
            advanceUntilIdle()
            issuedVm.issue()
            advanceUntilIdle()

            assertTrue(issuedApi.issuedIds.isEmpty(), "the client half of the immutability guard stops it locally")
        }

    @Test
    fun `a draft whose debtor was deactivated cannot be issued from the editor`() =
        runTest(dispatcher) {
            val api = FakeSalesInvoiceApiClient()
            api.invoices += invoice(id = 7).copy(debtorId = 5L)
            val debtors = FakeDebtorApiClient()
            // The editor loads active debtors only, so debtor 5 missing from the list means it is inactive.
            debtors.debtors = listOf(debtor(1))
            val vm = editorViewModel(api, debtors)

            vm.load(7)
            advanceUntilIdle()

            assertTrue(vm.state.debtorInactive)
            assertFalse(vm.state.canIssue, "the issue action is withheld; the server answers 409 DEBTOR_INACTIVE anyway")
            vm.issue()
            advanceUntilIdle()
            assertTrue(api.issuedIds.isEmpty())

            val activeDebtors = FakeDebtorApiClient()
            activeDebtors.debtors = listOf(debtor(5))
            val activeVm = editorViewModel(api, activeDebtors)
            activeVm.load(7)
            advanceUntilIdle()
            assertFalse(activeVm.state.debtorInactive)
            assertTrue(activeVm.state.canIssue)
        }

    /**
     * E2E 2026-09-27: a draft for a deactivated debtor could still be edited (PUT 200). The server now
     * answers 409 DEBTOR_INACTIVE; this is the client half — Speichern is withheld and never sent.
     */
    @Test
    fun `a draft whose debtor was deactivated cannot be saved from the editor`() =
        runTest(dispatcher) {
            val api = FakeSalesInvoiceApiClient()
            api.invoices += invoice(id = 7).copy(debtorId = 5L)
            val debtors = FakeDebtorApiClient()
            debtors.debtors = listOf(debtor(1))
            val vm = editorViewModel(api, debtors)

            vm.load(7)
            advanceUntilIdle()
            vm.onNotesChange("noch geändert")

            assertTrue(vm.state.hasChanges)
            assertFalse(vm.state.canSave, "saving is withheld; the server answers 409 DEBTOR_INACTIVE anyway")
            vm.save()
            advanceUntilIdle()
            assertNull(api.updatedStandalone, "nothing reaches the server")

            val activeDebtors = FakeDebtorApiClient()
            activeDebtors.debtors = listOf(debtor(5))
            val activeVm = editorViewModel(api, activeDebtors)
            activeVm.load(7)
            advanceUntilIdle()
            activeVm.onNotesChange("geändert")
            assertTrue(activeVm.state.canSave)
        }

    @Test
    fun `the running total is computed in Decimal for feedback only`() =
        runTest(dispatcher) {
            val vm = editorViewModel(FakeSalesInvoiceApiClient())
            vm.load(null)
            advanceUntilIdle()

            vm.updateLine(0) {
                it.copy(itemName = "A", quantity = "2", grossUnitPrice = "19,90")
            }
            vm.addLine()
            vm.updateLine(1) {
                it.copy(itemName = "B", quantity = "1", grossUnitPrice = "7.10", vatRate = "7.00")
            }

            assertEquals("46.90", vm.state.subtotal.toPlainString(), "2 × 19.90 + 1 × 7.10")

            vm.updateLine(1) { it.copy(grossUnitPrice = "garbage") }
            assertEquals("39.80", vm.state.subtotal.toPlainString(), "an unparseable line is skipped, never a Double")
        }

    @Test
    fun `a selected debtor stays editable and a re-pick overwrites`() =
        runTest(dispatcher) {
            val debtors = FakeDebtorApiClient()
            debtors.debtors = listOf(debtor(1), debtor(2, name = "Catering Nord"))
            val vm = editorViewModel(FakeSalesInvoiceApiClient(), debtors)
            vm.load(null)
            advanceUntilIdle()

            vm.selectDebtor(1)
            vm.onBuyerNameChange("Umbenannt GmbH")
            assertEquals("Umbenannt GmbH", vm.state.buyerName)

            vm.selectDebtor(2)
            assertEquals("Catering Nord", vm.state.buyerName)
        }

    @Test
    fun `save and issue writes the draft and then issues it`() =
        runTest(dispatcher) {
            val api = FakeSalesInvoiceApiClient()
            api.invoices += invoice(id = 7, lines = listOf(lineDto()))
            val vm = editorViewModel(api)
            vm.load(7)
            advanceUntilIdle()

            vm.saveAndIssue()
            advanceUntilIdle()

            assertEquals(7L, api.updatedStandalone?.first, "the draft is written first")
            assertEquals(listOf(7L), api.issuedIds, "and then issued")
            assertTrue(vm.state.isIssued)
            assertFalse(vm.state.isSaving)
        }

    @Test
    fun `save and issue sends nothing for an invalid form`() =
        runTest(dispatcher) {
            val api = FakeSalesInvoiceApiClient()
            val vm = editorViewModel(api)
            vm.load(null)
            advanceUntilIdle()

            vm.saveAndIssue()
            advanceUntilIdle()

            assertNull(api.createdStandalone, "an invalid form must not reach the server")
            assertTrue(api.issuedIds.isEmpty())
            assertTrue(vm.state.fieldErrors.isNotEmpty())
        }

    @Test
    fun `a failed issue after a successful save keeps the draft and raises the error`() =
        runTest(dispatcher) {
            val api = FakeSalesInvoiceApiClient()
            api.invoices += invoice(id = 7, lines = listOf(lineDto()))
            api.issueThrows = PosApiException(PosErrorCode.INTERNAL_ERROR, 500, "boom", null)
            val vm = editorViewModel(api)
            vm.load(7)
            advanceUntilIdle()

            vm.saveAndIssue()
            advanceUntilIdle()

            assertNotNull(vm.state.error)
            assertFalse(vm.state.isIssued)
            assertTrue(vm.state.saved, "the draft was written before the issue failed")
        }

    @Test
    fun `validating for issue marks the fields and reports not ready on an invalid form`() =
        runTest(dispatcher) {
            val vm = editorViewModel(FakeSalesInvoiceApiClient())
            vm.load(null)
            advanceUntilIdle()
            val before = vm.state.validationAttempt

            assertFalse(vm.validateForIssue())

            assertTrue(vm.state.fieldErrors.isNotEmpty(), "the field errors show before any confirm")
            assertEquals(before + 1, vm.state.validationAttempt, "the screen scrolls to the first field")
        }

    @Test
    fun `validating for issue passes a valid draft without sending anything`() =
        runTest(dispatcher) {
            val api = FakeSalesInvoiceApiClient()
            api.invoices += invoice(id = 7, lines = listOf(lineDto()))
            val vm = editorViewModel(api)
            vm.load(7)
            advanceUntilIdle()

            assertTrue(vm.validateForIssue())

            assertTrue(api.issuedIds.isEmpty())
            assertNull(api.updatedStandalone)
        }
}
