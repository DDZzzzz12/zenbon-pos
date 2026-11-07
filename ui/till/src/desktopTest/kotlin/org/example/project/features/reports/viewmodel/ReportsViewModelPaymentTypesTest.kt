package org.example.project.features.reports.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.core.data.remote.BillApiClient
import org.example.project.core.data.remote.EmployeeApiClient
import org.example.project.core.data.remote.PaymentTypeApiClient
import org.example.project.core.data.remote.StatisticsApiClient
import org.example.project.core.data.remote.ZBonApiClient
import org.example.project.core.domain.dto.payment.PaymentTypeDto
import org.example.project.core.network.error.NetworkApiException
import org.example.project.features.reports.presentation.viewmodel.ReportsViewModel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals

/** How every call this test does not exercise fails: the transport error `runCatchingApi` maps. */
private fun notUsed(call: String) = NetworkApiException(UnsupportedOperationException("$call is not used by the payment-type filter"))

private fun paymentType(
    id: Long,
    name: String,
) = PaymentTypeDto.PaymentTypeResponseDto(id = id, name = name, isActive = true, updatedAt = "2026-09-14T00:00:00")

/**
 * A client the view model is constructed with but this test does not exercise.
 *
 * Every call throws a [NetworkApiException] — the only kind (with `PosApiException`) that
 * `runCatchingApi` catches, so the unexercised load paths fail quietly instead of escaping
 * `viewModelScope` and failing `runTest`.
 */
private inline fun <reified T : Any> unexercised(): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        throw notUsed("${T::class.simpleName}.${method.name}")
    } as T

/**
 * The Reports payment-type filter must be filled from an endpoint every REPORT_READ holder can reach.
 *
 * `GET /api/payment-types/active` is `Cap(CATALOG_MANAGE)` by design (`CatalogRules`, pinned by
 * `PaymentTypeRoutesTest` and `AccountantReachabilityTest`). A cashier, auditor or accountant got a 403
 * the view model swallowed, and their filter stayed empty. `GET /api/payment-types` is
 * `Cap(CATALOG_READ)`, which every REPORT_READ bundle holds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportsViewModelPaymentTypesTest {
    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Records which payment-type endpoint the view model used. */
    private class RecordingPaymentTypeApiClient : PaymentTypeApiClient {
        val listRequests = mutableListOf<Boolean>()
        var activeEndpointCalls = 0
            private set

        override suspend fun getAllPaymentTypes(includeInactive: Boolean): PaymentTypeDto.PaymentTypeListResponseDto {
            listRequests += includeInactive
            val types = listOf(paymentType(1L, "Bar"), paymentType(2L, "Karte"))
            return PaymentTypeDto.PaymentTypeListResponseDto(
                paymentTypes = types,
                total = types.size,
                activeCount = types.size,
                inactiveCount = 0,
            )
        }

        override suspend fun getActivePaymentTypes(): List<PaymentTypeDto.PaymentTypeSummaryDto> {
            activeEndpointCalls++
            // Stands in for the 403 the server answers a cashier.
            throw notUsed("getActivePaymentTypes")
        }

        override suspend fun getPaymentTypeById(id: Long): PaymentTypeDto.PaymentTypeResponseDto = throw notUsed("getPaymentTypeById")

        override suspend fun createPaymentType(request: PaymentTypeDto.CreatePaymentTypeRequestDto): PaymentTypeDto.PaymentTypeResponseDto =
            throw notUsed("createPaymentType")

        override suspend fun updatePaymentType(
            id: Long,
            request: PaymentTypeDto.UpdatePaymentTypeRequestDto,
        ): PaymentTypeDto.PaymentTypeResponseDto = throw notUsed("updatePaymentType")

        override suspend fun deletePaymentType(id: Long): Unit = throw notUsed("deletePaymentType")

        override suspend fun getPaymentTypeStatistics(): PaymentTypeDto.PaymentTypeStatisticsDto = throw notUsed("getPaymentTypeStatistics")

        override suspend fun syncPaymentTypes(lastSyncTime: String): List<PaymentTypeDto.PaymentTypeResponseDto> = throw notUsed("syncPaymentTypes")
    }

    @Test
    fun paymentTypeFilter_isFilledFromTheCatalogReadList() =
        runTest(testDispatcher) {
            val paymentTypes = RecordingPaymentTypeApiClient()
            val viewModel =
                ReportsViewModel(
                    statisticsApiClient = unexercised<StatisticsApiClient>(),
                    zBonApiClient = unexercised<ZBonApiClient>(),
                    employeeApiClient = unexercised<EmployeeApiClient>(),
                    billApiClient = unexercised<BillApiClient>(),
                    paymentTypeApiClient = paymentTypes,
                )

            // A cashier's view: REPORT_READ without REPORT_ALL_READ.
            viewModel.determineViewMode(seesEveryone = false)
            advanceUntilIdle()

            assertEquals(listOf("Bar", "Karte"), viewModel.uiState.value.availablePaymentTypes)
            assertEquals(listOf(false), paymentTypes.listRequests, "active types only, via GET /api/payment-types?includeInactive=false")
            assertEquals(0, paymentTypes.activeEndpointCalls, "GET /api/payment-types/active is CATALOG_MANAGE and must not be called")
        }
}
