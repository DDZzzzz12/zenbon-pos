package org.example.project.service

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.order.ItemTransferHistoryDto
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.websocket.PendingBillingRequestStore
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
import org.example.project.features.terminal.domain.TerminalEmployee
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `requestBilling` records WHETHER the request came from a login-less order station — and, since the
 * attribution rule (OS-b, 2026-09-28), refuses the shared device account outright, so the flag is
 * false on every request that is still accepted.
 *
 * The client used to render every billing request as "Employee {number} requests billing", which on
 * the station path named the shared device account — text an operator reads as a colleague. The
 * client cannot decide this for itself: the notification carries only an employee NUMBER, and
 * [TerminalEmployee] documents why neither that number nor the `TERMINAL_DEVICE` role identifies the
 * account (the number is decoration nothing enforces; `V79` renamed an old per-person role onto the
 * role, so upgraded installs may hold real people carrying it). The username is the sound test, and
 * the server is the only side that holds it — hence the flag on the wire.
 */
@DisplayName("Billing request: station vs. person")
class RequestBillingStationFlagTest {
    /** Minimal stub — requestBilling never calls OrderItemService methods. */
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

    // The store is a process-wide singleton: a leftover request would be read as this test's own.
    @BeforeEach
    fun resetStore() = PendingBillingRequestStore.reset()

    @AfterEach
    fun clearStore() = PendingBillingRequestStore.reset()

    /**
     * OS-b, by attribution (owner, 2026-09-28): no bill is registered under the shared device account,
     * so it may not ask for one either — refused before anything is parked, which also means the
     * `requestedByStation` flag is no longer raised for a new request. A real person is not refused,
     * whichever device they use (the next test).
     */
    @Test
    fun `a request from the shared device account is refused and parks nothing`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            val tws = TestData.createTableWithSession(tableNumber = "BILLOS")
            val ownerId =
                transaction {
                    tws.session.employeeId.id.value
                        .toString()
                }
            val station = TestData.createTerminalDeviceEmployee()

            val refused =
                assertFailsWith<PosException> {
                    runBlocking { service.requestBilling(tws.table.id.value, station.id.toString()) }
                }
            assertEquals(HttpStatusCode.Forbidden, refused.httpStatus)
            assertTrue(PendingBillingRequestStore.getForEmployee(ownerId).isEmpty(), "a refused request must reach nobody")
        }

    @Test
    fun `a request from a real person is not flagged`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            val tws = TestData.createTableWithSession(tableNumber = "BILLPER")
            val ownerId =
                transaction {
                    tws.session.employeeId.id.value
                        .toString()
                }
            val manager = TestData.createManager()

            runBlocking { service.requestBilling(tws.table.id.value, manager.id.toString()) }

            val parked = PendingBillingRequestStore.getForEmployee(ownerId)
            assertEquals(1, parked.size, "the table owner must have exactly one parked request")
            assertFalse(
                parked.single().requestedByStation,
                "a colleague's request must keep naming the colleague",
            )
            assertEquals(manager.employeeNumber, parked.single().requestingEmployeeNumber)
        }
}
