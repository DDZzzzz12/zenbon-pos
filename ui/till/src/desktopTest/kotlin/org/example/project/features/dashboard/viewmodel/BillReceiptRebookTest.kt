package org.example.project.features.dashboard.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.core.auth.SettingManager
import org.example.project.core.data.provider.PaymentTypeDataProvider
import org.example.project.core.data.remote.BillApiClient
import org.example.project.core.data.remote.SalesInvoiceApiClient
import org.example.project.core.data.remote.VoucherApiClient
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.dto.voucher.VoucherDto
import org.example.project.core.domain.enums.bill.BillKind
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.domain.models.setup.VoucherSourceType
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.PosApiException
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations
import org.example.project.features.dashboard.presentation.components.billing.acceptsRebook
import org.example.project.features.dashboard.presentation.components.billing.rebookEntries
import org.example.project.features.dashboard.presentation.components.billing.rebookTipDecreaseRefused
import org.example.project.features.dashboard.presentation.state.PaymentEntry
import org.example.project.features.dashboard.presentation.viewmodel.BillReceiptViewModel
import org.example.project.money.Decimal
import org.example.project.testutils.fakes.FakeKeyValueStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val CASH = 1L
private const val CARD = 2L
private const val VOUCHER = 3L

private fun payment(
    typeId: Long,
    amount: String,
) = PaymentDto.PaymentResponse(
    id = typeId,
    amount = amount,
    paymentTypeId = typeId,
    paymentTypeName = "type-$typeId",
    billId = 42L,
    createdAt = "2026-09-29T12:10:00",
)

private fun redeemed(
    number: String,
    amount: String,
    paymentId: Long? = null,
) = VoucherDto.RedeemVoucherResponse(
    id = 1L,
    voucherNumber = number,
    voucherSource = VoucherSourceType.INTERNAL,
    redeemedAmount = amount,
    remainingValue = "0.00",
    redeemedAt = "2026-09-29T12:10:00",
    billId = 42L,
    paymentId = paymentId,
)

private fun bill(
    id: Long = 42L,
    payments: List<PaymentDto.PaymentResponse> = listOf(payment(CASH, "23.00")),
    tips: String = "0.00",
    vouchers: List<VoucherDto.RedeemVoucherResponse>? = null,
    relations: BillDto.BillRelationsDto = BillDto.BillRelationsDto(),
    billAmount: String = "23.00",
    promotionAmount: String = "0.00",
) = BillDto.BillResponseDto(
    billId = id,
    employeeId = "emp-1",
    employeeNumber = "07",
    tipRecipient = "EMPLOYEE",
    tableSessionId = 500L,
    tableNumber = "12",
    billNumber = "RE-$id",
    billAmount = billAmount,
    promotionAmount = promotionAmount,
    paidAmount = billAmount,
    tips = tips,
    startTime = "2026-09-29T12:00:00",
    endTime = "2026-09-29T12:10:00",
    voucherRedeem = vouchers,
    orderItems = emptyList(),
    payments = payments,
    isRebookable = true,
    relations = relations,
)

private fun entry(
    typeId: Long,
    amount: String,
    voucherNumber: String? = null,
) = PaymentEntry(
    id = "rb-$typeId-$amount",
    paymentTypeId = typeId,
    paymentTypeName = "type-$typeId",
    amount = Decimal.parse(amount),
    voucherNumber = voucherNumber,
    voucherSource = voucherNumber?.let { VoucherSourceType.INTERNAL },
)

/**
 * The till half of Zahlartwechsel = Storno + Neubuchung: the view model sends the new tender and the
 * WHOLE tip as a [BillDto.RebookRequestDto] (voucher legs exactly as billing sends them), never
 * sends a tender that does not balance or changes nothing, and surfaces the server's refusal as a
 * typed [UiError].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BillReceiptRebookTest {
    private val testDispatcher = StandardTestDispatcher()

    /** Every rebook request the view model sent, with the bill id it named. */
    private val sent = mutableListOf<Pair<Long, BillDto.RebookRequestDto>>()

    /** What `rebookBill` does with the call; the default answers with a Neubuchung RE-43. */
    private var answer: (BillDto.RebookRequestDto) -> BillDto.BillResponseDto = {
        bill(
            id = 43L,
            relations = BillDto.BillRelationsDto(kind = BillKind.REBOOKING, rebooksBill = BillDto.BillLinkDto(42L, "RE-42")),
        )
    }

    private val billApi: BillApiClient =
        Proxy.newProxyInstance(BillApiClient::class.java.classLoader, arrayOf(BillApiClient::class.java)) { _, method, args ->
            if (method.name != "rebookBill") error("unexpected call ${method.name}")
            val request = args[1] as BillDto.RebookRequestDto
            sent += (args[0] as Long) to request
            answer(request)
        } as BillApiClient

    private inline fun <reified T : Any> unused(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            error("unexpected call ${method.name}")
        } as T

    private fun viewModel() =
        BillReceiptViewModel(
            billApiClient = billApi,
            salesInvoiceApiClient = unused<SalesInvoiceApiClient>(),
            paymentTypeDataProvider = unused<PaymentTypeDataProvider>(),
            voucherApiClient = unused<VoucherApiClient>(),
            settingManager = SettingManager(FakeKeyValueStore()),
        )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        TranslationRegistry.initialize(defaultTranslations())
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `rebook sends the whole tip and the tender`() =
        runTest(testDispatcher) {
            val vm = viewModel()
            vm.rebookBill(bill(), entries = listOf(entry(CARD, "25.00")), tip = Decimal.parse("2.00"), mayLowerTip = false)
            advanceUntilIdle()

            val (billId, request) = sent.single()
            assertEquals(42L, billId)
            assertEquals("2.00", request.tips)
            assertEquals(listOf("25.00" to CARD), request.payments.map { it.amount to it.paymentTypeId })
            assertTrue(request.voucherRedeem.isEmpty())
            assertEquals(43L, vm.rebookedBill.value?.billId, "the Neubuchung is handed to the overlay")
            assertNull(vm.error.value)
        }

    @Test
    fun `a corrected voucher is sent`() =
        runTest(testDispatcher) {
            val original =
                bill(payments = listOf(payment(VOUCHER, "23.00")), vouchers = listOf(redeemed("G-A", "23.00")))
            viewModel().rebookBill(original, entries = listOf(entry(VOUCHER, "23.00", voucherNumber = "G-B")), tip = Decimal.ZERO, mayLowerTip = false)
            advanceUntilIdle()

            val request = sent.single().second
            assertEquals(
                listOf(VoucherDto.RedeemVoucherRequestDto("G-B", "23.00", VoucherSourceType.INTERNAL)),
                request.voucherRedeem,
            )
            assertEquals(listOf("23.00" to VOUCHER), request.payments.map { it.amount to it.paymentTypeId })
            assertEquals("0.00", request.tips)
        }

    @Test
    fun `a legacy voucher leg is sent with source LEGACY, exactly as table billing sends it`() =
        runTest(testDispatcher) {
            // The Zahlartwechsel now adds payments through the billing screen's PaymentTypePickerDialog,
            // which takes a LEGACY voucher by number and amount without validation.
            val legacy =
                PaymentEntry(
                    id = "chg-0-$VOUCHER-23.00-v",
                    paymentTypeId = VOUCHER,
                    paymentTypeName = "GUTSCHEIN",
                    amount = Decimal.parse("23.00"),
                    voucherNumber = "ALT-77",
                    voucherSource = VoucherSourceType.LEGACY,
                )
            viewModel().rebookBill(bill(), entries = listOf(legacy), tip = Decimal.ZERO, mayLowerTip = false)
            advanceUntilIdle()

            val request = sent.single().second
            assertEquals(
                listOf(VoucherDto.RedeemVoucherRequestDto("ALT-77", "23.00", VoucherSourceType.LEGACY)),
                request.voucherRedeem,
            )
            assertEquals(listOf("23.00" to VOUCHER), request.payments.map { it.amount to it.paymentTypeId })
        }

    @Test
    fun `no-change tender is not sent`() =
        runTest(testDispatcher) {
            val vm = viewModel()
            vm.rebookBill(bill(), entries = listOf(entry(CASH, "23.00")), tip = Decimal.ZERO, mayLowerTip = false)
            advanceUntilIdle()

            assertTrue(sent.isEmpty(), "the server would refuse it with BILL_REBOOK_NO_CHANGE")
            assertNull(vm.rebookedBill.value)
        }

    @Test
    fun `a tender that does not balance is not sent`() =
        runTest(testDispatcher) {
            val vm = viewModel()
            vm.rebookBill(bill(), entries = listOf(entry(CARD, "20.00")), tip = Decimal.ZERO, mayLowerTip = false)
            vm.rebookBill(bill(), entries = listOf(entry(CARD, "25.00")), tip = Decimal.parse("-2.00"), mayLowerTip = false)
            advanceUntilIdle()

            assertTrue(sent.isEmpty())
        }

    @Test
    fun `server refusal lands in error`() =
        runTest(testDispatcher) {
            answer = { throw PosApiException(PosErrorCode.BILL_REBOOK_UNSUPPORTED, 409, "gift-voucher sale", null) }
            val vm = viewModel()
            vm.rebookBill(bill(), entries = listOf(entry(CARD, "23.00")), tip = Decimal.ZERO, mayLowerTip = false)
            advanceUntilIdle()

            val error = assertIs<UiError.FromServer>(vm.error.value)
            assertEquals(PosErrorCode.BILL_REBOOK_UNSUPPORTED, error.code)
            assertNull(vm.rebookedBill.value)
        }

    @Test
    fun `a promotion lowers the total the new tender must cover`() =
        runTest(testDispatcher) {
            val promoted = bill(billAmount = "30.00", promotionAmount = "5.00", payments = listOf(payment(CASH, "25.00")))
            val vm = viewModel()

            vm.rebookBill(promoted, entries = listOf(entry(CARD, "30.00")), tip = Decimal.ZERO, mayLowerTip = false)
            advanceUntilIdle()
            assertTrue(sent.isEmpty(), "30.00 is the pre-promotion amount; the bill total is 25.00")

            vm.rebookBill(promoted, entries = listOf(entry(CARD, "27.00")), tip = Decimal.parse("2.00"), mayLowerTip = false)
            advanceUntilIdle()
            val request = sent.single().second
            assertEquals(listOf("27.00" to CARD), request.payments.map { it.amount to it.paymentTypeId })
            assertEquals("2.00", request.tips)
        }

    // --------------------------------------------------------------------------------------------
    // Tip: lowered only with BILL_VOID (user decision A)
    // --------------------------------------------------------------------------------------------

    /** A 23.00 sale with a 2.00 tip, paid 25.00 by card. */
    private fun tipped() = bill(payments = listOf(payment(CARD, "25.00")), tips = "2.00")

    @Test
    fun `without BILL_VOID a lower tip is not accepted and not sent`() =
        runTest(testDispatcher) {
            val original = tipped()
            assertFalse(original.acceptsRebook(listOf(entry(CARD, "23.00")), Decimal.ZERO, mayLowerTip = false))
            assertTrue(original.rebookTipDecreaseRefused(Decimal.ZERO, mayLowerTip = false), "the dialog shows the hint")

            val vm = viewModel()
            vm.rebookBill(original, entries = listOf(entry(CARD, "23.00")), tip = Decimal.ZERO, mayLowerTip = false)
            advanceUntilIdle()
            assertTrue(sent.isEmpty(), "the server would refuse it with BILL_REBOOK_TIP_DECREASE_FORBIDDEN")
        }

    @Test
    fun `without BILL_VOID the tip may be kept or raised`() =
        runTest(testDispatcher) {
            val original = tipped()
            assertTrue(original.acceptsRebook(listOf(entry(CASH, "25.00")), Decimal.parse("2.00"), mayLowerTip = false), "kept, method changed")
            assertTrue(original.acceptsRebook(listOf(entry(CARD, "26.00")), Decimal.parse("3.00"), mayLowerTip = false), "raised")
            assertFalse(original.rebookTipDecreaseRefused(Decimal.parse("2.00"), mayLowerTip = false))
            assertFalse(original.rebookTipDecreaseRefused(Decimal.parse("3.00"), mayLowerTip = false))
        }

    @Test
    fun `with BILL_VOID a lower tip is sent`() =
        runTest(testDispatcher) {
            val original = tipped()
            assertFalse(original.rebookTipDecreaseRefused(Decimal.ZERO, mayLowerTip = true))

            viewModel().rebookBill(original, entries = listOf(entry(CARD, "23.00")), tip = Decimal.ZERO, mayLowerTip = true)
            advanceUntilIdle()
            assertEquals("0.00", sent.single().second.tips)
        }

    // --------------------------------------------------------------------------------------------
    // The dialog starts from the original tender
    // --------------------------------------------------------------------------------------------

    /** Voucher G-A 20.00 (payment 11) + cash 10.00 (payment 12) for a 30.00 sale. */
    private fun voucherAndCash() =
        bill(
            billAmount = "30.00",
            payments = listOf(payment(VOUCHER, "20.00").copy(id = 11L), payment(CASH, "10.00").copy(id = 12L)),
            vouchers = listOf(redeemed("G-A", "20.00", paymentId = 11L)),
        )

    @Test
    fun `the initial entries are the original tender, voucher leg included`() {
        val original = voucherAndCash()

        val entries = original.rebookEntries()

        assertEquals(
            listOf(Triple(VOUCHER, Decimal.parse("20.00"), "G-A"), Triple(CASH, Decimal.parse("10.00"), null)),
            entries.map { Triple(it.paymentTypeId, it.amount, it.voucherNumber) },
        )
        assertEquals(VoucherSourceType.INTERNAL, entries.first().voucherSource, "the kept leg carries its source")
        assertFalse(original.acceptsRebook(entries, Decimal.ZERO, mayLowerTip = false), "unchanged, so nothing to confirm yet")
    }

    /** UI review follow-up 7: the kept legs are named in the UI language, the German name without a translation. */
    @Test
    fun `the initial entries are named in the UI language`() {
        val original =
            voucherAndCash().let { bill ->
                bill.copy(
                    payments =
                        bill.payments.map {
                            if (it.paymentTypeId == CASH) it.copy(paymentTypeEN = "Cash", paymentTypeCH = "现金") else it
                        },
                )
            }

        assertEquals(listOf("type-3", "Cash"), original.rebookEntries(Language.ENGLISH).map { it.paymentTypeName })
        assertEquals(listOf("type-3", "现金"), original.rebookEntries(Language.CHINESE).map { it.paymentTypeName })
        assertEquals(listOf("type-3", "type-1"), original.rebookEntries(Language.GERMAN).map { it.paymentTypeName })
    }

    @Test
    fun `a kept voucher leg is sent with its own number when only the other leg changes`() =
        runTest(testDispatcher) {
            val original = voucherAndCash()
            val (voucherLeg, cashLeg) = original.rebookEntries()
            val entries = listOf(voucherLeg, entry(CARD, "10.00"))
            assertTrue(cashLeg.voucherNumber == null)

            viewModel().rebookBill(original, entries = entries, tip = Decimal.ZERO, mayLowerTip = false)
            advanceUntilIdle()

            val request = sent.single().second
            assertEquals(listOf("20.00" to VOUCHER, "10.00" to CARD), request.payments.map { it.amount to it.paymentTypeId })
            assertEquals(
                listOf(VoucherDto.RedeemVoucherRequestDto("G-A", "20.00", VoucherSourceType.INTERNAL)),
                request.voucherRedeem,
            )
        }

    // --------------------------------------------------------------------------------------------
    // After a failure the bill is re-read
    // --------------------------------------------------------------------------------------------

    @Test
    fun `a failed rebook names the bill to re-read, a successful one does not`() =
        runTest(testDispatcher) {
            val vm = viewModel()
            vm.rebookBill(bill(), entries = listOf(entry(CARD, "23.00")), tip = Decimal.ZERO, mayLowerTip = false)
            advanceUntilIdle()
            assertNull(vm.rebookFailedBillId.value)

            answer = { throw PosApiException(PosErrorCode.BILL_ALREADY_DELETED, 409, "already stornoed", null) }
            vm.rebookBill(bill(), entries = listOf(entry(CARD, "23.00")), tip = Decimal.ZERO, mayLowerTip = false)
            advanceUntilIdle()
            assertEquals(42L, vm.rebookFailedBillId.value, "the overlay has its host re-read bill 42")

            vm.clearRebookFailed()
            assertNull(vm.rebookFailedBillId.value)
        }
}
