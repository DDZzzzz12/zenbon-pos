package org.example.project.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.constants.WellKnownLocationTypes
import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.error.PosErrorCode
import org.example.project.core.websocket.NotificationHub
import org.example.project.core.websocket.OutgoingFrame
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.domain.service.OrderService
import org.example.project.features.order.domain.service.RotationAssignmentService
import org.example.project.features.table.domain.models.RTable
import org.example.project.features.table.domain.service.OwnerDecision
import org.example.project.features.table.domain.service.RTableService
import org.example.project.features.table.domain.service.TableSessionOwnerPolicy
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.dbQuery
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A table opening reports whether the rotation moved on between what the till showed
 * ([OrderDto.CreateOrderRequestDto.expectedNextEmployeeId]) and the pick that actually ran, and it publishes
 * the new "next" only after the opening transaction has committed.
 */
class RotationShiftedTest {
    private val deviceId = "rs-device"
    private var productId = 0L
    private lateinit var actorId: String
    private lateinit var anna: Employee
    private lateinit var ben: Employee

    @AfterEach
    fun tearDown() = NotificationHub.resetForTest()

    private fun orderService() = GlobalContext.get().get<OrderService>()

    private fun policy() = GlobalContext.get().get<TableSessionOwnerPolicy>()

    private fun rotation() = GlobalContext.get().get<RotationAssignmentService>()

    private fun poolCashier(number: String): Employee {
        val emp = TestData.createCashier(employeeNumber = number, username = "u_$number")
        transaction { Employee.findById(emp.id)!!.inRotationPool = true }
        return emp
    }

    /** Pool of two (Anna, Ben) in pool order, a manager actor outside the pool, round-robin on, cursor reset. */
    private suspend fun setupBase() {
        TestData.setTseMode(TseMode.SIMULATION)
        actorId =
            TestData
                .createManager(employeeNumber = "RS_MGR", username = "rs_mgr")
                .id.value
                .toString()
        val first = poolCashier("RS_A")
        val second = poolCashier("RS_B")
        val category = TestData.createCategory(name = "RS_Cat")
        productId =
            TestData
                .createProduct(
                    categoryId = category.id.value,
                    productName = "RS_Prod",
                    productNumber = "RS_P",
                    price = BigDecimal("5.0000"),
                ).id.value
        TestData.createDefaultVatRates()
        TestData.createFiscalRegisteredTerminal(deviceId = deviceId, clientId = "TERM-RS")
        TestData.setRoundRobinAssignment(true)
        // Name them by the rotation's own order, not by an assumption about how the pool sorts.
        val nextId = rotation().peekNext()!!.employeeId
        if (nextId == first.id.value.toString()) {
            anna = first
            ben = second
        } else {
            anna = second
            ben = first
        }
    }

    private fun order(
        tableId: Long,
        expectedNextEmployeeId: String?,
        forEmployeeNumber: String? = null,
    ) = OrderDto.CreateOrderRequestDto(
        tableInfoId = 0L,
        tableId = tableId,
        forEmployeeNumber = forEmployeeNumber,
        expectedNextEmployeeId = expectedNextEmployeeId,
        orderItems =
            listOf(
                OrderItemDto.CreateOrderItemRequestDto(productId = productId, unitPrice = "5.0", quantity = 1, discount = 1.0),
            ),
    )

    private suspend fun open(
        tableId: Long,
        expected: String?,
        actor: String = actorId,
    ): OrderDto.OrderResponseDto = orderService().createOrder(order(tableId, expected), actor, deviceId, actorCapabilities = emptySet())

    private fun ownerOf(tableId: Long): String =
        transaction {
            RTable
                .findById(tableId)!!
                .currentSession!!
                .employeeId.employeeNumber
        }

    private suspend fun decide(
        tableId: Long,
        actingId: UUID,
        forEmployeeNumber: String?,
        expected: String?,
    ): OwnerDecision =
        dbQuery {
            policy().ownerDecisionForNewSession(RTable.findById(tableId)!!, Employee.findById(actingId)!!, forEmployeeNumber, expected)
        }

    private suspend fun observe(): Channel<OutgoingFrame> {
        val out = Channel<OutgoingFrame>(Channel.UNLIMITED)
        NotificationHub.addSseConnection(connectionId = "rs-obs", employeeId = "rs-obs", outgoing = out)
        return out
    }

    private fun Channel<OutgoingFrame>.rotationUpdates(): List<NotificationDto.RotationNextUpdateDto> {
        val updates = mutableListOf<NotificationDto.RotationNextUpdateDto>()
        while (true) {
            val frame = tryReceive().getOrNull() ?: break
            val msg = (frame as? OutgoingFrame.Event)?.event?.message
            if (msg is NotificationDto.RotationNextUpdateDto) updates += msg
        }
        return updates
    }

    private val Employee.idString: String get() = id.value.toString()

    @Test
    fun matchingExpectationIsNotShifted() =
        testApp(setupData = { runBlocking { setupBase() } }) { _ ->
            val table = TestData.createTable("RS_T1").id.value

            val response = open(table, expected = anna.idString)

            assertFalse(response.rotationShifted)
            assertNull(response.rotationOwner)
            assertEquals(anna.employeeNumber, ownerOf(table))
        }

    @Test
    fun staleExpectationAssignsRealNextAndFlagsShift() =
        testApp(setupData = { runBlocking { setupBase() } }) { _ ->
            val table = TestData.createTable("RS_T2").id.value
            // The till still shows Anna, but another opening already gave her a table.
            ShopConfigurationManager.setOrderRotationCursor(anna.id.value)

            val response = open(table, expected = anna.idString)

            assertTrue(response.rotationShifted)
            assertEquals(ben.employeeNumber, response.rotationOwner?.employeeNumber)
            assertEquals(ben.idString, response.rotationOwner?.employeeId)
            assertEquals(ben.employeeNumber, ownerOf(table))
        }

    @Test
    fun noExpectationNeverFlags() =
        testApp(setupData = { runBlocking { setupBase() } }) { _ ->
            val table = TestData.createTable("RS_T3").id.value
            ShopConfigurationManager.setOrderRotationCursor(anna.id.value)

            val response = open(table, expected = null)

            assertFalse(response.rotationShifted)
            assertNull(response.rotationOwner)
            assertEquals(ben.employeeNumber, ownerOf(table))
        }

    @Test
    fun namedOwnerTakeawayVirtualAndOccupiedNeverRotateOrFlag() =
        testApp(setupData = { runBlocking { setupBase() } }) { _ ->
            val actor = UUID.fromString(actorId)
            val wrong = ben.idString // anna is next, so any rotation would count as shifted
            val normal = TestData.createTable("RS_N").id.value
            val takeaway = TestData.createTable("RS_TA", locationTypeId = WellKnownLocationTypes.TAKEAWAY).id.value
            val virtual = TestData.createTable("RS_V", locationTypeId = WellKnownLocationTypes.QUICK_SALE).id.value

            val named = decide(normal, actor, forEmployeeNumber = ben.employeeNumber, expected = wrong)
            assertEquals(OwnerDecision(named.employee, rotated = false, rotationShifted = false), named)
            assertEquals(ben.employeeNumber, named.employee.employeeNumber)

            val take = decide(takeaway, actor, forEmployeeNumber = null, expected = wrong)
            assertFalse(take.rotated)
            assertFalse(take.rotationShifted)
            assertEquals("RS_MGR", take.employee.employeeNumber)

            val virt = decide(virtual, actor, forEmployeeNumber = null, expected = wrong)
            assertFalse(virt.rotated)
            assertFalse(virt.rotationShifted)

            // Occupied: the session is reused, the policy is never consulted, nothing rotates or flags.
            val occupied = TestData.createTableWithSession(tableNumber = "RS_OCC")
            val out = observe()
            val response = open(occupied.table.id.value, expected = wrong)
            assertFalse(response.rotationShifted)
            assertNull(response.rotationOwner)
            assertNull(ShopConfigurationManager.peekOrderRotationCursor())
            assertTrue(out.rotationUpdates().isEmpty(), "reusing an open session must not publish")
        }

    @Test
    fun stationOpenRotatesAndReportsRotatedButNoShift() =
        testApp(setupData = { runBlocking { setupBase() } }) { _ ->
            val station = TestData.createTerminalDeviceEmployee().id.value
            val t1 = TestData.createTable("RS_S1").id.value
            val t2 = TestData.createTable("RS_S2").id.value

            val withoutExpectation = decide(t1, station, forEmployeeNumber = null, expected = null)
            assertTrue(withoutExpectation.rotated)
            assertFalse(withoutExpectation.rotationShifted)
            assertEquals(anna.employeeNumber, withoutExpectation.employee.employeeNumber)

            val matching = decide(t2, station, forEmployeeNumber = null, expected = ben.idString)
            assertTrue(matching.rotated)
            assertFalse(matching.rotationShifted)
            assertEquals(ben.employeeNumber, matching.employee.employeeNumber)
        }

    @Test
    fun stationOpenIgnoresAStaleExpectation() =
        testApp(setupData = { runBlocking { setupBase() } }) { _ ->
            val station = TestData.createTerminalDeviceEmployee().id.value
            val table = TestData.createTable("RS_S3").id.value

            // Anna is next, the till showed Ben: a person's open would report a shift, a station's never does.
            val decision = decide(table, station, forEmployeeNumber = null, expected = ben.idString)

            assertTrue(decision.rotated)
            assertFalse(decision.rotationShifted)
            assertEquals(anna.employeeNumber, decision.employee.employeeNumber)
        }

    @Test
    fun twoSimultaneousOpensBothExpectingTheSameEmployeeGetDistinctOwnersAndExactlyOneShift() =
        testApp(setupData = { runBlocking { setupBase() } }) { _ ->
            val t1 = TestData.createTable("RS_C1").id.value
            val t2 = TestData.createTable("RS_C2").id.value
            val expected = anna.idString

            val responses =
                withContext(Dispatchers.IO) {
                    listOf(async { open(t1, expected) }, async { open(t2, expected) }).awaitAll()
                }

            assertNotEquals(ownerOf(t1), ownerOf(t2), "both openings went to the same waiter")
            assertEquals(setOf(anna.employeeNumber, ben.employeeNumber), setOf(ownerOf(t1), ownerOf(t2)))
            assertEquals(1, responses.count { it.rotationShifted }, "exactly one opening lost the race")
            assertEquals(ben.employeeNumber, responses.single { it.rotationShifted }.rotationOwner?.employeeNumber)
        }

    @Test
    fun failedOpenRollsBackCursorAndPublishesNothing() =
        testApp(setupData = { runBlocking { setupBase() } }) { _ ->
            val trainee = TestData.createCashier(employeeNumber = "RS_TRN", username = "rs_trn")
            transaction { Employee.findById(trainee.id)!!.trainingMode = true }
            val table = TestData.createTable("RS_F").id.value
            val cursorBefore = ShopConfigurationManager.peekOrderRotationCursor()
            val out = observe()

            val thrown = runCatching { open(table, expected = anna.idString, actor = trainee.idString) }.exceptionOrNull()

            assertEquals(PosErrorCode.TRAINING_MODE_MISMATCH, assertIs<PosException>(thrown).code)
            assertEquals(cursorBefore, ShopConfigurationManager.peekOrderRotationCursor(), "the pick must roll back with the open")
            assertEquals(anna.idString, rotation().peekNext()?.employeeId)
            assertTrue(out.rotationUpdates().isEmpty(), "a failed open must not publish")
        }

    @Test
    fun successfulRotatedOpenPublishesOnce() =
        testApp(setupData = { runBlocking { setupBase() } }) { _ ->
            val table = TestData.createTable("RS_P1").id.value
            val out = observe()

            open(table, expected = anna.idString)

            val updates = out.rotationUpdates()
            assertEquals(1, updates.size)
            assertEquals(ben.employeeNumber, updates.single().next?.employeeNumber)
        }

    @Test
    fun legacySessionOpenPublishesOnlyWhenRotated() =
        testApp(setupData = { runBlocking { setupBase() } }) { _ ->
            val tables = GlobalContext.get().get<RTableService>()
            val rotated = TestData.createTable("RS_L1").id.value
            val named = TestData.createTable("RS_L2").id.value
            val out = observe()

            tables.getOrCreateSessionByTableId(named, actorId, forEmployeeNumber = ben.employeeNumber)
            assertTrue(out.rotationUpdates().isEmpty(), "a named owner does not rotate, so nothing is published")

            tables.getOrCreateSessionByTableId(rotated, actorId, forEmployeeNumber = null)
            val updates = out.rotationUpdates()
            assertEquals(1, updates.size)
            assertEquals(ben.employeeNumber, updates.single().next?.employeeNumber)
        }
}
