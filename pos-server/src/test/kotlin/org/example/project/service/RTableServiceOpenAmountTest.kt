package org.example.project.service

import kotlinx.coroutines.runBlocking
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.order.ItemTransferHistoryDto
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.features.employee.data.repository.EmployeeInfoRepositoryImpl
import org.example.project.features.employee.data.repository.EmployeeRepositoryImpl
import org.example.project.features.employee.data.repository.PermissionGroupRepositoryImpl
import org.example.project.features.order.data.repository.ItemTransferRepositoryImpl
import org.example.project.features.order.data.service.RotationAssignmentServiceImpl
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.order.domain.service.OrderItemService
import org.example.project.features.table.data.repository.CombinableTablesRepositoryImpl
import org.example.project.features.table.data.repository.RTableRepositoryImpl
import org.example.project.features.table.data.repository.TableSessionRepositoryImpl
import org.example.project.features.table.data.service.RTableServiceImpl
import org.example.project.features.table.data.service.TableSessionOwnerPolicyImpl
import org.example.project.features.table.domain.models.RTable
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Integration test for [RTableServiceImpl]'s open-amount enrichment on every table read.
 *
 * Verifies the per-table open amount aggregation: SUM(finalPrice) of order items
 * whose itemStatus == OPEN on the table's current session; null when free or <= 0.
 */
class RTableServiceOpenAmountTest {
    /** Minimal stub — getAllTables never calls OrderItemService methods. */
    private val stubOrderItemService =
        object : OrderItemService {
            override suspend fun getItemForTableSession(
                sessionId: Long,
                itemStatus: String?,
            ) = emptyList<OrderItemDto.OrderItemResponseDto>()

            override suspend fun cancelOrderItems(
                item: List<Long>,
                employeeId: String,
                deviceId: String,
                reason: String?,
                sourceTableId: Long,
            ) = OrderItemDto.OrderItemOperationResultDto(
                affectedItemsCount = 0,
                processedItemIds = emptyList(),
                operationType = "CANCEL",
                message = "stub",
            )

            override suspend fun transferOrderItems(
                item: List<Long>,
                employeeId: String,
                deviceId: String,
                targetTableId: Long,
                currentTableId: Long,
                reason: String?,
                ownerBasisEmployeeId: String,
            ) = OrderItemDto.OrderItemOperationResultDto(
                affectedItemsCount = 0,
                processedItemIds = emptyList(),
                operationType = "TRANSFER",
                message = "stub",
            )

            override suspend fun getCancellations(
                startDate: String,
                endDate: String,
            ) = emptyList<OrderItemDto.CancellationResponseDto>()

            override suspend fun getTransferHistory(tableSessionId: Long) = emptyList<ItemTransferHistoryDto.ItemTransferHistoryResponseDto>()

            override suspend fun getTransfers(
                startDate: String,
                endDate: String,
            ) = emptyList<ItemTransferHistoryDto.ItemTransferHistoryResponseDto>()
        }

    private val rotationService =
        RotationAssignmentServiceImpl(
            employeeRepository = EmployeeRepositoryImpl(),
            configManager = ShopConfigurationManager,
            permissionGroups = PermissionGroupRepositoryImpl(),
            employeeInfoRepository = EmployeeInfoRepositoryImpl(),
        )

    private val service =
        RTableServiceImpl(
            tableRepository = RTableRepositoryImpl(),
            tableSessionRepository = TableSessionRepositoryImpl(),
            transferRepository = ItemTransferRepositoryImpl(),
            orderItemService = stubOrderItemService,
            combinableTablesRepository = CombinableTablesRepositoryImpl(),
            ownerPolicy =
                TableSessionOwnerPolicyImpl(
                    configManager = ShopConfigurationManager,
                    rotationAssignmentService = rotationService,
                ),
        )

    /** Table ids of the three fixture tables, seeded by [seed]. */
    private data class Fixture(
        val freeTableId: Long,
        val occupiedTableId: Long,
        val paidOnlyTableId: Long,
    )

    /**
     * Occupied table: OPEN 10.00 + 5.50, PAID 3.00, CANCELLED 2.00 -> 15.50.
     * Paid-only table: single PAID item 7.00 -> null. Free table: no session -> null.
     */
    private fun seed(): Fixture {
        val employee = TestData.createCashier(employeeNumber = "OA_EMP", username = "oa_emp")
        val category = TestData.createCategory(name = "OA_Cat")
        val product =
            TestData.createProduct(
                categoryId = category.id.value,
                productName = "OA_Prod",
                productNumber = "OA_P",
            )
        val vat = TestData.createDefaultVatRates().first()

        val freeTable = TestData.createTable(tableNumber = "OA_FREE")
        val occupiedTable = TestData.createTable(tableNumber = "OA_OCC")
        val paidOnlyTable = TestData.createTable(tableNumber = "OA_PAID")

        val freeTableId = freeTable.id.value
        val occupiedTableId = occupiedTable.id.value
        val paidOnlyTableId = paidOnlyTable.id.value

        transaction {
            // Occupied table: OPEN 10.00 + 5.50, PAID 3.00, CANCELLED 2.00 -> 15.50
            val occupiedSession = RTable.findById(occupiedTable.id.value)!!.startSession(employee)

            fun newItem(
                price: String,
                status: ItemStatus,
            ) = OrderItem.new {
                this.session = occupiedSession
                this.product = product
                this.unitPrice = BigDecimal(price)
                this.finalPrice = BigDecimal(price)
                this.itemStatus = status
                this.vat = vat
                this.discount = BigDecimal.ONE
                this.customPrice = false
                this.takeAway = false
                this.transferred = false
                this.sideNotes = null
            }
            newItem("10.00", ItemStatus.OPEN)
            newItem("5.50", ItemStatus.OPEN)
            newItem("3.00", ItemStatus.PAID)
            newItem("2.00", ItemStatus.CANCELLED)

            // Paid-only table: single PAID item 7.00 -> null
            val paidSession = RTable.findById(paidOnlyTable.id.value)!!.startSession(employee)
            OrderItem.new {
                this.session = paidSession
                this.product = product
                this.unitPrice = BigDecimal("7.00")
                this.finalPrice = BigDecimal("7.00")
                this.itemStatus = ItemStatus.PAID
                this.vat = vat
                this.discount = BigDecimal.ONE
                this.customPrice = false
                this.takeAway = false
                this.transferred = false
                this.sideNotes = null
            }
        }
        return Fixture(freeTableId, occupiedTableId, paidOnlyTableId)
    }

    @Test
    fun `getAllTables computes openAmount per table`() {
        testApp(
            setupData = {
                val (freeTableId, occupiedTableId, paidOnlyTableId) = seed()
                runBlocking {
                    val result =
                        service.getAllTables(
                            limit = null,
                            offset = null,
                            status = null,
                            locationTypeId = null,
                            includeInactive = false,
                        )

                    val free = result.tables.first { it.id == freeTableId }
                    val occupied = result.tables.first { it.id == occupiedTableId }
                    val paidOnly = result.tables.first { it.id == paidOnlyTableId }

                    assertNull(free.openAmount, "free table has no session -> null")
                    assertEquals("15.50", occupied.openAmount, "sums only OPEN items (PAID and CANCELLED excluded)")
                    assertNull(paidOnly.openAmount, "PAID-only table -> null")
                }
            },
        ) { _ -> }
    }

    /**
     * e2e backlog: `GET /api/tables/{id}` answered `openAmount: null` for a table the list showed
     * with a balance — the single reads skipped the computation. Every read must agree with the list.
     */
    @Test
    fun `single-table and filtered reads carry the same openAmount as the list`() {
        testApp(
            setupData = {
                val (freeTableId, occupiedTableId, paidOnlyTableId) = seed()
                runBlocking {
                    assertEquals("15.50", service.getTableById(occupiedTableId).openAmount, "getTableById")
                    assertEquals("15.50", service.getTableByNumber("OA_OCC").openAmount, "getTableByNumber")
                    assertNull(service.getTableById(freeTableId).openAmount, "free table by id -> null")
                    assertNull(service.getTableById(paidOnlyTableId).openAmount, "PAID-only table by id -> null")

                    val occupiedStatus = TableStatus.valueOf(service.getTableById(occupiedTableId).tableStatus)
                    val byStatus = service.getTablesByStatus(occupiedStatus).first { it.id == occupiedTableId }
                    assertEquals("15.50", byStatus.openAmount, "getTablesByStatus")

                    val searched = service.searchTables("OA_OCC", 10, false).tables.first { it.id == occupiedTableId }
                    assertEquals("15.50", searched.openAmount, "searchTables")
                }
            },
        ) { _ -> }
    }
}
