package org.example.project.features.order

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.features.audit.domain.models.TransactionAuditLog
import org.example.project.features.auth.data.TemporarySessionStore
import org.example.project.features.auth.domain.service.ApprovalRequestService
import org.example.project.features.employee.domain.models.Employee
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
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * A Storno a manager approved remotely must be audited as exactly that.
 *
 * A cashier without `ORDER_ITEM_CANCEL` gets a 202: the cancellation is parked, a manager approves
 * it on another device, and `DeferredActionExecutor` then runs it. The audit row it wrote said
 * `CANCEL_ORDER_ITEMS | K004 | DIRECT | approver null` (E2E-2026-09-20 round 2, R2-1) — asserting
 * the cashier acted on their own authority, the one thing the approval exists to rule out.
 * `ExceptionConverter` derived the type only from a temporary session on the device, and a remote
 * approval leaves none, so it fell through to `DIRECT`. The manual (kap-15) has always promised
 * `APPROVAL_REQUEST` with the manager's id and name.
 */
@DisplayName("Remote-approved Storno — audited as APPROVAL_REQUEST with the approving manager")
class ApprovedCancelAuditTest {
    private val deviceId = "approved-cancel-audit-device"
    private val requestId = "REQ-APPROVED-CANCEL-AUDIT"

    @AfterEach
    fun cleanUp() {
        TemporarySessionStore.removeRequest(requestId)
        TemporarySessionStore.removeDeferredAction(requestId)
    }

    @Test
    fun `a Storno executed after a manager's approval names that manager`() =
        testApp(
            setupData = {
                val f = fixture("ACA1")
                val manager = TestData.createManager(employeeNumber = "ACA1_MGR", username = "aca1_mgr")
                val managerId = manager.id.value.toString()

                // What requirePermissionOrDefer stores when it answers 202.
                TemporarySessionStore.addRequest(
                    TemporarySessionStore.PendingRequest(
                        requestId = requestId,
                        employeeId = f.cashierId,
                        deviceId = deviceId,
                        operation = "Cancel order items",
                        capabilities = setOf(Capability.ORDER_ITEM_CANCEL),
                        details = emptyMap(),
                        createdAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
                    ),
                )
                TemporarySessionStore.addDeferredAction(
                    TemporarySessionStore.DeferredAction(
                        requestId = requestId,
                        employeeId = f.cashierId,
                        deviceId = deviceId,
                        operation = "Cancel order items",
                        actionExecutor = {
                            GlobalContext.get().get<OrderItemService>().cancelOrderItems(
                                item = f.itemIds,
                                employeeId = f.cashierId,
                                deviceId = deviceId,
                                reason = "approved cancel",
                                sourceTableId = f.tableId,
                            )
                            "cancelled"
                        },
                    ),
                )

                // The manager approves on their own device — the path ApprovalRoutes takes.
                runBlocking {
                    GlobalContext.get().get<ApprovalRequestService>().approveRequest(requestId, managerId)
                }

                transaction {
                    val row = TransactionAuditLog.all().single { it.operation == "CANCEL_ORDER_ITEMS" }
                    assertEquals(f.cashierId, row.employeeId, "the cashier is still the actor")
                    assertEquals(
                        "APPROVAL_REQUEST",
                        row.authorizationType,
                        "a remote approval must not be recorded as the cashier's own authority",
                    )
                    assertEquals(managerId, row.authorizingManagerId, "the approving manager is named")
                    assertEquals(
                        Employee.findById(manager.id)!!.fullName,
                        row.authorizingManagerName,
                        "the name is resolved the same way a temporary session records it",
                    )
                }
            },
        ) { }

    @Test
    fun `a Storno by someone entitled to it stays DIRECT`() =
        testApp(
            setupData = {
                val f = fixture("ACA2")
                runBlocking {
                    GlobalContext.get().get<OrderItemService>().cancelOrderItems(
                        item = f.itemIds,
                        employeeId = f.cashierId,
                        deviceId = deviceId,
                        reason = "direct cancel",
                        sourceTableId = f.tableId,
                    )
                }

                transaction {
                    val row = TransactionAuditLog.all().single { it.operation == "CANCEL_ORDER_ITEMS" }
                    assertEquals("DIRECT", row.authorizationType)
                    assertNull(row.authorizingManagerId)
                    assertNull(row.authorizingManagerName)
                }
            },
        ) { }

    private class Fixture(
        val itemIds: List<Long>,
        val cashierId: String,
        val tableId: Long,
    )

    /** One open 4,00 € line on a fresh table, owned by a cashier, with this device registered. */
    private fun fixture(tag: String): Fixture {
        val cashier = TestData.createCashier(employeeNumber = "${tag}_CSH", username = "${tag.lowercase()}_csh")
        TestData.createShopInfo()
        val vats = TestData.createDefaultVatRates()
        val tws = TestData.createTableWithSession(tag)
        val category = TestData.createCategory("$tag Cat")
        val product =
            TestData.createProduct(
                categoryId = category.id.value,
                productName = "$tag Product",
                productNumber = tag,
                price = BigDecimal("4.0000"),
            )
        transaction {
            val now = currentTimeStamp()
            Terminal.new {
                this.deviceId = this@ApprovedCancelAuditTest.deviceId
                this.clientId = "TERM-$tag"
                this.registeredAt = now
                this.lastSeenAt = now
            }
        }
        val itemIds =
            transaction {
                val session = TableSession.findById(tws.session.id)!!
                session.employeeId = cashier
                listOf(
                    OrderItem
                        .new {
                            this.session = session
                            this.product = product
                            this.unitPrice = BigDecimal("4.00")
                            this.finalPrice = BigDecimal("4.00")
                            this.itemStatus = ItemStatus.OPEN
                            this.vat = vats.first()
                            this.discount = BigDecimal.ONE
                            this.customPrice = false
                            this.takeAway = false
                            this.transferred = false
                            this.sideNotes = null
                        }.id.value,
                )
            }
        return Fixture(itemIds, cashier.id.value.toString(), tws.table.id.value)
    }
}
