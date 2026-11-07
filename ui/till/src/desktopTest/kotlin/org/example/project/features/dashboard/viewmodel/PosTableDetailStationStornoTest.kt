package org.example.project.features.dashboard.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.core.data.domain.service.ClientOrderService
import org.example.project.core.data.provider.RTableDataProvider
import org.example.project.core.data.remote.EcrConfigurationApiClient
import org.example.project.core.data.remote.OrderApiClient
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.PosApiException
import org.example.project.core.presentation.error.toUiError
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations
import org.example.project.core.presentation.strings.keys.TemporarySessionKeys
import org.example.project.core.utils.LanguageManager
import org.example.project.features.dashboard.presentation.viewmodel.PosTableDetailViewModel
import org.example.project.ui.screens.dashboard.StationCorrectionActions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Owner decision 2026-10-03 (emu21 NOTE-v), client half: an order station never raises a Storno
 * approval request. The Storno is offered only while a manager is signed in on the station (the
 * sign-in lends ORDER_ITEM_CANCEL); without one the ViewModel refuses locally and sends nothing, and
 * the server's own refusal (`STATION_STORNO_REQUIRES_MANAGER`) reads the same sentence.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PosTableDetailStationStornoTest {
    private val testDispatcher = StandardTestDispatcher()

    /** Every call reaching the order service — a refused Storno must leave this empty. */
    private val orderCalls = mutableListOf<String>()

    private fun <T> unused(type: Class<T>): T =
        type.cast(
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
                throw UnsupportedOperationException(method.name)
            },
        )

    private val orders: ClientOrderService =
        Proxy.newProxyInstance(ClientOrderService::class.java.classLoader, arrayOf(ClientOrderService::class.java)) { _, method, _ ->
            orderCalls += method.name
            throw UnsupportedOperationException(method.name)
        } as ClientOrderService

    private fun viewModel() =
        PosTableDetailViewModel(
            unused(RTableDataProvider::class.java),
            orders,
            unused(OrderApiClient::class.java),
            LanguageManager(),
            unused(EcrConfigurationApiClient::class.java),
            shopId = { 1L },
        )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        TranslationRegistry.initialize(defaultTranslations())
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the station offers the Storno only under a manager sign-in, the till always`() {
        assertFalse(StationCorrectionActions.offersStorno(isStation = true, holdsItemCancel = false), "station, no manager")
        assertTrue(StationCorrectionActions.offersStorno(isStation = true, holdsItemCancel = true), "station, manager signed in")
        // At a till a cashier without the capability still reaches the approval request.
        assertTrue(StationCorrectionActions.offersStorno(isStation = false, holdsItemCancel = false))
        assertTrue(StationCorrectionActions.offersStorno(isStation = false, holdsItemCancel = true))
    }

    @Test
    fun `with the Storno withdrawn the dialog does not open and nothing is sent`() =
        runTest(testDispatcher) {
            val vm = viewModel()
            vm.setStornoOffered(false)

            vm.showCancelDialog()
            assertFalse(vm.uiState.value.showCancelDialog, "no Storno dialog on a station without a manager")
            assertEquals(
                PosErrorCode.STATION_STORNO_REQUIRES_MANAGER,
                vm.uiState.value.error
                    ?.code,
            )
            assertEquals(
                TemporarySessionKeys.stationStornoNeedsManagerMessage,
                vm.uiState.value.error
                    ?.messageKey,
            )

            vm.clearError()
            vm.cancelSelectedItems(reason = "wrong table", sourceTableId = 4L) {}
            advanceUntilIdle()
            assertEquals(emptyList(), orderCalls, "no cancel — and so no approval request — leaves the station")
            assertEquals(
                PosErrorCode.STATION_STORNO_REQUIRES_MANAGER,
                vm.uiState.value.error
                    ?.code,
            )
        }

    @Test
    fun `a manager sign-in offers the Storno, and its end withdraws it and closes the dialog`() =
        runTest(testDispatcher) {
            val vm = viewModel()
            vm.setStornoOffered(true)
            vm.showCancelDialog()
            assertTrue(vm.uiState.value.showCancelDialog)
            assertNull(vm.uiState.value.error)

            // The sign-in ends while the dialog is open: back to station rights.
            vm.setStornoOffered(false)
            assertFalse(vm.uiState.value.showCancelDialog, "an ended sign-in closes the Storno dialog")
            assertFalse(vm.uiState.value.stornoOffered)
        }

    @Test
    fun `the server's refusal reads the same sentence as the client guard`() {
        val error =
            PosApiException(
                code = PosErrorCode.STATION_STORNO_REQUIRES_MANAGER,
                httpStatus = 403,
                message = "raw server text",
                traceId = "t-1",
            ).toUiError()

        assertEquals(TemporarySessionKeys.stationStornoNeedsManagerTitle, error.titleKey)
        assertEquals(TemporarySessionKeys.stationStornoNeedsManagerMessage, error.messageKey)
    }
}
