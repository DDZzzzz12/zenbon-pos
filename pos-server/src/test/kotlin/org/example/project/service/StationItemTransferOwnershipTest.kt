package org.example.project.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.constants.WellKnownLocationTypes
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.order.domain.service.OrderItemService
import org.example.project.features.payment.domain.models.Vat
import org.example.project.features.product.domain.models.Product
import org.example.project.features.table.database.tables.TableSessionTable
import org.example.project.features.table.domain.models.TableSession
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Item transfer is the third path that can OPEN a table session (spec 2026-09-14). An order station
 * moving items onto a free table never owns the session that opens: the rotation owns it, or the
 * transfer is refused and every item stays on its source table.
 *
 * Every leg that turns round-robin on runs inside [TestData.withRoundRobinAssignment], which resets the
 * process-wide flag in a `finally`.
 */
@DisplayName("Order station item transfer: session ownership")
class StationItemTransferOwnershipTest {
    private fun service(): OrderItemService = GlobalContext.get().get()

    private fun poolCashier(
        number: String,
        training: Boolean = false,
    ): Employee {
        val emp = TestData.createCashier(employeeNumber = number, username = "u_$number")
        return transaction {
            Employee.findById(emp.id)!!.apply {
                inRotationPool = true
                trainingMode = training
            }
        }
    }

    /** A table with an open (non-training) session holding [count] OPEN items: its id and the item ids. */
    private fun sourceWithItems(
        tableNumber: String,
        count: Int,
    ): Pair<Long, List<Long>> {
        val category = TestData.createCategory(name = "SIT_Cat")
        val product = TestData.createProduct(categoryId = category.id.value, productName = "SIT_Prod", productNumber = "SIT_P")
        val vat = TestData.createDefaultVatRates().first()
        val tws = TestData.createTableWithSession(tableNumber = tableNumber)
        val itemIds =
            transaction {
                (1..count).map {
                    OrderItem
                        .new {
                            this.session = TableSession.findById(tws.session.id)!!
                            this.product = Product.findById(product.id)!!
                            this.unitPrice = BigDecimal("9.99")
                            this.finalPrice = BigDecimal("9.99")
                            this.itemStatus = ItemStatus.OPEN
                            this.vat = Vat.findById(vat.id)!!
                            this.discount = BigDecimal.ONE
                            this.customPrice = false
                            this.takeAway = false
                            this.transferred = false
                            this.sideNotes = null
                        }.id.value
                }
            }
        return tws.table.id.value to itemIds
    }

    private suspend fun transfer(
        station: Employee,
        items: List<Long>,
        from: Long,
        to: Long,
    ) = service().transferOrderItems(
        item = items,
        employeeId = station.id.value.toString(),
        deviceId = "sit-station",
        targetTableId = to,
        currentTableId = from,
    )

    private suspend fun refusal(block: suspend () -> Unit): PosException = assertIs<PosException>(runCatching { block() }.exceptionOrNull())

    /** Employee number owning the table's ACTIVE session, or null while it has none. */
    private fun activeOwnerNumber(tableId: Long): String? =
        transaction {
            TableSession
                .find { (TableSessionTable.table eq tableId) and (TableSessionTable.completed eq false) }
                .firstOrNull()
                ?.employeeId
                ?.employeeNumber
        }

    /** The tables the given items' sessions belong to. */
    private fun tableIdsOf(items: List<Long>): Set<Long> =
        transaction {
            items
                .map {
                    OrderItem
                        .findById(it)!!
                        .session.table.id.value
                }.toSet()
        }

    @Test
    fun `a station transfer to a free table opens a session owned by the rotated employee`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            val station = TestData.createTerminalDeviceEmployee()
            poolCashier("RR001")
            val (source, items) = sourceWithItems("SIT_SRC1", count = 2)
            val target = TestData.createTable(tableNumber = "SIT_TGT1")

            TestData.withRoundRobinAssignment(true) {
                transfer(station, items, from = source, to = target.id.value)
            }

            assertEquals("RR001", activeOwnerNumber(target.id.value))
            assertEquals(setOf(target.id.value), tableIdsOf(items))
            assertNull(activeOwnerNumber(source), "moving every item completes the source session")
        }

    @Test
    fun `a station transfer never opens a takeaway table and leaves the items on the source`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            val station = TestData.createTerminalDeviceEmployee()
            poolCashier("RR001")
            val (source, items) = sourceWithItems("SIT_SRC2", count = 1)
            val takeaway = TestData.createTable(tableNumber = "SIT_TAKE", locationTypeId = WellKnownLocationTypes.TAKEAWAY)

            TestData.withRoundRobinAssignment(true) {
                val refused = refusal { transfer(station, items, from = source, to = takeaway.id.value) }
                assertEquals(PosErrorCode.STATION_TABLE_VIRTUAL, refused.code)
                assertEquals(HttpStatusCode.Conflict, refused.httpStatus)
            }

            assertNull(activeOwnerNumber(takeaway.id.value), "a refused transfer must leave the takeaway table free")
            assertEquals(setOf(source), tableIdsOf(items))
        }

    @Test
    fun `a station transfer to an already-open table keeps its owner and does not move the rotation`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            val station = TestData.createTerminalDeviceEmployee()
            poolCashier("RR001")
            val (source, items) = sourceWithItems("SIT_SRC3", count = 2)
            val target = TestData.createTableWithSession(tableNumber = "SIT_OPEN")
            val moved = items.take(1)

            TestData.withRoundRobinAssignment(true) {
                transfer(station, moved, from = source, to = target.table.id.value)
                assertNull(ShopConfigurationManager.getOrderRotationCursor(), "reusing an open session never picks from the rotation")
            }

            assertEquals("TBL_SIT_OPEN", activeOwnerNumber(target.table.id.value))
            assertEquals(setOf(target.table.id.value), tableIdsOf(moved))
        }

    @Test
    fun `a station transfer to a free table is refused when the rotated owner is in training mode`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            val station = TestData.createTerminalDeviceEmployee()
            poolCashier("RR_TRN", training = true)
            val (source, items) = sourceWithItems("SIT_SRC4", count = 1)
            val target = TestData.createTable(tableNumber = "SIT_TGT4")

            TestData.withRoundRobinAssignment(true) {
                val refused = refusal { transfer(station, items, from = source, to = target.id.value) }
                assertEquals(PosErrorCode.TRAINING_MODE_MISMATCH, refused.code)
                assertEquals(HttpStatusCode.Conflict, refused.httpStatus)
            }

            assertNull(activeOwnerNumber(target.id.value), "real items must never open a training session")
            assertEquals(setOf(source), tableIdsOf(items))
        }

    @Test
    fun `a manager lent to the station does not become the owner of the table the transfer opens`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            val station = TestData.createTerminalDeviceEmployee()
            val manager = TestData.createManager()
            poolCashier("RR001")
            val (source, items) = sourceWithItems("SIT_SRC5", count = 1)
            val target = TestData.createTable(tableNumber = "SIT_TGT5")

            TestData.withRoundRobinAssignment(true) {
                // The route hands the service the LENT manager as the actor and the station as whoever asked.
                service().transferOrderItems(
                    item = items,
                    employeeId = manager.id.value.toString(),
                    deviceId = "sit-station",
                    targetTableId = target.id.value,
                    currentTableId = source,
                    ownerBasisEmployeeId = station.id.value.toString(),
                )
            }

            assertEquals("RR001", activeOwnerNumber(target.id.value), "the rotation, not the approving manager, owns the new session")
        }

    @Test
    fun `a manager in the rotation pool may be picked as the owner by the rotation`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            val station = TestData.createTerminalDeviceEmployee()
            val manager = TestData.createManager()
            transaction { Employee.findById(manager.id)!!.inRotationPool = true }
            val (source, items) = sourceWithItems("SIT_SRC6", count = 1)
            val target = TestData.createTable(tableNumber = "SIT_TGT6")

            TestData.withRoundRobinAssignment(true) {
                service().transferOrderItems(
                    item = items,
                    employeeId = manager.id.value.toString(),
                    deviceId = "sit-station",
                    targetTableId = target.id.value,
                    currentTableId = source,
                    ownerBasisEmployeeId = station.id.value.toString(),
                )
            }

            assertEquals(manager.employeeNumber, activeOwnerNumber(target.id.value))
        }
}
