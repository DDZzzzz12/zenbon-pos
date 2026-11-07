package org.example.project.service

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import org.example.project.features.table.database.tables.TableSessionTable
import org.example.project.features.table.domain.models.TableSession
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

class RTableServiceVirtualRotationTest {
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

    /**
     * A virtual table (QUICK_SALE / VOUCHER) refuses to open a session at all since E2E-2026-09-18
     * #4: such a session is unreachable from any floor plan and wedges the Z-Bon. The rotation
     * invariant this test was written for still holds — the refusal must not burn a pool employee,
     * so the cursor stays where it was.
     */
    @Test
    fun `virtual table refuses to open a session and does not advance the rotation cursor`() {
        testApp(
            setupData = {
                val configRepo = ConfigurationRepository()
                seedPoolEmployee("001")
                seedPoolEmployee("002")
                val caller = TestData.createCashier(employeeNumber = "999", username = "caller")
                // QUICK_SALE is a virtual location type (isVirtual = true)
                val virtualTable =
                    TestData.createTable(
                        tableNumber = "QS1",
                        seats = 0,
                        locationTypeId = WellKnownLocationTypes.QUICK_SALE,
                    )

                runBlocking {
                    enableRoundRobin(configRepo)

                    val refusal =
                        assertFailsWith<PosException> {
                            service.getOrCreateSessionByTableId(
                                virtualTable.id.value,
                                caller.id.toString(),
                                forEmployeeNumber = null,
                            )
                        }

                    assertEquals(PosErrorCode.TABLE_VIRTUAL_NOT_ORDERABLE, refusal.code)
                    // Cursor untouched — a refused open must not consume a pool employee.
                    assertEquals(null, ShopConfigurationManager.getOrderRotationCursor())
                }
            },
        ) { _ -> }
    }

    @Test
    fun `non-virtual table with round-robin still picks rotation employee`() {
        testApp(
            setupData = {
                val configRepo = ConfigurationRepository()
                val emp1 = seedPoolEmployee("001")
                seedPoolEmployee("002")
                val caller = TestData.createCashier(employeeNumber = "999", username = "caller")
                val table = TestData.createTable(tableNumber = "T_INDOOR") // INDOOR = non-virtual

                runBlocking {
                    enableRoundRobin(configRepo)
                    val resp =
                        service.getOrCreateSessionByTableId(
                            table.id.value,
                            caller.id.toString(),
                            forEmployeeNumber = null,
                        )
                    assertEquals(emp1.id.toString(), resp.employeeId)
                    assertEquals(emp1.id.value, ShopConfigurationManager.getOrderRotationCursor())
                }
            },
        ) { _ -> }
    }

    @Test
    fun `concurrent opens of the same free table create exactly one session`() {
        testApp(
            setupData = {
                val configRepo = ConfigurationRepository()
                val emp1 = seedPoolEmployee("001")
                seedPoolEmployee("002")
                val caller = TestData.createCashier(employeeNumber = "999", username = "caller")
                val table = TestData.createTable(tableNumber = "RACE_T")

                runBlocking {
                    enableRoundRobin(configRepo)

                    // Fire several concurrent opens of the SAME free table.
                    // On Postgres the r_table FOR UPDATE lock serializes these; on H2 (test
                    // engine) row locking holds the same invariant for this access pattern.
                    val results =
                        coroutineScope {
                            (1..5)
                                .map {
                                    async {
                                        service.getOrCreateSessionByTableId(
                                            table.id.value,
                                            caller.id.toString(),
                                            forEmployeeNumber = null,
                                        )
                                    }
                                }.awaitAll()
                        }

                    val activeCount =
                        transaction {
                            TableSession
                                .find {
                                    (TableSessionTable.table eq table.id.value) and
                                        (TableSessionTable.completed eq false)
                                }.count()
                        }
                    assertEquals(1L, activeCount)
                    // All callers get the same session id
                    assertEquals(1, results.map { it.id }.toSet().size)
                    // Cursor advanced exactly once (to the first pool employee)
                    assertEquals(emp1.id.value, ShopConfigurationManager.getOrderRotationCursor())
                }
            },
        ) { _ -> }
    }
}
