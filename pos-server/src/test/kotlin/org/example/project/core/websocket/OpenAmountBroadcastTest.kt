package org.example.project.core.websocket

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.dto.table.RTableDto
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.features.bill.domain.service.BillService
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.order.domain.service.OrderItemService
import org.example.project.features.order.domain.service.OrderService
import org.example.project.features.payment.domain.models.Vat
import org.example.project.features.product.domain.models.Product
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.table.domain.service.RTableService
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Integration tests asserting that the five server write paths broadcast a
 * [NotificationDto.TableStatusUpdateDto] whose `openAmount` equals the recomputed open
 * total for the *partial* (session-not-completed) case:
 *
 *  1. Order creation — [OrderService.createOrder] (emits after Phase-1 commit).
 *  2. Partial item cancel — [OrderItemService.cancelOrderItems] (open items remain).
 *  3. Partial payment — [BillService.createBill] → createBillInternal (open items remain).
 *  4. Item transfer — [OrderItemService.transferOrderItems] (both tables change, E2E-2026-09-15 N6).
 *     Covered on BOTH of that function's exits: the source session surviving with fewer items, and
 *     the source emptying out (session completed) into an already-occupied target (E2E-2026-09-16 F14).
 *  5. Whole-table transfer — [RTableService.transferTable], the manager action. Both tables change
 *     here too, but the source session COMPLETES (every open item leaves), so its card carries a
 *     freed table and a null openAmount rather than a reduced one.
 *
 * Each test registers an observer SSE connection on [NotificationHub] and drains the queued
 * frames (mirroring [NotificationServiceTest]). The emit is enqueued synchronously (trySend),
 * so by the time the suspend service call returns the frame is already on the channel.
 *
 * All fixture creation, the service call, and the assertions run inside `setupData` (the
 * Koin/DB-bound application thread), exactly as `RTableServiceOpenAmountTest` does — the
 * Exposed default database is only reliably bound there.
 */
class OpenAmountBroadcastTest {
    private val deviceId = "open-amount-test-device"

    @AfterEach
    fun tearDown() = NotificationHub.resetForTest()

    /** Drains all queued frames and returns the table-status updates, in emission order. */
    private fun Channel<OutgoingFrame>.drainTableStatusUpdates(): List<NotificationDto.TableStatusUpdateDto> {
        val updates = mutableListOf<NotificationDto.TableStatusUpdateDto>()
        while (true) {
            val frame = tryReceive().getOrNull() ?: break
            val msg = (frame as? OutgoingFrame.Event)?.event?.message
            if (msg is NotificationDto.TableStatusUpdateDto) updates += msg
        }
        return updates
    }

    /** Terminal lookup (findByDeviceId) backs the clientId the write paths require. */
    private fun createTerminal() =
        transaction {
            Terminal.new {
                this.deviceId = this@OpenAmountBroadcastTest.deviceId
                this.clientId = "OA-CLIENT"
                this.isActive = true
                this.registeredAt = currentTimeStamp()
                this.lastSeenAt = currentTimeStamp()
            }
        }

    /** Adds an OPEN item with the given finalPrice to a session; returns its id. */
    private fun addOpenItem(
        sessionId: Long,
        productId: Long,
        vatId: String,
        price: String,
    ): Long =
        transaction {
            OrderItem
                .new {
                    this.session = TableSession.findById(sessionId)!!
                    this.product = Product.findById(productId)!!
                    this.unitPrice = BigDecimal(price)
                    this.finalPrice = BigDecimal(price)
                    this.itemStatus = ItemStatus.OPEN
                    this.vat = Vat.findById(vatId)!!
                    this.discount = BigDecimal.ONE
                    this.customPrice = false
                    this.takeAway = false
                    this.transferred = false
                    this.sideNotes = null
                }.id.value
        }

    /** Registers an observer connection that all broadcasts reach. */
    private suspend fun observe(): Channel<OutgoingFrame> {
        val out = Channel<OutgoingFrame>(Channel.UNLIMITED)
        NotificationHub.addSseConnection(connectionId = "obs", employeeId = "obs", outgoing = out)
        return out
    }

    @Test
    fun `createOrder broadcasts open amount of the new order`() =
        testApp(
            setupData = {
                val manager = TestData.createManager(employeeNumber = "OA_MGR", username = "oa_mgr")
                val category = TestData.createCategory(name = "OA_Cat")
                val product =
                    TestData.createProduct(
                        categoryId = category.id.value,
                        productName = "OA_Prod",
                        productNumber = "OA_P",
                        price = BigDecimal("12.5000"),
                    )
                TestData.createDefaultVatRates()
                val tws = TestData.createTableWithSession(tableNumber = "OA_CREATE")
                createTerminal()

                val service = GlobalContext.get().get<OrderService>()
                val updates =
                    runBlocking {
                        val out = observe()
                        // Two items @ 12.50 (no discount) → open total 25.00.
                        service.createOrder(
                            request =
                                OrderDto.CreateOrderRequestDto(
                                    tableInfoId = tws.session.id.value,
                                    orderItems =
                                        listOf(
                                            OrderItemDto.CreateOrderItemRequestDto(
                                                productId = product.id.value,
                                                unitPrice = "12.50",
                                                quantity = 2,
                                            ),
                                        ),
                                ),
                            employeeId = manager.id.value.toString(),
                            deviceId = deviceId,
                            actorCapabilities = emptySet(),
                        )
                        out.drainTableStatusUpdates()
                    }

                val update = updates.single()
                assertEquals(tws.table.id.value, update.tableId)
                assertEquals("25.00", update.openAmount)
            },
        ) { _ -> }

    @Test
    fun `partial cancel broadcasts open amount of the remaining items`() =
        testApp(
            setupData = {
                val manager = TestData.createManager(employeeNumber = "OA_MGR", username = "oa_mgr")
                val category = TestData.createCategory(name = "OA_Cat")
                val product = TestData.createProduct(categoryId = category.id.value, productName = "OA_Prod", productNumber = "OA_P")
                val vat = TestData.createDefaultVatRates().first()
                val tws = TestData.createTableWithSession(tableNumber = "OA_CANCEL")
                createTerminal()

                // Seed three OPEN items: 10.00, 5.50, 4.00.
                addOpenItem(tws.session.id.value, product.id.value, vat.id.value, "10.00")
                addOpenItem(tws.session.id.value, product.id.value, vat.id.value, "5.50")
                val toCancel = addOpenItem(tws.session.id.value, product.id.value, vat.id.value, "4.00")

                val service = GlobalContext.get().get<OrderItemService>()
                val updates =
                    runBlocking {
                        val out = observe()
                        // Cancel only the 4.00 item → 10.00 + 5.50 = 15.50 remain OPEN.
                        service.cancelOrderItems(
                            item = listOf(toCancel),
                            employeeId = manager.id.value.toString(),
                            deviceId = deviceId,
                            reason = "test partial cancel",
                            sourceTableId = tws.table.id.value,
                        )
                        out.drainTableStatusUpdates()
                    }

                val update = updates.single()
                assertEquals(tws.table.id.value, update.tableId)
                assertEquals("15.50", update.openAmount)
            },
        ) { _ -> }

    @Test
    fun `partial payment broadcasts open amount of the unbilled items`() =
        testApp(
            setupData = {
                val manager = TestData.createManager(employeeNumber = "OA_MGR", username = "oa_mgr")
                val category = TestData.createCategory(name = "OA_Cat")
                val product = TestData.createProduct(categoryId = category.id.value, productName = "OA_Prod", productNumber = "OA_P")
                val vat = TestData.createDefaultVatRates().first()
                val cash = TestData.createPaymentType(name = "OA_Cash")
                val tws = TestData.createTableWithSession(tableNumber = "OA_BILL")
                createTerminal()

                // Seed three OPEN items: 10.00, 5.50, 4.00.
                val toBill = addOpenItem(tws.session.id.value, product.id.value, vat.id.value, "10.00")
                addOpenItem(tws.session.id.value, product.id.value, vat.id.value, "5.50")
                addOpenItem(tws.session.id.value, product.id.value, vat.id.value, "4.00")

                val service = GlobalContext.get().get<BillService>()
                val updates =
                    runBlocking {
                        val out = observe()
                        // Pay only the 10.00 item → 5.50 + 4.00 = 9.50 remain OPEN.
                        service.createBill(
                            employeeId = manager.id.value.toString(),
                            deviceId = deviceId,
                            request =
                                BillDto.CreateBillRequestDto(
                                    tableSessionId = tws.session.id.value,
                                    orderItems = listOf(toBill),
                                    billAmount = "10.00",
                                    payments = listOf(PaymentDto.PaymentRequest(amount = "10.00", paymentTypeId = cash.id.value)),
                                ),
                        )
                        out.drainTableStatusUpdates()
                    }

                val update = updates.single()
                assertEquals(tws.table.id.value, update.tableId)
                assertEquals("9.50", update.openAmount)
            },
        ) { _ -> }

    @Test
    fun `partial transfer broadcasts the source table with its reduced open amount`() =
        testApp(
            setupData = {
                val manager = TestData.createManager(employeeNumber = "OA_MGR", username = "oa_mgr")
                val category = TestData.createCategory(name = "OA_Cat")
                val product = TestData.createProduct(categoryId = category.id.value, productName = "OA_Prod", productNumber = "OA_P")
                val vat = TestData.createDefaultVatRates().first()
                val source = TestData.createTableWithSession(tableNumber = "OA_TR_SRC")
                val target = TestData.createTable(tableNumber = "OA_TR_FRE")

                // Seed three OPEN items on the source: 10.00, 5.50, 4.00.
                addOpenItem(source.session.id.value, product.id.value, vat.id.value, "10.00")
                addOpenItem(source.session.id.value, product.id.value, vat.id.value, "5.50")
                val toMove = addOpenItem(source.session.id.value, product.id.value, vat.id.value, "4.00")

                val service = GlobalContext.get().get<OrderItemService>()
                val updates =
                    runBlocking {
                        val out = observe()
                        // Move only the 4.00 item → the source session survives with 10.00 + 5.50.
                        service.transferOrderItems(
                            item = listOf(toMove),
                            employeeId = manager.id.value.toString(),
                            deviceId = deviceId,
                            targetTableId = target.id.value,
                            currentTableId = source.table.id.value,
                        )
                        out.drainTableStatusUpdates()
                    }

                val sourceCard =
                    assertNotNull(
                        updates.singleOrNull { it.tableId == source.table.id.value },
                        "the source keeps open items and its openAmount changed — it must be re-broadcast; " +
                            "got cards for ${updates.map { it.tableId }}",
                    )
                assertEquals("15.50", sourceCard.openAmount, "source card must carry the post-transfer total")

                val targetCard =
                    assertNotNull(
                        updates.singleOrNull { it.tableId == target.id.value },
                        "the target card is missing; got cards for ${updates.map { it.tableId }}",
                    )
                assertEquals("4.00", targetCard.openAmount)
            },
        ) { _ -> }

    @Test
    fun `transfer into an already-occupied table broadcasts both tables`() =
        testApp(
            setupData = {
                val manager = TestData.createManager(employeeNumber = "OA_MGR", username = "oa_mgr")
                val category = TestData.createCategory(name = "OA_Cat")
                val product = TestData.createProduct(categoryId = category.id.value, productName = "OA_Prod", productNumber = "OA_P")
                val vat = TestData.createDefaultVatRates().first()
                val source = TestData.createTableWithSession(tableNumber = "OA_TR_SR2")
                val target = TestData.createTableWithSession(tableNumber = "OA_TR_OCC")

                // Source: 10.00 + 5.50 + 4.00. Target already occupied with 7.00.
                addOpenItem(source.session.id.value, product.id.value, vat.id.value, "10.00")
                addOpenItem(source.session.id.value, product.id.value, vat.id.value, "5.50")
                val toMove = addOpenItem(source.session.id.value, product.id.value, vat.id.value, "4.00")
                addOpenItem(target.session.id.value, product.id.value, vat.id.value, "7.00")

                val service = GlobalContext.get().get<OrderItemService>()
                val updates =
                    runBlocking {
                        val out = observe()
                        service.transferOrderItems(
                            item = listOf(toMove),
                            employeeId = manager.id.value.toString(),
                            deviceId = deviceId,
                            targetTableId = target.table.id.value,
                            currentTableId = source.table.id.value,
                        )
                        out.drainTableStatusUpdates()
                    }

                val sourceCard =
                    assertNotNull(
                        updates.singleOrNull { it.tableId == source.table.id.value },
                        "source card missing; got cards for ${updates.map { it.tableId }}",
                    )
                assertEquals("15.50", sourceCard.openAmount)

                val targetCard =
                    assertNotNull(
                        updates.singleOrNull { it.tableId == target.table.id.value },
                        "an occupied target also changes its openAmount and must be broadcast; " +
                            "got cards for ${updates.map { it.tableId }}",
                    )
                assertEquals("11.00", targetCard.openAmount, "7.00 already there + the moved 4.00")
            },
        ) { _ -> }

    /**
     * The second exit of `transferInTransaction`: EVERY open item leaves, so the source session is
     * completed — and the target was ALREADY occupied. Both conditions must hold to reach the gap,
     * which is why neither neighbouring case caught it: a partial transfer returns at the first exit
     * (the test above), and a free target makes `getOrCreateSession` report a newly created session.
     *
     * Before the fix the target card was emitted only `if (targetSession.first)` — i.e. only when the
     * transfer OPENED the target — so an occupied target kept its pre-transfer total on every other
     * device (E2E-2026-09-16 F14). The target assertion below is the one that failed.
     */
    @Test
    fun `transfer of every open item into an occupied table broadcasts both tables`() =
        testApp(
            setupData = {
                val manager = TestData.createManager(employeeNumber = "OA_MGR", username = "oa_mgr")
                val category = TestData.createCategory(name = "OA_Cat")
                val product = TestData.createProduct(categoryId = category.id.value, productName = "OA_Prod", productNumber = "OA_P")
                val vat = TestData.createDefaultVatRates().first()
                val source = TestData.createTableWithSession(tableNumber = "OA_AT_SRC")
                val target = TestData.createTableWithSession(tableNumber = "OA_AT_OCC")

                // Source: 10.00 + 5.50 + 4.00 — all three move, emptying the source. Target holds 7.00.
                val first = addOpenItem(source.session.id.value, product.id.value, vat.id.value, "10.00")
                val second = addOpenItem(source.session.id.value, product.id.value, vat.id.value, "5.50")
                val third = addOpenItem(source.session.id.value, product.id.value, vat.id.value, "4.00")
                addOpenItem(target.session.id.value, product.id.value, vat.id.value, "7.00")

                val service = GlobalContext.get().get<OrderItemService>()
                val updates =
                    runBlocking {
                        val out = observe()
                        service.transferOrderItems(
                            item = listOf(first, second, third),
                            employeeId = manager.id.value.toString(),
                            deviceId = deviceId,
                            targetTableId = target.table.id.value,
                            currentTableId = source.table.id.value,
                        )
                        out.drainTableStatusUpdates()
                    }

                val sourceCard =
                    assertNotNull(
                        updates.singleOrNull { it.tableId == source.table.id.value },
                        "source card missing; got cards for ${updates.map { it.tableId }}",
                    )
                assertEquals(TableStatus.FREE.toString(), sourceCard.status, "every open item left, so the source is freed")
                assertNull(sourceCard.openAmount, "a freed table carries no open amount")

                val targetCard =
                    assertNotNull(
                        updates.singleOrNull { it.tableId == target.table.id.value },
                        "an occupied target absorbed every moved item and must be broadcast even though " +
                            "no new session was created for it; got cards for ${updates.map { it.tableId }}",
                    )
                assertEquals("26.50", targetCard.openAmount, "7.00 already there + the moved 19.50")
            },
        ) { _ -> }

    /**
     * The manager's "transfer table" action (`POST /api/tables/{id}/transfer`) — the whole-table
     * mirror of the per-item path above. It moves EVERY open item at once, so the target's
     * openAmount changes by the entire source total.
     *
     * Before the fix, `RTableServiceImpl.transferItemsToExistingSession` queued a card for the
     * source table only: the target card went stale on every other device until someone tapped
     * refresh. The target assertion below is the one that failed.
     */
    @Test
    fun `manager transfer of a whole table into an occupied table broadcasts both tables`() =
        testApp(
            setupData = {
                val managerNumber = "OA_MGR"
                val manager = TestData.createManager(employeeNumber = managerNumber, username = "oa_mgr")
                val category = TestData.createCategory(name = "OA_Cat")
                val product = TestData.createProduct(categoryId = category.id.value, productName = "OA_Prod", productNumber = "OA_P")
                val vat = TestData.createDefaultVatRates().first()
                val source = TestData.createTableWithSession(tableNumber = "OA_MT_SRC")
                val target = TestData.createTableWithSession(tableNumber = "OA_MT_OCC")

                // Source: 10.00 + 5.50 + 4.00 — all of it moves. Target already occupied with 7.00.
                addOpenItem(source.session.id.value, product.id.value, vat.id.value, "10.00")
                addOpenItem(source.session.id.value, product.id.value, vat.id.value, "5.50")
                addOpenItem(source.session.id.value, product.id.value, vat.id.value, "4.00")
                addOpenItem(target.session.id.value, product.id.value, vat.id.value, "7.00")

                val service = GlobalContext.get().get<RTableService>()
                val updates =
                    runBlocking {
                        val out = observe()
                        service.transferTable(
                            sourceTableId = source.table.id.value,
                            request =
                                RTableDto.TransferTableRequestDto(
                                    targetTableId = target.table.id.value,
                                    targetEmployeeNumber = managerNumber,
                                    reason = "guests moved to a bigger table",
                                ),
                            managerId = manager.id.value.toString(),
                        )
                        out.drainTableStatusUpdates()
                    }

                val sourceCard =
                    assertNotNull(
                        updates.singleOrNull { it.tableId == source.table.id.value },
                        "source card missing; got cards for ${updates.map { it.tableId }}",
                    )
                assertEquals(TableStatus.FREE.toString(), sourceCard.status, "every open item left, so the source is freed")
                assertNull(sourceCard.openAmount, "a freed table carries no open amount")

                val targetCard =
                    assertNotNull(
                        updates.singleOrNull { it.tableId == target.table.id.value },
                        "the target absorbed every moved item, so its card must be broadcast too; " +
                            "got cards for ${updates.map { it.tableId }}",
                    )
                assertEquals("26.50", targetCard.openAmount, "7.00 already there + the moved 19.50")
            },
        ) { _ -> }
}
