package org.example.project.features.employee

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.encodeToString
import org.example.project.core.domain.dto.employee.EmployeeDto
import org.example.project.core.domain.dto.rotation.RotationDto
import org.example.project.core.domain.dto.setup.ShopConfigurationDto
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmploymentStatus
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.websocket.NotificationHub
import org.example.project.core.websocket.OutgoingFrame
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.employee.domain.service.EmployeeService
import org.example.project.features.order.data.service.RotationNextNotifier
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.order.domain.service.OrderItemService
import org.example.project.features.order.domain.service.RotationAssignmentService
import org.example.project.features.payment.domain.models.Vat
import org.example.project.features.product.domain.models.Product
import org.example.project.features.table.domain.models.TableSession
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every change that moves "who is next" publishes it, after the change has committed: the pool flag, an
 * employee leaving ACTIVE, the round-robin toggle, and a station's item transfer that opens a free table.
 * A change that cannot move it (a notes edit) publishes nothing.
 */
class RotationPublishTriggersTest {
    @AfterEach
    fun tearDown() = NotificationHub.resetForTest()

    private fun employees() = GlobalContext.get().get<EmployeeService>()

    private fun rotation() = GlobalContext.get().get<RotationAssignmentService>()

    private fun poolCashier(number: String): Employee {
        val emp = TestData.createCashier(employeeNumber = number, username = "u_$number")
        transaction { Employee.findById(emp.id)!!.inRotationPool = true }
        return emp
    }

    private suspend fun observe(): Channel<OutgoingFrame> {
        val out = Channel<OutgoingFrame>(Channel.UNLIMITED)
        NotificationHub.addSseConnection(connectionId = "rpt-obs", employeeId = "rpt-obs", outgoing = out)
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

    @Test
    fun settingRotationPoolPublishes() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            TestData.setRoundRobinAssignment(true)
            val emp = TestData.createCashier(employeeNumber = "RPT_A", username = "u_rpt_a")
            val out = observe()

            employees().setRotationPool(emp.id.value, enabled = true)

            val updates = out.rotationUpdates()
            assertEquals(1, updates.size)
            assertEquals("RPT_A", updates.single().next?.employeeNumber)
        }

    @Test
    fun deactivatingPoolMemberPublishesAndNextSkipsThem() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            TestData.setRoundRobinAssignment(true)
            val first = poolCashier("RPT_B1")
            val second = poolCashier("RPT_B2")
            val nextId = rotation().peekNext()!!.employeeId
            val (leaving, staying) = if (nextId == first.id.value.toString()) first to second else second to first
            val out = observe()

            employees().changeEmploymentStatus(leaving.id.value, EmploymentStatus.INACTIVE, null)

            val updates = out.rotationUpdates()
            assertEquals(1, updates.size)
            assertEquals(staying.employeeNumber, updates.single().next?.employeeNumber)
        }

    @Test
    fun changingRoundRobinRuleOffPublishesNullNext() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            TestData.createShopInfo()
            TestData.setRoundRobinAssignment(true)
            poolCashier("RPT_C")
            val token = TestAuthHelper.loginAsAdmin(client)
            val out = observe()

            val response =
                client.put("/api/v1/config/1/order-rules") {
                    bearerAuth(token)
                    setBody(
                        TextContent(
                            testJson.encodeToString(
                                ShopConfigurationDto.UpdateOrderRulesRequestDto(
                                    employeeSelectionOnFreeTable = false,
                                    roundRobinAssignment = false,
                                ),
                            ),
                            ContentType.Application.Json,
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val updates = out.rotationUpdates()
            assertEquals(1, updates.size)
            assertNull(updates.single().next)
        }

    @Test
    fun unrelatedEmployeeEditDoesNotPublish() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            TestData.setRoundRobinAssignment(true)
            val actor = TestData.createManager(employeeNumber = "RPT_MGR", username = "rpt_mgr")
            val emp = poolCashier("RPT_D")
            val out = observe()

            employees().updateEmployee(
                employeeId = emp.id.value,
                updateData = EmployeeDto.UpdateEmployeeRequestDto(notes = "just a note"),
                userId = actor.id.value.toString(),
                actorCapabilities = setOf(Capability.EMPLOYEE_WRITE),
            )

            assertTrue(out.rotationUpdates().isEmpty(), "a notes edit cannot move the rotation")
        }

    @Test
    fun updateThatChangesEmploymentStatusPublishes() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            TestData.setRoundRobinAssignment(true)
            val actor = TestData.createManager(employeeNumber = "RPT_MGR2", username = "rpt_mgr2")
            val emp = poolCashier("RPT_E")
            val out = observe()

            employees().updateEmployee(
                employeeId = emp.id.value,
                updateData = EmployeeDto.UpdateEmployeeRequestDto(employmentStatus = "INACTIVE"),
                userId = actor.id.value.toString(),
                actorCapabilities = setOf(Capability.EMPLOYEE_WRITE),
            )

            assertEquals(1, out.rotationUpdates().size)
        }

    /**
     * Installs a probe on the notifier that records, for each publish, whether a DB transaction was still
     * open when it computed `next`. An open one means the publish ran before the change committed.
     */
    private suspend fun publishedInsideTransaction(block: suspend () -> Unit): List<Boolean> {
        val real = rotation()
        val seen = mutableListOf<Boolean>()
        RotationNextNotifier.install(
            object : RotationAssignmentService by real {
                override suspend fun peekNext(): RotationDto.EmployeeRefDto? {
                    seen += TransactionManager.currentOrNull() != null
                    return real.peekNext()
                }
            },
        )
        try {
            block()
        } finally {
            RotationNextNotifier.install(real)
        }
        return seen
    }

    private fun statusOf(emp: Employee): EmploymentStatus = transaction { Employee.findById(emp.id)!!.employmentStatus }

    @Test
    fun updateRolledBackAfterTheBlockPublishesNothing() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            TestData.setRoundRobinAssignment(true)
            val emp = poolCashier("RPT_H")
            val out = observe()

            // "not-a-uuid" passes every guard but makes the entry-log write throw AFTER the block, inside
            // the same transaction, so the status change rolls back.
            assertFails {
                employees().updateEmployee(
                    employeeId = emp.id.value,
                    updateData = EmployeeDto.UpdateEmployeeRequestDto(employmentStatus = "INACTIVE"),
                    userId = "not-a-uuid",
                    actorCapabilities = setOf(Capability.EMPLOYEE_WRITE),
                )
            }

            assertEquals(EmploymentStatus.ACTIVE, statusOf(emp), "the change rolled back")
            assertTrue(out.rotationUpdates().isEmpty(), "a rolled-back change must not be announced")
        }

    @Test
    fun deleteRolledBackAfterTheBlockPublishesNothing() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            TestData.setRoundRobinAssignment(true)
            val emp = poolCashier("RPT_I")
            val out = observe()

            assertFails { employees().deleteEmployee(emp.id.value, userId = "not-a-uuid") }

            assertEquals(EmploymentStatus.ACTIVE, statusOf(emp), "the delete rolled back")
            assertTrue(out.rotationUpdates().isEmpty(), "a rolled-back delete must not be announced")
        }

    @Test
    fun employeeWritersPublishOnlyAfterCommit() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            TestData.setRoundRobinAssignment(true)
            val actor = TestData.createManager(employeeNumber = "RPT_MGR3", username = "rpt_mgr3")
            val a = poolCashier("RPT_J1")
            val b = poolCashier("RPT_J2")
            val c = poolCashier("RPT_J3")
            val d = TestData.createCashier(employeeNumber = "RPT_J4", username = "u_rpt_j4")
            val out = observe()

            val seen =
                publishedInsideTransaction {
                    employees().updateEmployee(
                        employeeId = a.id.value,
                        updateData = EmployeeDto.UpdateEmployeeRequestDto(employmentStatus = "INACTIVE"),
                        userId = actor.id.value.toString(),
                        actorCapabilities = setOf(Capability.EMPLOYEE_WRITE),
                    )
                    employees().deleteEmployee(b.id.value, userId = actor.id.value.toString())
                    employees().changeEmploymentStatus(c.id.value, EmploymentStatus.INACTIVE, null)
                    employees().setRotationPool(d.id.value, enabled = true)
                }

            assertEquals(listOf(false, false, false, false), seen, "every publish must run after its commit")
            // Committed state: only d is still an active pool member, so it is what the last publish names.
            assertEquals(
                "RPT_J4",
                out
                    .rotationUpdates()
                    .last()
                    .next
                    ?.employeeNumber,
            )
        }

    @Test
    fun stationTransferOntoFreeTablePublishesOnce() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            val station = TestData.createTerminalDeviceEmployee()
            poolCashier("RPT_F")
            val category = TestData.createCategory(name = "RPT_Cat")
            val product = TestData.createProduct(categoryId = category.id.value, productName = "RPT_Prod", productNumber = "RPT_P")
            val vat = TestData.createDefaultVatRates().first()
            val source = TestData.createTableWithSession(tableNumber = "RPT_SRC")
            val itemId =
                transaction {
                    OrderItem
                        .new {
                            this.session = TableSession.findById(source.session.id)!!
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
            val target = TestData.createTable(tableNumber = "RPT_TGT")

            TestData.withRoundRobinAssignment(true) {
                val out = observe()
                GlobalContext.get().get<OrderItemService>().transferOrderItems(
                    item = listOf(itemId),
                    employeeId = station.id.value.toString(),
                    deviceId = "rpt-station",
                    targetTableId = target.id.value,
                    currentTableId = source.table.id.value,
                )
                assertEquals(1, out.rotationUpdates().size)
            }
        }

    @Test
    fun stationTransferOntoOccupiedTableDoesNotPublish() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            val station = TestData.createTerminalDeviceEmployee()
            poolCashier("RPT_G")
            val category = TestData.createCategory(name = "RPT_Cat2")
            val product = TestData.createProduct(categoryId = category.id.value, productName = "RPT_Prod2", productNumber = "RPT_P2")
            val vat = TestData.createDefaultVatRates().first()
            val source = TestData.createTableWithSession(tableNumber = "RPT_SRC2")
            val occupied = TestData.createTableWithSession(tableNumber = "RPT_OCC")
            val itemId =
                transaction {
                    OrderItem
                        .new {
                            this.session = TableSession.findById(source.session.id)!!
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

            TestData.withRoundRobinAssignment(true) {
                val out = observe()
                GlobalContext.get().get<OrderItemService>().transferOrderItems(
                    item = listOf(itemId),
                    employeeId = station.id.value.toString(),
                    deviceId = "rpt-station",
                    targetTableId = occupied.table.id.value,
                    currentTableId = source.table.id.value,
                )
                assertTrue(out.rotationUpdates().isEmpty(), "joining an existing session does not rotate")
            }
        }
}
