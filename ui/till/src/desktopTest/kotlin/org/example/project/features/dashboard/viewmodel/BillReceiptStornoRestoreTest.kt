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
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.PosApiException
import org.example.project.features.dashboard.presentation.state.StornoRestoreConflict
import org.example.project.features.dashboard.presentation.viewmodel.BillReceiptViewModel
import org.example.project.testutils.fakes.FakeKeyValueStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A Storno that re-opens its table session is refused (409 TABLE_HAS_ACTIVE_SESSION) when new
 * guests now occupy the table. The till must not show that as a plain error: it offers a Storno
 * without re-opening, and confirming retries with `restoreSession = false`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BillReceiptStornoRestoreTest {
    private val testDispatcher = StandardTestDispatcher()

    private val bills = StornoRecordingBillApiClient()

    private fun viewModel() =
        BillReceiptViewModel(
            billApiClient = bills,
            salesInvoiceApiClient = unused<SalesInvoiceApiClient>(),
            paymentTypeDataProvider = unused<PaymentTypeDataProvider>(),
            voucherApiClient = unused<VoucherApiClient>(),
            settingManager = SettingManager(FakeKeyValueStore()),
        )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `an occupied table offers a storno without re-opening instead of an error`() =
        runTest(testDispatcher) {
            bills.refuseRestore = true
            val vm = viewModel()

            vm.deleteBill(42L, "Falsch gebucht", restoreSession = true)
            advanceUntilIdle()

            assertEquals(StornoRestoreConflict(42L, "Falsch gebucht"), vm.stornoRestoreConflict.value)
            assertNull(vm.error.value, "the conflict is a choice, not an error")
            assertTrue(!vm.billDeleted.value)

            vm.confirmStornoWithoutRestore()
            advanceUntilIdle()

            assertEquals(listOf(42L to true, 42L to false), bills.calls, "the retry must not re-open the table")
            assertNull(vm.stornoRestoreConflict.value)
            assertTrue(vm.billDeleted.value)
        }

    @Test
    fun `dismissing the conflict voids nothing`() =
        runTest(testDispatcher) {
            bills.refuseRestore = true
            val vm = viewModel()

            vm.deleteBill(42L, null, restoreSession = true)
            advanceUntilIdle()
            vm.dismissStornoRestoreConflict()
            advanceUntilIdle()

            assertNull(vm.stornoRestoreConflict.value)
            assertEquals(listOf(42L to true), bills.calls)
            assertTrue(!vm.billDeleted.value)
        }

    @Test
    fun `a plain storno is sent without re-opening`() =
        runTest(testDispatcher) {
            val vm = viewModel()

            vm.deleteBill(42L, null, restoreSession = false)
            advanceUntilIdle()

            assertEquals(listOf(42L to false), bills.calls)
            assertTrue(vm.billDeleted.value)
            assertNotNull(vm.successMessage.value)
        }

    @Test
    fun `a refused storno names the bill so the host re-reads it and its list`() =
        runTest(testDispatcher) {
            bills.alreadyVoided = true
            val vm = viewModel()

            vm.deleteBill(42L, null, restoreSession = false)
            advanceUntilIdle()

            assertEquals(PosErrorCode.BILL_ALREADY_DELETED, vm.error.value?.code)
            assertEquals(42L, vm.stornoFailedBillId.value)
            assertTrue(!vm.billDeleted.value)

            vm.clearStornoFailed()
            assertNull(vm.stornoFailedBillId.value)
        }

    @Test
    fun `the table conflict is not a failed storno`() =
        runTest(testDispatcher) {
            bills.refuseRestore = true
            val vm = viewModel()

            vm.deleteBill(42L, null, restoreSession = true)
            advanceUntilIdle()

            assertNull(vm.stornoFailedBillId.value, "nothing changed yet; the operator still chooses")
        }
}

/** Any call the test does not expect fails loudly. */
private inline fun <reified T> unused(): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        error("unexpected call: ${method.name}")
    } as T

private class StornoRecordingBillApiClient : BillApiClient by unused() {
    val calls = mutableListOf<Pair<Long, Boolean>>()
    var refuseRestore = false
    var alreadyVoided = false

    override suspend fun deleteBill(
        billId: Long,
        deletingReason: String?,
        restoreSession: Boolean,
    ): String {
        calls += billId to restoreSession
        if (alreadyVoided) {
            throw PosApiException(
                code = PosErrorCode.BILL_ALREADY_DELETED,
                httpStatus = 409,
                message = "Bill $billId is already deleted",
                traceId = null,
            )
        }
        if (restoreSession && refuseRestore) {
            throw PosApiException(
                code = PosErrorCode.TABLE_HAS_ACTIVE_SESSION,
                httpStatus = 409,
                message = "table is occupied",
                traceId = null,
            )
        }
        return "Bill deleted successfully"
    }
}
