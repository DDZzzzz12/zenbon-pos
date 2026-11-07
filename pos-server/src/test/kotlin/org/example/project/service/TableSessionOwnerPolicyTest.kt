package org.example.project.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.constants.WellKnownLocationTypes
import org.example.project.core.error.PosErrorCode
import org.example.project.features.employee.data.repository.EmployeeInfoRepositoryImpl
import org.example.project.features.employee.data.repository.EmployeeRepositoryImpl
import org.example.project.features.employee.data.repository.PermissionGroupRepositoryImpl
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.data.service.RotationAssignmentServiceImpl
import org.example.project.features.table.data.service.TableSessionOwnerPolicyImpl
import org.example.project.features.table.domain.models.RTable
import org.example.project.features.table.domain.service.TableSessionOwnerPolicy
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.dbQuery
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Spec 2026-09-14 §3 for a terminal role, and the unchanged resolution for a person. */
class TableSessionOwnerPolicyTest {
    private val policy: TableSessionOwnerPolicy =
        TableSessionOwnerPolicyImpl(
            configManager = ShopConfigurationManager,
            rotationAssignmentService =
                RotationAssignmentServiceImpl(
                    employeeRepository = EmployeeRepositoryImpl(),
                    configManager = ShopConfigurationManager,
                    permissionGroups = PermissionGroupRepositoryImpl(),
                    employeeInfoRepository = EmployeeInfoRepositoryImpl(),
                ),
        )

    private fun poolCashier(number: String): Employee {
        val emp = TestData.createCashier(employeeNumber = number, username = "u_$number")
        transaction { Employee.findById(emp.id)!!.inRotationPool = true }
        return emp
    }

    /** The policy as its callers run it: inside one transaction, with entities loaded in it. */
    private suspend fun newSessionOwner(
        tableId: Long,
        actingId: UUID,
        forEmployeeNumber: String? = null,
    ): String =
        dbQuery {
            policy.ownerForNewSession(RTable.findById(tableId)!!, Employee.findById(actingId)!!, forEmployeeNumber).employeeNumber
        }

    private suspend fun bookingOwner(
        tableId: Long,
        actingId: UUID,
    ): String = dbQuery { policy.ownerForBookingOpen(RTable.findById(tableId)!!, Employee.findById(actingId)!!).employeeNumber }

    private suspend fun rejection(block: suspend () -> Unit): PosErrorCode {
        val thrown = runCatching { block() }.exceptionOrNull()
        return assertIs<PosException>(thrown, "expected a PosException, got $thrown").code
    }

    @Test
    fun `a station on a free normal table with round-robin on is owned by the rotation pick`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            poolCashier("001")
            val station = TestData.createTerminalDeviceEmployee()
            val table = TestData.createTable(tableNumber = "P_NORMAL")

            TestData.withRoundRobinAssignment(true) {
                assertEquals("001", newSessionOwner(table.id.value, station.id.value))
                assertEquals("001", bookingOwner(table.id.value, station.id.value))
            }
        }

    @Test
    fun `a station never opens a takeaway or virtual table, and that answer wins over a named owner`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            poolCashier("001")
            val station = TestData.createTerminalDeviceEmployee()
            val takeaway = TestData.createTable(tableNumber = "P_TAKE", locationTypeId = WellKnownLocationTypes.TAKEAWAY)
            val voucher = TestData.createTable(tableNumber = "P_VOUCH", seats = 0, locationTypeId = WellKnownLocationTypes.VOUCHER)

            TestData.withRoundRobinAssignment(true) {
                assertEquals(PosErrorCode.STATION_TABLE_VIRTUAL, rejection { newSessionOwner(takeaway.id.value, station.id.value) })
                assertEquals(PosErrorCode.STATION_TABLE_VIRTUAL, rejection { newSessionOwner(voucher.id.value, station.id.value) })
                assertEquals(
                    PosErrorCode.STATION_TABLE_VIRTUAL,
                    rejection { newSessionOwner(takeaway.id.value, station.id.value, forEmployeeNumber = "001") },
                )
                assertEquals(PosErrorCode.STATION_TABLE_VIRTUAL, rejection { bookingOwner(voucher.id.value, station.id.value) })
                assertNull(ShopConfigurationManager.getOrderRotationCursor(), "a refused open must not advance the rotation")
            }
        }

    @Test
    fun `a station cannot name the owner of a free table`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            poolCashier("001")
            val station = TestData.createTerminalDeviceEmployee()
            val table = TestData.createTable(tableNumber = "P_NAMED")

            TestData.withRoundRobinAssignment(true) {
                assertEquals(
                    PosErrorCode.STATION_TABLE_REQUIRES_ROTATION,
                    rejection { newSessionOwner(table.id.value, station.id.value, forEmployeeNumber = "001") },
                )
                assertNull(ShopConfigurationManager.getOrderRotationCursor())
            }
        }

    @Test
    fun `a station cannot open a free table with round-robin off`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            poolCashier("001")
            val station = TestData.createTerminalDeviceEmployee()
            val table = TestData.createTable(tableNumber = "P_OFF")
            // Row 1 (takeaway/virtual) wins over row 4 (round-robin off) even with rotation off.
            val takeaway = TestData.createTable(tableNumber = "P_OFF_TA", locationTypeId = WellKnownLocationTypes.TAKEAWAY)
            TestData.setRoundRobinAssignment(false)

            assertEquals(PosErrorCode.STATION_TABLE_REQUIRES_ROTATION, rejection { newSessionOwner(table.id.value, station.id.value) })
            assertEquals(PosErrorCode.STATION_TABLE_REQUIRES_ROTATION, rejection { bookingOwner(table.id.value, station.id.value) })
            assertEquals(PosErrorCode.STATION_TABLE_VIRTUAL, rejection { newSessionOwner(takeaway.id.value, station.id.value) })
        }

    @Test
    fun `a station on an empty pool gets ROTATION_POOL_EMPTY`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            val station = TestData.createTerminalDeviceEmployee()
            val table = TestData.createTable(tableNumber = "P_EMPTY")

            TestData.withRoundRobinAssignment(true) {
                assertEquals(PosErrorCode.ROTATION_POOL_EMPTY, rejection { newSessionOwner(table.id.value, station.id.value) })
            }
        }

    @Test
    fun `a person resolves exactly as before, and a booking open never rotates a person`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            poolCashier("001")
            val caller = TestData.createCashier(employeeNumber = "999", username = "caller")
            val normal = TestData.createTable(tableNumber = "P_PERSON")
            val quickSale = TestData.createTable(tableNumber = "P_PER_QS", seats = 0, locationTypeId = WellKnownLocationTypes.QUICK_SALE)

            TestData.withRoundRobinAssignment(true) {
                assertEquals("001", newSessionOwner(normal.id.value, caller.id.value, forEmployeeNumber = "001"))
                assertEquals("001", newSessionOwner(normal.id.value, caller.id.value))
                assertEquals("999", newSessionOwner(quickSale.id.value, caller.id.value))
                assertEquals("999", bookingOwner(normal.id.value, caller.id.value))
            }

            assertEquals("999", newSessionOwner(normal.id.value, caller.id.value))
        }

    @Test
    fun `a person opening a free takeaway table owns it and the rotation does not move`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            // T9-18: the seeded takeaway location ("Abholung") is takeaway but NOT virtual, so an
            // isVirtual-only exemption let every pickup order burn a waiter's turn.
            poolCashier("001")
            poolCashier("002")
            val caller = TestData.createCashier(employeeNumber = "999", username = "caller_take")
            val takeaway = TestData.createTable(tableNumber = "P_PER_TA", locationTypeId = WellKnownLocationTypes.TAKEAWAY)
            val normal = TestData.createTable(tableNumber = "P_PER_TA_N")
            ShopConfigurationManager.setOrderRotationCursor(null)

            TestData.withRoundRobinAssignment(true) {
                assertEquals("999", newSessionOwner(takeaway.id.value, caller.id.value))
                assertNull(ShopConfigurationManager.getOrderRotationCursor(), "a takeaway open must not advance the rotation")
                assertEquals("001", newSessionOwner(normal.id.value, caller.id.value), "the next dining table gets whoever was next")
            }
        }

    @Test
    fun `a person cannot name the terminal account as the owner of a new session`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            val caller = TestData.createCashier(employeeNumber = "999", username = "caller_term")
            val station = TestData.createTerminalDeviceEmployee()
            val table = TestData.createTable(tableNumber = "P_TERMOWN")

            val thrown =
                runCatching {
                    newSessionOwner(table.id.value, caller.id.value, forEmployeeNumber = station.employeeNumber)
                }.exceptionOrNull()
            val posException = assertIs<PosException>(thrown, "expected a PosException, got $thrown")
            assertEquals(PosErrorCode.VALIDATION_FAILED, posException.code)
            assertEquals(HttpStatusCode.BadRequest, posException.httpStatus)
        }
}
