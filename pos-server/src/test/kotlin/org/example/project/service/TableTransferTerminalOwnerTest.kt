package org.example.project.service

import kotlinx.coroutines.runBlocking
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.order.ItemTransferHistoryDto
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.dto.table.RTableDto
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.features.employee.data.repository.EmployeeInfoRepositoryImpl
import org.example.project.features.employee.data.repository.EmployeeRepositoryImpl
import org.example.project.features.employee.data.repository.PermissionGroupRepositoryImpl
import org.example.project.features.order.data.repository.ItemTransferRepositoryImpl
import org.example.project.features.order.data.service.RotationAssignmentServiceImpl
import org.example.project.features.order.domain.service.OrderItemService
import org.example.project.features.table.data.repository.CombinableTablesRepositoryImpl
import org.example.project.features.table.data.repository.RTableRepositoryImpl
import org.example.project.features.table.data.repository.TableSessionRepositoryImpl
import org.example.project.features.table.data.service.RTableServiceImpl
import org.example.project.features.table.data.service.TableSessionOwnerPolicyImpl
import org.example.project.features.table.domain.models.RTable
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Spec 2026-09-14 (station-table-session-ownership design): `RTableServiceImpl.transferTable` re-assigns
 * an EXISTING session, so it needs its own terminal-account guard even though §§4.1-4.2 already cover
 * every path that STARTS one. A manager must not be able to hand a table's session to the shared
 * order-station employee.
 */
class TableTransferTerminalOwnerTest {
    /** Minimal stub — transferTable never calls OrderItemService methods. */
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
                    rotationAssignmentService =
                        RotationAssignmentServiceImpl(
                            employeeRepository = EmployeeRepositoryImpl(),
                            configManager = ShopConfigurationManager,
                            permissionGroups = PermissionGroupRepositoryImpl(),
                            employeeInfoRepository = EmployeeInfoRepositoryImpl(),
                        ),
                ),
        )

    @Test
    fun `transferring a table's session to the terminal account is refused and the owner is unchanged`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            val terminal = TestData.createTerminalDeviceEmployee()
            val manager = TestData.createManager()
            val tws = TestData.createTableWithSession(tableNumber = "TRXFER")
            val originalOwnerId = transaction { tws.session.employeeId.id.value }

            val thrown =
                runCatching {
                    runBlocking {
                        service.transferTable(
                            sourceTableId = tws.table.id.value,
                            request =
                                RTableDto.TransferTableRequestDto(
                                    targetTableId = tws.table.id.value,
                                    targetEmployeeNumber = terminal.employeeNumber,
                                    reason = "Test",
                                ),
                            managerId = manager.id.toString(),
                        )
                    }
                }.exceptionOrNull()

            assertIs<PosException>(thrown)
            assertEquals(PosErrorCode.VALIDATION_FAILED, thrown.code)

            transaction {
                val currentOwnerId =
                    RTable
                        .findById(tws.table.id)!!
                        .currentSession!!
                        .employeeId.id.value
                assertEquals(originalOwnerId, currentOwnerId, "a refused transfer must leave the session's owner unchanged")
            }
        }

    @Test
    fun `transferring a session to a FREE table with the terminal account as target is refused and the session stays put`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            val terminal = TestData.createTerminalDeviceEmployee()
            val manager = TestData.createManager()
            val tws = TestData.createTableWithSession(tableNumber = "TRXFER")
            val freeTable = TestData.createTable(tableNumber = "TRXFREE")
            val originalOwnerId = transaction { tws.session.employeeId.id.value }
            val originalSessionId = transaction { tws.session.id.value }

            val thrown =
                runCatching {
                    runBlocking {
                        service.transferTable(
                            sourceTableId = tws.table.id.value,
                            request =
                                RTableDto.TransferTableRequestDto(
                                    targetTableId = freeTable.id.value,
                                    targetEmployeeNumber = terminal.employeeNumber,
                                    reason = "Test",
                                ),
                            managerId = manager.id.toString(),
                        )
                    }
                }.exceptionOrNull()

            assertIs<PosException>(thrown)
            assertEquals(PosErrorCode.VALIDATION_FAILED, thrown.code)

            transaction {
                val sourceSession = RTable.findById(tws.table.id)!!.currentSession
                assertEquals(originalSessionId, sourceSession?.id?.value, "the session must still be on the source table")
                assertEquals(
                    originalOwnerId,
                    sourceSession?.employeeId?.id?.value,
                    "a refused move-to-free-table transfer must leave the session's owner unchanged",
                )
            }
        }

    @Test
    fun `a reason of exactly 500 characters still transfers into an occupied table and the session notes stay within the column`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            val manager = TestData.createManager()
            val source = TestData.createTableWithSession(tableNumber = "TRSRC")
            val target = TestData.createTableWithSession(tableNumber = "TRTGT")
            TestData.createOrderItemInSession(source.session, ItemStatus.OPEN)
            val ownerNumber = transaction { target.session.employeeId.employeeNumber }
            val reason = "x".repeat(500)

            runBlocking {
                service.transferTable(
                    sourceTableId = source.table.id.value,
                    request =
                        RTableDto.TransferTableRequestDto(
                            targetTableId = target.table.id.value,
                            targetEmployeeNumber = ownerNumber,
                            reason = reason,
                        ),
                    managerId = manager.id.toString(),
                )
            }

            transaction {
                val note = RTable.findById(target.table.id)!!.currentSession!!.note
                assertEquals(500, note?.length, "the note is capped at the varchar(500) column, the reason itself is untouched")
            }
        }
}
