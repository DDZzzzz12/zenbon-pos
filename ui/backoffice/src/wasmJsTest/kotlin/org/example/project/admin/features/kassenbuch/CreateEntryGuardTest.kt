package org.example.project.admin.features.kassenbuch

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus
import org.example.project.admin.features.dashboard.FakeKassenbuchApiClient
import org.example.project.admin.features.dashboard.FakeZBonApiClient
import org.example.project.admin.features.kassenbuch.presentation.EntryFormFields
import org.example.project.admin.features.kassenbuch.presentation.EntryLineInput
import org.example.project.admin.features.kassenbuch.presentation.KassenbuchViewModel
import org.example.project.core.domain.dto.kassenbuch.CashBookDto
import org.example.project.core.domain.enums.kassenbuch.CashBookCloseInterval
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.utils.KassenbuchBelegDateViolation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val FISCAL_BOOK_ID = 11L
private const val TRESOR_ID = 12L
private const val DOCUMENT_ID = 501L

/**
 * [balance] defaults to a Tresor that holds enough for every expense below: since F59 the form blocks
 * a withdrawal above the book's balance (the Kassenminus guard), which is not what most of these
 * cases are about. The cases that ARE about it pass their own balance.
 */
private fun entryBook(
    id: Long,
    name: String,
    isFiscal: Boolean,
    balance: String = "1000.00",
) = CashBookDto.ResponseDto(
    id = id,
    name = name,
    isFiscal = isFiscal,
    datevAccount = "1600",
    closeInterval = CashBookCloseInterval.MANUAL,
    isActive = true,
    sortOrder = 0,
    currentBalance = balance,
    currency = "EUR",
)

/**
 * The client half of two guards on manual entry creation (CLAUDE.md rule 8).
 *
 * **Fiscal:** a Kassenbuch entry in the Ladenkasse is signed by the TSE, and
 * `KassenbuchServiceImpl` resolves a signing terminal through `requireSigningTerminal` whenever the
 * target book is fiscal. The web admin has no terminal, so those entries are made at the till. The
 * button is not drawn there, and these cases pin that the ViewModel never *asks* either.
 *
 * **Document:** the server throws "documentId is required" on a null one. The form refuses first, so
 * a stray call cannot turn a known-in-advance rule into a 422 the operator has to decode.
 *
 * Both are asserted as **zero requests**, not as an error state: a guard that hides a control while
 * still calling the server is the half-implemented kind the rule exists to forbid.
 */
class CreateEntryGuardTest {
    private fun viewModel(
        api: FakeKassenbuchApiClient,
        config: FakeFiscalConfigurationApiClient = FakeFiscalConfigurationApiClient(),
        tresorBalance: String = "1000.00",
    ) = KassenbuchViewModel(
        kassenbuchApiClient = api,
        cashBookApiClient =
            FakeCashBookApiClient(
                books =
                    listOf(
                        entryBook(FISCAL_BOOK_ID, "Ladenkasse", isFiscal = true),
                        entryBook(TRESOR_ID, "Tresor", isFiscal = false, balance = tresorBalance),
                    ),
            ),
        zBonApiClient = FakeZBonApiClient(latestEndTime = null),
        fiscalConfigurationApiClient = config,
    )

    private fun api() = FakeKassenbuchApiClient(entries = { emptyList() }, balance = { "0.00" })

    /** A Tresor form, filled to the point where only the Beleg is missing. */
    private fun openFilledForm(vm: KassenbuchViewModel) {
        vm.loadBooks()
        vm.selectBook(TRESOR_ID)
        vm.openEntryForm()
        vm.setEntryType(KassenbuchEntryType.CASH_EXPENSE)
        vm.setEntryBelegtext("Büromaterial")
        vm.setEntryLine(0, EntryLineInput(amount = "23,80", vatRate = "19"))
    }

    @Test
    fun theFiscalBookNeverOpensTheEntryForm() =
        runTest {
            val api = api()
            val vm = viewModel(api)
            vm.loadBooks()
            vm.selectBook(FISCAL_BOOK_ID)

            vm.openEntryForm()

            assertNull(vm.state.entryForm, "a fiscal entry needs a signing terminal the web admin has not got")
            assertEquals(0, api.createEntryCalls)
        }

    @Test
    fun theAllBooksTabHasNoBookToBookInto() =
        runTest {
            val api = api()
            val vm = viewModel(api)
            vm.loadBooks()

            vm.openEntryForm()

            assertNull(vm.state.entryForm)
            assertEquals(0, api.createEntryCalls)
        }

    @Test
    fun aNonFiscalBookOpensAFormPointedAtThatBook() =
        runTest {
            val vm = viewModel(api())
            vm.loadBooks()
            vm.selectBook(TRESOR_ID)

            vm.openEntryForm()

            val form = assertNotNull(vm.state.entryForm)
            assertEquals(TRESOR_ID, form.cashBookId)
            assertEquals("Tresor", form.cashBookName)
            assertNull(form.documentId, "a fresh form carries no Beleg")
            assertFalse(form.canSubmit)
        }

    @Test
    fun anEntryWithoutADocumentIsNeverSent() =
        runTest {
            val api = api()
            val vm = viewModel(api)
            openFilledForm(vm)

            assertFalse(assertNotNull(vm.state.entryForm).canSubmit, "confirm stays disabled without a Beleg")
            vm.submitEntry()

            assertEquals(0, api.createEntryCalls, "the server requires a documentId; the form refuses first")
            assertNotNull(vm.state.entryForm, "the form stays open so the operator can attach one")
        }

    @Test
    fun attachingTheDocumentIsWhatMakesTheFormSubmittable() =
        runTest {
            val api = api()
            val vm = viewModel(api)
            openFilledForm(vm)

            vm.setEntryDocument(DOCUMENT_ID)

            assertTrue(assertNotNull(vm.state.entryForm).canSubmit)
            vm.submitEntry()
            assertEquals(1, api.createEntryCalls)
            assertEquals(DOCUMENT_ID, assertNotNull(api.lastCreateRequest).documentId)
            assertNull(vm.state.entryForm, "a booked entry closes its form")
        }

    @Test
    fun theEntryCarriesTheSelectedCashBookAndExactAmounts() =
        runTest {
            val api = api()
            val vm = viewModel(api)
            openFilledForm(vm)
            vm.addEntryLine()
            vm.setEntryLine(1, EntryLineInput(amount = "16.90", vatRate = "7"))
            vm.setEntryDocument(DOCUMENT_ID)

            vm.submitEntry()

            val request = assertNotNull(api.lastCreateRequest)
            assertEquals(TRESOR_ID, request.cashBookId, "null cashBookId would book this into the fiscal Ladenkasse")
            assertEquals(listOf("23.80", "16.90"), request.lines.map { it.amount })
            assertEquals(listOf("19", "7"), request.lines.map { it.vatRate })
            // 23.80 + 16.90 summed in Decimal, scale preserved. Through a Double the same sum is
            // 40.699999999999996.
            assertEquals("40.70", request.calculateTotalAmount())
            assertTrue(request.validate().isEmpty(), "the client mirrors the server's own validation matrix")
        }

    @Test
    fun theBelegDatumIsSeededAndSentAsGermanDate() =
        runTest {
            val api = api()
            val vm = viewModel(api)
            openFilledForm(vm)
            vm.setEntryDocument(DOCUMENT_ID)

            val seeded = assertNotNull(vm.state.entryForm).belegDatum
            assertTrue(
                Regex("""\d{2}\.\d{2}\.\d{4}""").matches(seeded),
                "belegDatum is German DD.MM.YYYY, not the ISO date every other field on this screen uses — was '$seeded'",
            )

            vm.submitEntry()
            assertEquals(seeded, assertNotNull(api.lastCreateRequest).belegDatum)
        }

    @Test
    fun confirmOnAnEmptyFormMarksEveryMissingFieldAndRevealsTheFirst() =
        runTest {
            val api = api()
            val vm = viewModel(api)
            vm.loadBooks()
            vm.selectBook(TRESOR_ID)
            vm.openEntryForm()

            vm.submitEntry()

            assertEquals(0, api.createEntryCalls)
            val form = assertNotNull(vm.state.entryForm)
            assertEquals(1, form.validationAttempt, "a refused press is what tells the dialog to show and reveal the errors")
            val errors = form.fieldErrors()
            assertEquals(
                setOf(EntryFormFields.BELEGTEXT, EntryFormFields.amount(0), EntryFormFields.vatRate(0)),
                errors.keys,
                "the seeded Beleg date is valid; text, amount and VAT rate are missing",
            )
            assertEquals(EntryFormFields.BELEGTEXT, form.fieldOrder().first { it in errors }, "the first field at fault is focused")
            assertFalse(form.isDirtyFrom(form.copy(validationAttempt = 0)), "the attempt counter never makes the form dirty")
        }

    @Test
    fun aMalformedBelegDatumIsRefusedBeforeTheRequest() =
        runTest {
            val api = api()
            val vm = viewModel(api)
            openFilledForm(vm)
            vm.setEntryDocument(DOCUMENT_ID)

            vm.setEntryBelegDatum("2026-02-01")
            assertTrue(assertNotNull(vm.state.entryForm).hasUnusableBelegDatum)
            vm.submitEntry()
            vm.setEntryBelegDatum("31.02.2026")
            assertTrue(assertNotNull(vm.state.entryForm).hasUnusableBelegDatum, "the right shape is not enough: there is no 31 February")
            vm.submitEntry()

            assertEquals(0, api.createEntryCalls, "an ISO date here would store a Belegdatum no DATEV export can read")
        }

    @Test
    fun aNegativeBelegTotalIsRefused() =
        runTest {
            val api = api()
            val vm = viewModel(api)
            openFilledForm(vm)
            vm.setEntryDocument(DOCUMENT_ID)

            // The entry type carries the direction, so a negative total means the wrong type was
            // picked — the server rejects it, and so does the form.
            vm.setEntryLine(0, EntryLineInput(amount = "-23.80", vatRate = "19"))
            vm.submitEntry()

            assertEquals(0, api.createEntryCalls)
        }

    /**
     * A blank `USt-Satz` blocks confirm, and the form says so.
     *
     * Without this the operator fills every field, uploads the Beleg, watches the Beleg warning
     * disappear — and the button still will not enable, with nothing on screen naming the reason. The
     * VAT field is narrow, sits beside the amount, and has no error state of its own until something
     * is typed into it, so there is no other clue at all.
     */
    @Test
    fun aMissingVatRateIsNamedRatherThanLeavingTheButtonSilentlyDead() =
        runTest {
            val vm = viewModel(api())
            openFilledForm(vm)
            vm.setEntryLine(0, EntryLineInput(amount = "23,80", vatRate = ""))
            vm.setEntryDocument(DOCUMENT_ID)

            val form = assertNotNull(vm.state.entryForm)
            assertFalse(form.canSubmit, "the server's matrix demands a VAT rate on every line")
            assertTrue(
                form.hasMissingVatRate,
                "confirm is blocked only by the VAT rate, so the form must say that instead of staying silent",
            )
        }

    /** One cause at a time: while the Beleg is still missing, the VAT line stays quiet. */
    @Test
    fun theVatWarningWaitsUntilItIsTheOnlyThingLeft() =
        runTest {
            val vm = viewModel(api())
            openFilledForm(vm)
            vm.setEntryLine(0, EntryLineInput(amount = "23,80", vatRate = ""))

            assertFalse(
                assertNotNull(vm.state.entryForm).hasMissingVatRate,
                "the Beleg is missing too; two red lines at once say less than one",
            )
        }

    /** A filled VAT rate silences the warning — the property tracks the rule, not the form's age. */
    @Test
    fun typingTheVatRateClearsTheWarning() =
        runTest {
            val vm = viewModel(api())
            openFilledForm(vm)
            vm.setEntryLine(0, EntryLineInput(amount = "23,80", vatRate = ""))
            vm.setEntryDocument(DOCUMENT_ID)

            vm.setEntryLine(0, EntryLineInput(amount = "23,80", vatRate = "19"))

            val form = assertNotNull(vm.state.entryForm)
            assertFalse(form.hasMissingVatRate)
            assertTrue(form.canSubmit)
        }

    @Test
    fun theLastLineCannotBeRemoved() =
        runTest {
            val vm = viewModel(api())
            openFilledForm(vm)

            vm.removeEntryLine(0)

            assertEquals(1, assertNotNull(vm.state.entryForm).lines.size, "a Beleg with no lines is not a booking")
        }

    @Test
    fun switchingTabsUnderAnOpenFormDoesNotMoveTheBooking() =
        runTest {
            val api = api()
            val vm = viewModel(api)
            openFilledForm(vm)
            vm.setEntryDocument(DOCUMENT_ID)

            // The tab moves to the Ladenkasse under the open form. submitEntry re-checks the book
            // behind the form rather than trusting that it was non-fiscal when the form opened.
            vm.selectBook(FISCAL_BOOK_ID)
            vm.submitEntry()

            assertEquals(1, api.createEntryCalls, "the form is still pointed at the Tresor it was opened for")
            assertEquals(TRESOR_ID, assertNotNull(api.lastCreateRequest).cashBookId)
        }

    // ---- Beleg date: the shop's age limit, the same rule the server applies ----

    private fun germanDate(d: LocalDate) = "${d.day.toString().padStart(2, '0')}.${d.month.number.toString().padStart(2, '0')}.${d.year}"

    @Test
    fun aBelegDatumOlderThanTheShopLimitIsRefusedBeforeTheRequest() =
        runTest {
            val api = api()
            val vm = viewModel(api, FakeFiscalConfigurationApiClient(belegAgeDays = 10))
            openFilledForm(vm)
            vm.setEntryDocument(DOCUMENT_ID)
            val today = assertNotNull(vm.state.entryForm).today

            vm.setEntryBelegDatum(germanDate(today.minus(DatePeriod(days = 11))))
            assertEquals(KassenbuchBelegDateViolation.TOO_OLD, assertNotNull(vm.state.entryForm).belegDatumViolation)
            vm.submitEntry()

            assertEquals(0, api.createEntryCalls, "the loaded limit must bound the form exactly like the server")
        }

    @Test
    fun aReceiptFromAClosedPeriodInsideTheLimitIsNotRefused() =
        runTest {
            val vm = viewModel(api())
            openFilledForm(vm)
            vm.setEntryDocument(DOCUMENT_ID)
            val today = assertNotNull(vm.state.entryForm).today

            // A receipt from last week: its period may well be counted already. It still books,
            // into the open period, and keeps its date.
            vm.setEntryBelegDatum(germanDate(today.minus(DatePeriod(days = 7))))

            val form = assertNotNull(vm.state.entryForm)
            assertNull(form.belegDatumViolation)
            assertTrue(form.canSubmit)
        }

    @Test
    fun aFutureBelegDatumIsRefused() =
        runTest {
            val vm = viewModel(api())
            openFilledForm(vm)
            vm.setEntryDocument(DOCUMENT_ID)
            val today = assertNotNull(vm.state.entryForm).today

            vm.setEntryBelegDatum(germanDate(today.plus(DatePeriod(days = 1))))

            val form = assertNotNull(vm.state.entryForm)
            assertEquals(KassenbuchBelegDateViolation.FUTURE, form.belegDatumViolation)
            assertFalse(form.canSubmit)
        }

    // ---- Backdating override ----

    @Test
    fun aReceiptPastTheLimitIsSentOnlyWithAReason() =
        runTest {
            val api = api()
            val vm = viewModel(api)
            openFilledForm(vm)
            vm.setEntryDocument(DOCUMENT_ID)
            val today = assertNotNull(vm.state.entryForm).today

            vm.setEntryBelegDatum(germanDate(today.minus(DatePeriod(days = 45))))
            assertTrue(assertNotNull(vm.state.entryForm).needsLateBookingReason)
            vm.submitEntry()
            assertEquals(0, api.createEntryCalls, "a late booking without a reason must not be sent")

            vm.setEntryLateBookingReason("Lieferantenrechnung verspätet erhalten")
            vm.submitEntry()

            assertEquals(1, api.createEntryCalls)
            assertEquals("Lieferantenrechnung verspätet erhalten", assertNotNull(api.lastCreateRequest).lateBookingReason)
        }

    @Test
    fun aLateBookingWaitingForAManagerIsNotReportedAsBooked() =
        runTest {
            val api = api().apply { createEntryAwaitsApproval = true }
            val vm = viewModel(api)
            openFilledForm(vm)
            vm.setEntryDocument(DOCUMENT_ID)
            val today = assertNotNull(vm.state.entryForm).today
            vm.setEntryBelegDatum(germanDate(today.minus(DatePeriod(days = 45))))
            vm.setEntryLateBookingReason("Quittung im Fahrzeug gefunden")

            vm.submitEntry()

            assertNull(vm.state.entryForm, "the form closes: the request is with a manager")
            assertTrue(vm.state.lateBookingAwaitingApproval, "the operator is told it is waiting, not that it was booked")
        }

    @Test
    fun aReasonForADateInsideTheLimitIsNotSent() =
        runTest {
            val api = api()
            val vm = viewModel(api)
            openFilledForm(vm)
            vm.setEntryDocument(DOCUMENT_ID)
            vm.setEntryLateBookingReason("nicht nötig")

            vm.submitEntry()

            assertEquals(1, api.createEntryCalls)
            assertNull(assertNotNull(api.lastCreateRequest).lateBookingReason)
        }

    // --- F59: the Kassenminus guard, client half ---------------------------------------------------

    @Test
    fun anExpenseAboveWhatTheBookHoldsIsNeverSent() =
        runTest {
            val api = api()
            val vm = viewModel(api, tresorBalance = "23.79")
            openFilledForm(vm)
            vm.setEntryDocument(DOCUMENT_ID)

            val form = assertNotNull(vm.state.entryForm)
            assertEquals("23.79", form.availableCash, "the form carries the book's balance as listed")
            assertTrue(form.exceedsAvailableCash, "23,80 out of a book holding 23,79")
            assertFalse(form.canSubmit)
            vm.submitEntry()
            assertEquals(0, api.createEntryCalls, "a cash book cannot go below zero")
        }

    @Test
    fun negativeControlTheLastCentIsSent() =
        runTest {
            val api = api()
            val vm = viewModel(api, tresorBalance = "23.80")
            openFilledForm(vm)
            vm.setEntryDocument(DOCUMENT_ID)

            assertTrue(assertNotNull(vm.state.entryForm).canSubmit)
            vm.submitEntry()
            assertEquals(1, api.createEntryCalls)
        }

    @Test
    fun anInflowIsNotCappedByTheBalance() =
        runTest {
            val api = api()
            val vm = viewModel(api, tresorBalance = "0.00")
            openFilledForm(vm)
            vm.setEntryType(KassenbuchEntryType.CASH_DEPOSIT)
            vm.setEntryDocument(DOCUMENT_ID)

            val form = assertNotNull(vm.state.entryForm)
            assertFalse(form.exceedsAvailableCash)
            assertTrue(form.canSubmit)
        }
}
