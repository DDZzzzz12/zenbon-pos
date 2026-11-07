package org.example.project.features.order

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.constants.WellKnownLocationTypes
import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.error.PosErrorCode
import org.example.project.features.order.domain.service.OrderService
import org.example.project.features.table.database.tables.TableSessionTable
import org.example.project.features.table.domain.models.RTable
import org.example.project.features.table.domain.models.TableSession
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 2026-09-18 e2e finding #3: a free table is opened ONLY by its first order, in the transaction that
 * books the items, with the explicit-open owner rule (the named waiter, else rotation, else the actor).
 */
class FirstOrderOpensTableTest {
    private val deviceId = "fot-device"
    private var managerId = ""
    private var productId = 0L
    private var tableId = 0L

    private fun setupBase() {
        TestData.setTseMode(TseMode.SIMULATION)
        runBlocking { TestData.setRoundRobinAssignment(false) }
        managerId =
            TestData
                .createManager(employeeNumber = "FOT_MGR", username = "fot_mgr")
                .id.value
                .toString()
        TestData.createCashier(employeeNumber = "FOT_W2", username = "fot_w2")
        val category = TestData.createCategory(name = "FOT_Cat")
        productId =
            TestData
                .createProduct(
                    categoryId = category.id.value,
                    productName = "FOT_Prod",
                    productNumber = "FOT_P",
                    price = BigDecimal("5.0000"),
                ).id.value
        TestData.createDefaultVatRates()
        TestData.createFiscalRegisteredTerminal(deviceId = deviceId, clientId = "TERM-FOT")
    }

    private fun order(
        tableId: Long,
        productId: Long,
        forEmployeeNumber: String? = null,
    ) = OrderDto.CreateOrderRequestDto(
        tableInfoId = 0L, // placeholder: ignored when tableId is set
        tableId = tableId,
        forEmployeeNumber = forEmployeeNumber,
        orderItems =
            listOf(
                OrderItemDto.CreateOrderItemRequestDto(productId = productId, unitPrice = "5.0", quantity = 1, discount = 1.0),
            ),
    )

    private fun service() = GlobalContext.get().get<OrderService>()

    @Test
    fun `the first order on a free table opens it, owned by the named waiter`() =
        testApp(setupData = {
            setupBase()
            tableId = TestData.createTable("FOT1").id.value
        }) { _ ->
            runBlocking { service().createOrder(order(tableId, productId, "FOT_W2"), managerId, deviceId, actorCapabilities = emptySet()) }

            transaction {
                val table = RTable.findById(tableId)!!
                assertEquals(TableStatus.OCCUPIED, table.tableStatus)
                assertEquals("FOT_W2", table.currentSession!!.employeeId.employeeNumber)
            }
        }

    @Test
    fun `an order that fails inside the transaction leaves the table FREE and no session behind`() =
        testApp(setupData = {
            setupBase()
            tableId = TestData.createTable("FOT2").id.value
        }) { _ ->
            assertFailsWith<PosException> {
                runBlocking {
                    service().createOrder(
                        order(tableId, productId = 999_999L),
                        managerId,
                        deviceId,
                        actorCapabilities = emptySet(),
                    )
                }
            }

            transaction {
                assertEquals(TableStatus.FREE, RTable.findById(tableId)!!.tableStatus)
                assertTrue(TableSession.find { TableSessionTable.table eq tableId }.empty())
            }
            Unit
        }

    @Test
    fun `a virtual non-takeaway table is never opened by an order`() =
        testApp(setupData = {
            setupBase()
            tableId = TestData.createTable("FOT_QS", locationTypeId = WellKnownLocationTypes.QUICK_SALE).id.value
        }) { _ ->
            val ex =
                assertFailsWith<PosException> {
                    runBlocking { service().createOrder(order(tableId, productId), managerId, deviceId, actorCapabilities = emptySet()) }
                }

            assertEquals(PosErrorCode.TABLE_VIRTUAL_NOT_ORDERABLE, ex.code)
            transaction { assertTrue(TableSession.find { TableSessionTable.table eq tableId }.empty()) }
            Unit
        }
}
