package org.example.project.features.voucher.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.core.data.models.PaymentType
import org.example.project.core.data.provider.PaymentTypeDataProvider
import org.example.project.core.data.remote.BillApiClient
import org.example.project.core.data.remote.EcrConfigurationApiClient
import org.example.project.core.data.remote.VoucherApiClient
import org.example.project.core.domain.dto.setup.ShopConfigurationDto
import org.example.project.core.domain.dto.voucher.VoucherDto
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.PosApiException
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations
import org.example.project.features.voucher.presentation.viewmodel.VoucherCreationViewModel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F38: „Drucken“ after a voucher sale printed nothing — the print was a TODO, so the customer of a
 * signed, booked sale got no receipt. It now prints the sale's bill through the bill print API, and
 * honours the shop's print-settings like „Referenzdruck“ (SET-04).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoucherReceiptPrintTest {
    private val testDispatcher = StandardTestDispatcher()

    /** What the shop's print-settings answer: enabled, or disabled. */
    private var printingEnabled = true

    /** The bill id the voucher sale answers with; null models a table voucher / an older server. */
    private var saleBillId: Long? = 4711L

    /** Thrown by the print call, or null for a successful print. */
    private var printFailure: RuntimeException? = null

    private val printedBills = mutableListOf<Long>()

    private val vouchers: VoucherApiClient =
        proxy { name, _ ->
            check(name == "createVoucher") { name }
            VoucherDto.VoucherResponseDto(
                id = 1,
                sessionId = null,
                voucherNumber = "GS-0001",
                employeeNumber = "1",
                orderItemId = 2,
                originalValue = "50.00",
                remainingValue = "50.00",
                totalRedeemed = "0.00",
                isFullyRedeemed = false,
                createdAt = "2026-10-04T10:00:00",
                fullyRedeemedAt = null,
                status = "ACTIVE",
                billId = saleBillId,
            )
        }

    private val paymentTypes: PaymentTypeDataProvider = proxy { name, _ -> error(name) }

    private val bills: BillApiClient =
        proxy { name, args ->
            check(name == "printBill") { name }
            printFailure?.let { throw it }
            printedBills += args[0] as Long
            Unit
        }

    private val config: EcrConfigurationApiClient =
        proxy { name, args ->
            check(name == "getPrintSettings") { name }
            ShopConfigurationDto.PrintSettingsResponseDto(shopId = args[0] as Long, enabled = printingEnabled)
        }

    private fun viewModel() = VoucherCreationViewModel(vouchers, paymentTypes, bills, config, shopId = { 1L })

    /** Opens the dialog (reads print-settings) and completes a counter sale. */
    private fun VoucherCreationViewModel.sell() {
        loadPrintAvailability()
        setCounterSaleValue("50.00")
        selectCounterSalePaymentType(PaymentType(id = 1, name = "BAR", isActive = true))
        createCounterSaleVoucher()
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        TranslationRegistry.initialize(defaultTranslations())
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `print sends the sale's bill id to the bill print API`() =
        runTest(testDispatcher) {
            val vm = viewModel()
            vm.sell()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.printOffered)

            vm.printVoucherReceipt()
            advanceUntilIdle()

            assertEquals(listOf(4711L), printedBills)
            val state = vm.uiState.value.counterSale
            assertTrue(state.receiptPrinted)
            assertFalse(state.isPrinting)
            assertNull(state.printError)
        }

    @Test
    fun `a refused print surfaces as a typed error and keeps the dialog open`() =
        runTest(testDispatcher) {
            printFailure = PosApiException(PosErrorCode.PRINTING_DISABLED, 409, "Printing is not enabled", null)
            val vm = viewModel()
            vm.sell()
            advanceUntilIdle()

            vm.printVoucherReceipt()
            advanceUntilIdle()

            val state = vm.uiState.value.counterSale
            assertEquals(PosErrorCode.PRINTING_DISABLED, state.printError?.code)
            assertFalse(state.receiptPrinted)
            assertTrue(state.showPrintDialog, "the cashier can retry or skip")

            vm.clearPrintError()
            assertNull(vm.uiState.value.counterSale.printError)
        }

    @Test
    fun `with printing disabled the print is not offered and not sent`() =
        runTest(testDispatcher) {
            printingEnabled = false
            val vm = viewModel()
            vm.sell()
            advanceUntilIdle()

            assertFalse(vm.uiState.value.printOffered)
            vm.printVoucherReceipt()
            advanceUntilIdle()
            assertEquals(emptyList(), printedBills, "the client guard holds even if the button is reached")
        }

    @Test
    fun `without a bill id nothing is printed`() =
        runTest(testDispatcher) {
            saleBillId = null
            val vm = viewModel()
            vm.sell()
            advanceUntilIdle()

            vm.printVoucherReceipt()
            advanceUntilIdle()
            assertEquals(emptyList(), printedBills)
            assertFalse(vm.uiState.value.counterSale.receiptPrinted)
        }

    private inline fun <reified T> proxy(crossinline answer: (String, Array<Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            answer(method.name, args ?: emptyArray())
        } as T
}
