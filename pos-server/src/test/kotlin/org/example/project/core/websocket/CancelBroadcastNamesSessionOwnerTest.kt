package org.example.project.core.websocket

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.order.domain.service.OrderItemService
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * A table card names the waiter who owns the session, not whoever last touched it.
 *
 * The cancellation branch of `OrderItemServiceImpl` passed `employee = employee` — the acting
 * employee — to `createTableStatusUpdateDto`. Every device receiving the push then relabelled the
 * table with the canceller. Observed on 2026-09-20 across two devices: table 1's session belonged
 * to K002, cashier K004 cancelled one item, and the station showed `K004` until someone hit
 * refresh, while the acting till reverted to `K002` on its next authoritative fetch
 * (E2E-2026-09-20 #5).
 *
 * The transfer branch in the same file already draws the distinction and says so in
 * `openedTableCard`: *"It names the session's owner, who may not be the acting employee."* The
 * cancellation branch simply never got the same treatment.
 */
@DisplayName("Cancellation broadcast — the table card names the session owner")
class CancelBroadcastNamesSessionOwnerTest {
    private val deviceId = "cancel-owner-test-device"

    @AfterEach
    fun tearDown() = NotificationHub.resetForTest()

    @Test
    fun `cancelling an item as someone else keeps the session owner on the card`() =
        testApp(
            setupData = {
                val owner = TestData.createCashier(employeeNumber = "CO_OWNER", username = "co_owner")
                val canceller = TestData.createManager(employeeNumber = "CO_ACTOR", username = "co_actor")
                val category = TestData.createCategory(name = "CO_Cat")
                val product =
                    TestData.createProduct(
                        categoryId = category.id.value,
                        productName = "CO_Prod",
                        productNumber = "CO_P",
                        price = BigDecimal("10.0000"),
                    )
                val vat = TestData.createDefaultVatRates().first()
                val tws = TestData.createTableWithSession(tableNumber = "CO_TBL")
                transaction {
                    Terminal.new {
                        this.deviceId = this@CancelBroadcastNamesSessionOwnerTest.deviceId
                        this.clientId = "CO-CLIENT"
                        this.isActive = true
                        this.registeredAt = currentTimeStamp()
                        this.lastSeenAt = currentTimeStamp()
                    }
                }

                // The session belongs to the owner; two items so the table stays OCCUPIED after
                // one is cancelled — the branch that broadcasts a surviving session.
                val toCancel =
                    transaction {
                        val session = TableSession.findById(tws.session.id)!!
                        session.employeeId = owner
                        val ids =
                            (1..2).map {
                                OrderItem
                                    .new {
                                        this.session = session
                                        this.product = product
                                        this.unitPrice = BigDecimal("10.00")
                                        this.finalPrice = BigDecimal("10.00")
                                        this.itemStatus = ItemStatus.OPEN
                                        this.vat = vat
                                        this.discount = BigDecimal.ONE
                                        this.customPrice = false
                                        this.takeAway = false
                                        this.transferred = false
                                        this.sideNotes = null
                                    }.id.value
                            }
                        ids.first()
                    }

                val updates =
                    runBlocking {
                        val out = observe()
                        GlobalContext.get().get<OrderItemService>().cancelOrderItems(
                            item = listOf(toCancel),
                            employeeId = canceller.id.value.toString(),
                            deviceId = deviceId,
                            reason = "owner-label test",
                            sourceTableId = tws.table.id.value,
                        )
                        out.drainTableStatusUpdates()
                    }

                val update = updates.single()
                assertEquals(tws.table.id.value, update.tableId)
                assertEquals(
                    "CO_OWNER",
                    update.employeeNumber,
                    "the card must keep naming the session's waiter, not the employee who cancelled",
                )
            },
        ) { _ -> }

    private fun Channel<OutgoingFrame>.drainTableStatusUpdates(): List<NotificationDto.TableStatusUpdateDto> {
        val updates = mutableListOf<NotificationDto.TableStatusUpdateDto>()
        while (true) {
            val frame = tryReceive().getOrNull() ?: break
            val msg = (frame as? OutgoingFrame.Event)?.event?.message
            if (msg is NotificationDto.TableStatusUpdateDto) updates += msg
        }
        return updates
    }

    private suspend fun observe(): Channel<OutgoingFrame> {
        val out = Channel<OutgoingFrame>(Channel.UNLIMITED)
        NotificationHub.addSseConnection(connectionId = "obs", employeeId = "obs", outgoing = out)
        return out
    }
}
