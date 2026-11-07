package org.example.project.core.websocket

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.features.bill.domain.service.BillService
import org.example.project.features.order.domain.models.OrderItem
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
 * A table card names the employee who owns the session, not whoever billed part of it.
 *
 * The partial-payment branch of `BillServiceImpl.createBillInternal` passed `employee = employee` —
 * the biller — to `createTableStatusUpdateDto`. With round-robin owners and a third employee (or a
 * manager) billing one table, every device relabelled the card with the biller until the next
 * authoritative fetch; the database owner never changed (QA observation 2026-10-31). The cancel
 * branch had the same defect and was fixed in `CancelBroadcastNamesSessionOwnerTest`.
 */
@DisplayName("Partial-bill broadcast — the table card names the session owner")
class BillBroadcastNamesSessionOwnerTest {
    private val deviceId = "bill-owner-test-device"

    @AfterEach
    fun tearDown() = NotificationHub.resetForTest()

    @Test
    fun `a partial bill by someone else keeps the session owner on the card`() =
        testApp(
            setupData = {
                val owner = TestData.createCashier(employeeNumber = "BO_OWNER", username = "bo_owner")
                val biller = TestData.createManager(employeeNumber = "BO_ACTOR", username = "bo_actor")
                val category = TestData.createCategory(name = "BO_Cat")
                val product = TestData.createProduct(categoryId = category.id.value, productName = "BO_Prod", productNumber = "BO_P")
                val vat = TestData.createDefaultVatRates().first()
                val cash = TestData.createPaymentType(name = "BO_Cash")
                val tws = TestData.createTableWithSession(tableNumber = "BO_TBL")
                transaction {
                    Terminal.new {
                        this.deviceId = this@BillBroadcastNamesSessionOwnerTest.deviceId
                        this.clientId = "BO-CLIENT"
                        this.isActive = true
                        this.registeredAt = currentTimeStamp()
                        this.lastSeenAt = currentTimeStamp()
                    }
                }

                // Two OPEN items, one billed: the session survives, which is the branch that broadcasts.
                val ids =
                    transaction {
                        val session = TableSession.findById(tws.session.id)!!
                        session.employeeId = owner
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
                    }

                val updates =
                    runBlocking {
                        val out = Channel<OutgoingFrame>(Channel.UNLIMITED)
                        NotificationHub.addSseConnection(connectionId = "obs", employeeId = "obs", outgoing = out)
                        GlobalContext.get().get<BillService>().createBill(
                            employeeId = biller.id.value.toString(),
                            deviceId = deviceId,
                            request =
                                BillDto.CreateBillRequestDto(
                                    tableSessionId = tws.session.id.value,
                                    orderItems = listOf(ids.first()),
                                    billAmount = "10.00",
                                    payments = listOf(PaymentDto.PaymentRequest(amount = "10.00", paymentTypeId = cash.id.value)),
                                ),
                        )
                        buildList {
                            while (true) {
                                val frame = out.tryReceive().getOrNull() ?: break
                                val msg = (frame as? OutgoingFrame.Event)?.event?.message
                                if (msg is NotificationDto.TableStatusUpdateDto) add(msg)
                            }
                        }
                    }

                assertEquals(
                    "BO_OWNER",
                    updates.single().employeeNumber,
                    "the card must keep naming the session's waiter, not the employee who billed",
                )
            },
        ) { _ -> }
}
