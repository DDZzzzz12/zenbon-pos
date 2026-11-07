package org.example.project.features.dashboard.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.core.data.domain.service.ClientOrderService
import org.example.project.core.data.models.RTable
import org.example.project.core.data.provider.RTableDataProvider
import org.example.project.core.data.remote.EcrConfigurationApiClient
import org.example.project.core.data.remote.OrderApiClient
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.dto.setup.ShopConfigurationDto
import org.example.project.core.network.error.NetworkApiException
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations
import org.example.project.core.utils.LanguageManager
import org.example.project.features.dashboard.presentation.viewmodel.PosTableDetailViewModel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SET-04 (emu19 FAIL-10): „Referenzdruck“ was offered on the till and the station while the shop's
 * printing was disabled, and the server then refused it with 409 `PRINTING_DISABLED`. The panel now
 * reads the print-settings and withholds the action; the server keeps its own refusal.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PosTableDetailReferencePrintTest {
    private val testDispatcher = StandardTestDispatcher()

    /** What the shop's print-settings answer: enabled, disabled, or null for "cannot be read". */
    private var printingEnabled: Boolean? = true

    private val referencePrints = mutableListOf<Long>()

    private val table =
        RTable(
            id = 4,
            tableNumber = "4",
            seats = 4,
            locationTypeId = 1,
            locationTypeName = "Innen",
            tableStatus = "OCCUPIED",
            currentSessionId = 9,
            gridX = 0,
            gridY = 0,
            rotation = 0f,
            gridWidth = 1,
            gridHeight = 1,
            isActive = true,
        )

    private val tables: RTableDataProvider =
        Proxy.newProxyInstance(RTableDataProvider::class.java.classLoader, arrayOf(RTableDataProvider::class.java)) { _, method, _ ->
            if (method.name != "getRTableById") throw UnsupportedOperationException(method.name)
            table
        } as RTableDataProvider

    private val orders: ClientOrderService =
        Proxy.newProxyInstance(ClientOrderService::class.java.classLoader, arrayOf(ClientOrderService::class.java)) { _, method, _ ->
            if (method.name != "getOrderItemsForTable") throw UnsupportedOperationException(method.name)
            emptyList<Any>()
        } as ClientOrderService

    private val orderApi: OrderApiClient =
        Proxy.newProxyInstance(OrderApiClient::class.java.classLoader, arrayOf(OrderApiClient::class.java)) { _, method, args ->
            if (method.name != "printReferencePrint") throw UnsupportedOperationException(method.name)
            referencePrints += (args[0] as OrderItemDto.ReferencePrintRequestDto).tableId
            Unit
        } as OrderApiClient

    private val config: EcrConfigurationApiClient =
        Proxy.newProxyInstance(
            EcrConfigurationApiClient::class.java.classLoader,
            arrayOf(EcrConfigurationApiClient::class.java),
        ) { _, method, args ->
            if (method.name != "getPrintSettings") throw UnsupportedOperationException(method.name)
            val enabled = printingEnabled ?: throw NetworkApiException(IllegalStateException("unreachable"))
            ShopConfigurationDto.PrintSettingsResponseDto(shopId = args[0] as Long, enabled = enabled)
        } as EcrConfigurationApiClient

    private fun viewModel() = PosTableDetailViewModel(tables, orders, orderApi, LanguageManager(), config, shopId = { 1L })

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        TranslationRegistry.initialize(defaultTranslations())
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `with printing disabled the reference print is not offered and not sent`() =
        runTest(testDispatcher) {
            printingEnabled = false
            val vm = viewModel()
            vm.loadTableDetails(table.id)
            advanceUntilIdle()

            assertFalse(vm.uiState.value.referencePrintOffered)
            vm.printReferencePrint()
            advanceUntilIdle()
            assertEquals(emptyList(), referencePrints, "the client guard holds even if the button is reached")
        }

    @Test
    fun `with printing enabled the reference print is offered and sent`() =
        runTest(testDispatcher) {
            printingEnabled = true
            val vm = viewModel()
            vm.loadTableDetails(table.id)
            advanceUntilIdle()

            assertTrue(vm.uiState.value.referencePrintOffered)
            vm.printReferencePrint()
            advanceUntilIdle()
            assertEquals(listOf(table.id), referencePrints)
        }

    @Test
    fun `a toggle made during the shift applies on the next panel open`() =
        runTest(testDispatcher) {
            val vm = viewModel()
            vm.loadTableDetails(table.id)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.referencePrintOffered)

            printingEnabled = false
            vm.loadTableDetails(table.id)
            advanceUntilIdle()

            assertFalse(vm.uiState.value.referencePrintOffered)
        }

    @Test
    fun `unreadable print-settings leave the action offered - the server still refuses`() =
        runTest(testDispatcher) {
            printingEnabled = null
            val vm = viewModel()
            vm.loadTableDetails(table.id)
            advanceUntilIdle()

            assertTrue(vm.uiState.value.referencePrintOffered)
        }
}
