package org.example.project.service

import kotlinx.coroutines.runBlocking
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.constants.WellKnownLocationTypes
import org.example.project.core.domain.dto.order.ItemTransferHistoryDto
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.employee.data.repository.EmployeeInfoRepositoryImpl
import org.example.project.features.employee.data.repository.EmployeeRepositoryImpl
import org.example.project.features.employee.data.repository.PermissionGroupRepositoryImpl
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.data.repository.ItemTransferRepositoryImpl
import org.example.project.features.order.data.service.RotationAssignmentServiceImpl
import org.example.project.features.order.domain.service.OrderItemService
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.database.tables.ConfigKey
import org.example.project.features.table.data.repository.CombinableTablesRepositoryImpl
import org.example.project.features.table.data.repository.RTableRepositoryImpl
import org.example.project.features.table.data.repository.TableSessionRepositoryImpl
import org.example.project.features.table.data.service.RTableServiceImpl
import org.example.project.features.table.data.service.TableSessionOwnerPolicyImpl
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.test.assertIs
import kotlin.test.assertNull

class RTableServiceRoundRobinTest {
    /** Minimal stub — the round-robin path never calls OrderItemService methods. */
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

    private fun seedPoolEmployee(number: String): Employee {
        val emp = TestData.createCashier(employeeNumber = number, username = "u_$number")
        return transaction {
            val refreshed = Employee.findById(emp.id)!!
            refreshed.inRotationPool = true
            refreshed
        }
    }

    private suspend fun enableRoundRobin(configRepo: ConfigurationRepository) {
        val shop = TestData.createShopInfo()
        @Suppress("DEPRECATION")
        newSuspendedTransaction {
            configRepo.setSetting(
                shopId = shop.id.value,
                configKey = ConfigKey.ORDER_RULES,
                value = """{"employeeSelectionOnFreeTable":false,"roundRobinAssignment":true}""",
            )
        }
        ShopConfigurationManager.setOrderRotationCursor(null)
        ShopConfigurationManager.refreshConfiguration()
    }

    @Test
    fun `auto-assigns rotation pool employee in turn when round-robin is on`() {
        testApp(
            setupData = {
                val configRepo = ConfigurationRepository()
                val emp1 = seedPoolEmployee("001")
                val emp2 = seedPoolEmployee("002")
                val callerId = TestData.createCashier(employeeNumber = "999", username = "caller").id.toString()
                val table1 = TestData.createTable(tableNumber = "T1")
                val table2 = TestData.createTable(tableNumber = "T2")
                val table3 = TestData.createTable(tableNumber = "T3")

                runBlocking {
                    enableRoundRobin(configRepo)

                    val r1 = service.getOrCreateSessionByTableId(table1.id.value, callerId, forEmployeeNumber = null)
                    val r2 = service.getOrCreateSessionByTableId(table2.id.value, callerId, forEmployeeNumber = null)
                    val r3 = service.getOrCreateSessionByTableId(table3.id.value, callerId, forEmployeeNumber = null)

                    assertEquals(emp1.id.toString(), r1.employeeId)
                    assertEquals(emp2.id.toString(), r2.employeeId)
                    assertEquals(emp1.id.toString(), r3.employeeId) // wraps
                }
            },
        ) { _ -> }
    }

    @Test
    fun `forEmployeeNumber overrides round-robin`() {
        testApp(
            setupData = {
                val configRepo = ConfigurationRepository()
                seedPoolEmployee("001")
                val emp2 = seedPoolEmployee("002")
                val callerId = TestData.createCashier(employeeNumber = "999", username = "caller").id.toString()
                val table = TestData.createTable(tableNumber = "T_OVR")

                runBlocking {
                    enableRoundRobin(configRepo)
                    val resp = service.getOrCreateSessionByTableId(table.id.value, callerId, forEmployeeNumber = "002")
                    assertEquals(emp2.id.toString(), resp.employeeId)
                }
            },
        ) { _ -> }
    }

    @Test
    fun `an order station never opens a takeaway table, even with round-robin on`() {
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            seedPoolEmployee("001")
            val station = TestData.createTerminalDeviceEmployee()
            val takeaway = TestData.createTable(tableNumber = "T_STA_TAKE", locationTypeId = WellKnownLocationTypes.TAKEAWAY)

            TestData.withRoundRobinAssignment(true) {
                val thrown =
                    runCatching {
                        service.getOrCreateSessionByTableId(takeaway.id.value, station.id.value.toString(), forEmployeeNumber = null)
                    }.exceptionOrNull()

                assertEquals(PosErrorCode.STATION_TABLE_VIRTUAL, assertIs<PosException>(thrown).code)
                assertNull(ShopConfigurationManager.getOrderRotationCursor())
            }
        }
    }

    @Test
    fun `an order station cannot name the owner of a free table`() {
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            seedPoolEmployee("001")
            val station = TestData.createTerminalDeviceEmployee()
            val table = TestData.createTable(tableNumber = "T_STA_NAME")

            TestData.withRoundRobinAssignment(true) {
                val thrown =
                    runCatching {
                        service.getOrCreateSessionByTableId(table.id.value, station.id.value.toString(), forEmployeeNumber = "001")
                    }.exceptionOrNull()

                assertEquals(PosErrorCode.STATION_TABLE_REQUIRES_ROTATION, assertIs<PosException>(thrown).code)
                assertNull(ShopConfigurationManager.getOrderRotationCursor())
            }
        }
    }

    @Test
    fun `a station reuses an already-open takeaway session without touching rotation`() {
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            seedPoolEmployee("001")
            val station = TestData.createTerminalDeviceEmployee()
            val tws =
                TestData.createTableWithSession(
                    tableNumber = "T_STA_OPEN",
                    locationTypeId = WellKnownLocationTypes.TAKEAWAY,
                )
            val expectedOwnerId = transaction { tws.session.employeeId.id.value }

            TestData.withRoundRobinAssignment(true) {
                val resp =
                    service.getOrCreateSessionByTableId(
                        tws.table.id.value,
                        station.id.value.toString(),
                        forEmployeeNumber = null,
                    )

                assertEquals(tws.session.id.value, resp.id)
                assertEquals(expectedOwnerId.toString(), resp.employeeId)
                assertNull(ShopConfigurationManager.getOrderRotationCursor())
            }
        }
    }
}
