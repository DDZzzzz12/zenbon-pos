package org.example.project.repository

import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.data.repository.ItemTransferRepositoryImpl
import org.example.project.features.order.domain.models.ItemTransfer
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.table.domain.models.RTable
import org.example.project.features.table.domain.models.TableSession
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Repository test for [ItemTransferRepositoryImpl].
 *
 * Uses the project's H2-backed test app (testApp) to exercise real Exposed
 * persistence including the JSONB-emulated `order_item_ids` column.
 */
class ItemTransferRepositoryTest {
    private val repo = ItemTransferRepositoryImpl()

    private data class Fixture(
        val employee: Employee,
        val fromSession: TableSession,
        val toSession: TableSession,
        val items: List<OrderItem>,
    )

    /**
     * Creates an isolated transfer fixture: 2 tables, 2 sessions (one cashier each),
     * a product, a VAT, and [itemCount] OrderItems on the source session.
     *
     * Must be called inside a `transaction { }` block (or via testApp setupData).
     */
    private fun createFixture(
        suffix: String,
        itemCount: Int,
    ): Fixture {
        val employee =
            TestData.createCashier(
                employeeNumber = "EMP_$suffix",
                username = "emp_$suffix",
            )
        val from = TestData.createTable(tableNumber = "F_$suffix")
        val to = TestData.createTable(tableNumber = "T_$suffix")
        val category = TestData.createCategory(name = "Cat_$suffix")
        val product =
            TestData.createProduct(
                categoryId = category.id.value,
                productName = "Prod_$suffix",
                productNumber = "P_$suffix",
            )
        val vatList = TestData.createDefaultVatRates()
        val vat = vatList.first()

        return transaction {
            val fromSession = RTable.findById(from.id.value)!!.startSession(employee)
            val toSession = RTable.findById(to.id.value)!!.startSession(employee)

            val items =
                (1..itemCount).map {
                    OrderItem.new {
                        this.session = fromSession
                        this.product = product
                        this.unitPrice = BigDecimal("9.9900")
                        this.finalPrice = BigDecimal("9.9900")
                        this.itemStatus = ItemStatus.OPEN
                        this.vat = vat
                        this.discount = BigDecimal.ONE
                        this.customPrice = false
                        this.takeAway = false
                        this.transferred = false
                        this.sideNotes = null
                    }
                }

            Fixture(employee, fromSession, toSession, items)
        }
    }

    @Test
    fun `createTransferBatch writes one row with order_item_ids set and order_item_id null`() {
        var transferId: Long = -1
        var expectedIds: List<Long> = emptyList()

        testApp(
            setupData = {
                val fx = createFixture("BA", itemCount = 2)
                expectedIds = fx.items.map { it.id.value }
                transaction {
                    transferId =
                        repo
                            .createTransferBatch(
                                itemIds = expectedIds,
                                fromTable = fx.fromSession,
                                toTable = fx.toSession,
                                employee = fx.employee,
                            ).id.value
                }
                transaction {
                    val transfer = ItemTransfer.findById(transferId)!!
                    assertEquals(1L, ItemTransfer.all().count())
                    assertEquals(expectedIds, transfer.orderItemIds)
                    assertNull(transfer.orderItem)
                    assertEquals(expectedIds, transfer.transferredItemIds())
                }
            },
        ) { _ -> }
    }

    @Test
    fun `transferredItemIds returns single id for legacy-shape row`() {
        testApp(
            setupData = {
                val fx = createFixture("LG", itemCount = 1)
                val item = fx.items.single()

                val legacyId =
                    transaction {
                        ItemTransfer
                            .new {
                                this.employee = fx.employee
                                this.fromTableSession = fx.fromSession
                                this.toTableSession = fx.toSession
                                this.orderItem = item
                                this.orderItemIds = null
                                this.transferTime = currentTimeStamp()
                            }.id.value
                    }

                transaction {
                    val legacy = ItemTransfer.findById(legacyId)!!
                    assertEquals(listOf(item.id.value), legacy.transferredItemIds())
                    assertNull(legacy.orderItemIds)
                }
            },
        ) { _ -> }
    }

    @Test
    fun `createTransferBatch writes exactly one row regardless of item count`() {
        testApp(
            setupData = {
                val fx = createFixture("ML", itemCount = 5)
                val ids = fx.items.map { it.id.value }

                transaction {
                    repo.createTransferBatch(ids, fx.fromSession, fx.toSession, fx.employee)
                }

                transaction {
                    assertEquals(1L, ItemTransfer.all().count())
                    val only = ItemTransfer.all().single()
                    assertEquals(ids, only.orderItemIds)
                    assertNull(only.orderItem)
                    assertEquals(5, only.transferredItemIds().size)
                }
            },
        ) { _ -> }
    }

    @Test
    fun `getTransfersByTableSessionId returns events for both shapes`() {
        testApp(
            setupData = {
                val fx = createFixture("BS", itemCount = 1)
                val itemId =
                    fx.items
                        .single()
                        .id.value

                transaction {
                    repo.createTransferBatch(listOf(itemId), fx.fromSession, fx.toSession, fx.employee)
                }

                transaction {
                    val byFrom = repo.getTransfersByTableSessionId(fx.fromSession.id.value)
                    val byTo = repo.getTransfersByTableSessionId(fx.toSession.id.value)
                    assertEquals(1, byFrom.size)
                    assertEquals(1, byTo.size)
                    assertEquals(listOf(itemId), byFrom.first().transferredItemIds())
                }
            },
        ) { _ -> }
    }
}
