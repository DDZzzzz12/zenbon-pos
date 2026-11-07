package org.example.project.features.dashboard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.core.data.domain.service.ClientOrderService
import org.example.project.core.data.models.Items
import org.example.project.core.data.models.Product
import org.example.project.core.data.models.RTable
import org.example.project.core.data.provider.RTableDataProvider
import org.example.project.core.data.remote.ApprovalOutcome
import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.dto.rotation.RotationDto
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.components.notification.OverlayEvent
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.RulesKeys
import org.example.project.core.utils.LanguageManager
import org.example.project.features.dashboard.presentation.viewmodel.TableOrderViewModel
import org.example.project.features.rotation.RotationNextStore
import org.example.project.money.Decimal
import org.example.project.testutils.fakes.FakeOverlayEventBus
import org.example.project.testutils.fakes.FakeProductCategoryDataProvider
import org.example.project.testutils.fakes.FakeProductDataProvider
import org.example.project.testutils.fakes.StubChoiceGroupDataProvider
import org.example.project.testutils.fakes.StubRTableDataProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** When the rotation moved on under the till, the cashier is told who actually got the table. */
@OptIn(ExperimentalCoroutinesApi::class)
class TableOrderViewModelRotationToastTest {
    private val testDispatcher = StandardTestDispatcher()
    private val bus = FakeOverlayEventBus()
    private val anna = RotationDto.EmployeeRefDto(employeeId = "id-anna", employeeNumber = "E1", displayName = "Anna")
    private val ben = RotationDto.EmployeeRefDto(employeeId = "id-ben", employeeNumber = "E2", displayName = "Ben")
    private val store = RotationNextStore()
    private var storeVersion = 0L

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        TranslationRegistry.initialize(defaultTranslations())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun response(
        shifted: Boolean,
        owner: RotationDto.EmployeeRefDto?,
    ) = OrderDto.OrderResponseDto(
        orderId = 1L,
        orderNumber = "O1",
        employeeId = "id-ben",
        tableNumber = "3",
        orderTime = "2026-10-16T10:00:00",
        orderTotal = "1.00",
        rotationShifted = shifted,
        rotationOwner = owner,
    )

    private fun show(next: RotationDto.EmployeeRefDto?) =
        store.apply(NotificationDto.RotationNextUpdateDto(timestamp = "", version = ++storeVersion, next = next))

    private fun viewModel(
        service: AnsweringOrderService,
        sessionId: Long? = null,
    ) = TableOrderViewModel(
        rTableDataProvider = TableProvider(sessionId),
        productDataProvider = FakeProductDataProvider(),
        categoryDataProvider = FakeProductCategoryDataProvider(),
        orderService = service,
        choiceGroupDataProvider = StubChoiceGroupDataProvider(),
        overlayEventBus = bus,
        languageManager = LanguageManager(),
        rotationNextStore = store,
    )

    /** Opens the order screen for table 3, lets [beforeSubmit] run while the waiter composes, then submits. */
    private suspend fun kotlinx.coroutines.test.TestScope.submit(
        answer: OrderDto.OrderResponseDto?,
        sessionId: Long? = null,
        beforeSubmit: () -> Unit = {},
    ): AnsweringOrderService {
        val service = AnsweringOrderService(answer)
        val vm = viewModel(service, sessionId)
        vm.loadTable(3L)
        advanceUntilIdle()
        vm.confirmConfiguredItem(line())
        advanceUntilIdle()
        beforeSubmit()
        vm.submitOrder()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.submitSuccess)
        return service
    }

    @Test
    fun showsToastNamingNewOwnerWhenShifted() =
        runTest(testDispatcher) {
            submit(response(shifted = true, owner = ben))

            val toast =
                bus.history
                    .filterIsInstance<OverlayEvent.Enqueue>()
                    .single()
                    .state
            assertEquals(getString(RulesKeys.rotationShiftedToast, Language.DEFAULT, "Ben"), toast.message)
            assertTrue("Ben" in toast.message)
        }

    @Test
    fun noToastWhenNotShifted() =
        runTest(testDispatcher) {
            submit(response(shifted = false, owner = null))

            assertTrue(bus.history.isEmpty())
        }

    @Test
    fun nullResponseIsSuccessWithoutToast() =
        runTest(testDispatcher) {
            // An older server answers 201 with no body: the order is booked, there is just no shift info.
            submit(null)

            assertTrue(bus.history.isEmpty())
        }

    @Test
    fun expectationIsWhatTheTillShowedWhenTheFreeTableWasOpened() =
        runTest(testDispatcher) {
            show(anna)

            // Another till opens a table while this waiter composes: the store moves on to Ben.
            val service = submit(response(shifted = true, owner = ben), beforeSubmit = { show(ben) })

            assertEquals("id-anna", service.expectations.single(), "the id captured when the screen opened, not a fresh read")
            val toast =
                bus.history
                    .filterIsInstance<OverlayEvent.Enqueue>()
                    .single()
                    .state
            assertTrue("Ben" in toast.message)
        }

    @Test
    fun noExpectationForATableThatAlreadyHasASession() =
        runTest(testDispatcher) {
            show(anna)

            val service = submit(response(shifted = false, owner = null), sessionId = 7L)

            assertNull(service.expectations.single())
        }

    @Test
    fun noExpectationWhenNothingWasShown() =
        runTest(testDispatcher) {
            val service = submit(response(shifted = false, owner = null), beforeSubmit = { show(anna) })

            assertNull(service.expectations.single(), "nothing was shown when the table was opened")
        }

    private fun line() =
        Items(
            id = 1L,
            orderItemIds = listOf(1L),
            product =
                Product(
                    id = 1L,
                    productNumber = "P1",
                    name = "Cola",
                    description = null,
                    price = Decimal.parse("1.00"),
                    productType = "FOOD",
                    categoryId = 1L,
                    isActive = true,
                ),
            unitPrice = Decimal.parse("1.00"),
            totalPrice = Decimal.parse("1.00"),
            quantity = 1,
        )

    private class TableProvider(
        private val sessionId: Long?,
    ) : RTableDataProvider by StubRTableDataProvider() {
        override suspend fun getRTableById(id: Long): RTable =
            RTable(
                id = id,
                tableNumber = "$id",
                seats = 4,
                locationTypeId = 1L,
                locationTypeName = "Indoor",
                tableStatus = if (sessionId == null) "FREE" else "OCCUPIED",
                currentSessionId = sessionId,
                gridX = 0,
                gridY = 0,
                rotation = 0f,
            )
    }

    private class AnsweringOrderService(
        private val answer: OrderDto.OrderResponseDto?,
    ) : ClientOrderService {
        val expectations = mutableListOf<String?>()

        override suspend fun submitOrder(
            sessionId: Long?,
            items: List<Items>,
            tableId: Long?,
            forEmployeeNumber: String?,
            expectedNextEmployeeId: String?,
        ): OrderDto.OrderResponseDto? {
            expectations += expectedNextEmployeeId
            return answer
        }

        override suspend fun getOrderItemsForTable(tableId: Long): List<OrderItemDto.OrderItemResponseDto> = error("unused")

        override suspend fun cancelOrderItems(
            itemIds: List<Long>,
            reason: String?,
            sourceTableId: Long,
        ): ApprovalOutcome = error("unused")

        override suspend fun transferOrderItems(
            itemIds: List<Long>,
            targetTableId: Long,
            sourceTableId: Long,
            reason: String,
        ): ApprovalOutcome = error("unused")
    }
}
