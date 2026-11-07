package org.example.project.features.order

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.features.audit.domain.models.TransactionAuditLog
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.order.domain.service.OrderItemService
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cancelling and transferring ordered items must leave an audit row.
 *
 * `ExceptionConverter.shouldAuditOperation` has listed `CANCEL_ORDER_ITEMS` and
 * `TRANSFER_ORDER_ITEMS` among the critical operations since it was written, and
 * `OrderItemRoutes` documents that "the cancellation is recorded on the TSE and in the audit
 * trail" — but nothing in `features/order` ever called the wrapper that consults that list, so a
 * Storno, the most audit-worthy till operation there is, left `transaction_audit_log` untouched.
 * Found by the 2026-09-20 E2E run: a full session of ordering, cancelling and approving moved the
 * table by zero rows.
 *
 * The `operation` string at `OrderItemRoutes.kt:166` is the *approval-request* name passed to
 * `requirePermissionOrDefer`, not an audit write — easy to mistake for one.
 */
@DisplayName("Order-item cancel and transfer are audited")
class OrderItemAuditTest {
    private val deviceId = "order-audit-device"

    @Test
    fun `cancelling ordered items writes a CANCEL_ORDER_ITEMS audit row`() =
        testApp(
            setupData = {
                val f = fixture("OAUD1")
                runBlocking {
                    GlobalContext.get().get<OrderItemService>().cancelOrderItems(
                        item = f.itemIds,
                        employeeId = f.employeeId,
                        deviceId = deviceId,
                        reason = "audit test",
                        sourceTableId = f.tableId,
                    )
                }

                transaction {
                    val rows = TransactionAuditLog.all().filter { it.operation == "CANCEL_ORDER_ITEMS" }
                    assertTrue(
                        rows.isNotEmpty(),
                        "a Storno must leave an audit row — the route's KDoc promises one",
                    )
                    assertEquals(
                        f.employeeId,
                        rows.single().employeeId,
                        "the audit row names the employee who cancelled",
                    )
                }
            },
        ) { }

    @Test
    fun `transferring ordered items writes a TRANSFER_ORDER_ITEMS audit row`() =
        testApp(
            setupData = {
                val f = fixture("OAUD2")
                val target = TestData.createTable(tableNumber = "OAUD2T")
                runBlocking {
                    GlobalContext.get().get<OrderItemService>().transferOrderItems(
                        item = f.itemIds,
                        employeeId = f.employeeId,
                        deviceId = deviceId,
                        targetTableId = target.id.value,
                        currentTableId = f.tableId,
                    )
                }

                transaction {
                    val rows = TransactionAuditLog.all().filter { it.operation == "TRANSFER_ORDER_ITEMS" }
                    assertTrue(rows.isNotEmpty(), "an item transfer must leave an audit row")
                }
            },
        ) { }

    private class Fixture(
        val itemIds: List<Long>,
        val employeeId: String,
        val tableId: Long,
    )

    /** One open 4,00 € line on a fresh table, owned by a manager, with this device registered. */
    private fun fixture(tag: String): Fixture {
        val manager = TestData.createManager(employeeNumber = "${tag}_MGR", username = "${tag.lowercase()}_mgr")
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
                this.deviceId = this@OrderItemAuditTest.deviceId
                this.clientId = "TERM-$tag"
                this.registeredAt = now
                this.lastSeenAt = now
            }
        }
        val itemIds =
            transaction {
                val session = TableSession.findById(tws.session.id)!!
                session.employeeId = manager
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
        return Fixture(itemIds, manager.id.value.toString(), tws.table.id.value)
    }
}
