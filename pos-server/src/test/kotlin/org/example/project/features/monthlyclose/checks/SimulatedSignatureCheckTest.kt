package org.example.project.features.monthlyclose.checks

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDto
import org.example.project.core.domain.enums.order.OrderStatus
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.monthlyclose.data.repository.SimulatedSignatureRepository
import org.example.project.features.monthlyclose.data.service.checks.SimulatedSignatureCheck
import org.example.project.features.order.domain.models.Orders
import org.example.project.features.tse.database.tables.TseTransactionTable
import org.example.project.features.tse.domain.models.TseTransaction
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * 2026-09-18 e2e finding #1: a NORMAL-configured shop without reachable hardware stored simulated
 * signatures as SIGNED. `no_simulated_signatures` must find those rows by the simulator's serial.
 */
class SimulatedSignatureCheckTest {
    private val start = LocalDateTime.parse("2026-09-01T00:00:00")
    private val end = LocalDateTime.parse("2026-10-01T00:00:00")
    private val inWindow = LocalDateTime.parse("2026-09-18T12:00:00")

    private fun seedBill(
        serial: String,
        status: TseSigningStatus,
    ) {
        val tws = TestData.createTableWithSession("SIMSIG")
        transaction {
            val txId =
                TseTransactionTable.insertAndGetId {
                    it[tseSerialNumber] = serial
                    it[transactionNumber] = 4711L
                    it[processType] = "Kassenbeleg-V1"
                    it[tseMode] = TseMode.NORMAL
                }
            val session = tws.session
            Bill.new {
                this.employeeId = session.employeeId
                this.tableSession = session
                this.billNumber = "SIMSIG-1"
                this.billAmount = BigDecimal("10.0000")
                this.paidAmount = BigDecimal("10.0000")
                this.tips = BigDecimal("0.0000")
                this.startTime = inWindow
                this.endTime = inWindow
                this.isTraining = false
                this.tseSigningStatus = status
                this.tseTransaction = TseTransaction.findById(txId)
            }
        }
    }

    /** An order (Bestellung-V1) signed by the simulator — a live DB held 6 the first check missed. */
    private fun seedOrder(serial: String) {
        val tws = TestData.createTableWithSession("SIMORD")
        transaction {
            val txId =
                TseTransactionTable.insertAndGetId {
                    it[tseSerialNumber] = serial
                    it[transactionNumber] = 4712L
                    it[processType] = "Bestellung-V1"
                    it[tseMode] = TseMode.NORMAL
                }
            Orders.new {
                this.orderNumber = "ORD-SIMSIG-1"
                this.employee = tws.session.employeeId
                this.tableSession = tws.session
                this.orderTime = inWindow
                this.orderTotal = BigDecimal("10.0000")
                this.orderStatus = OrderStatus.CONFIRMED
                this.tseSigningStatus = TseSigningStatus.SIGNED
                this.tseTransaction = TseTransaction.findById(txId)
            }
        }
    }

    private fun runCheck(): MonthlyCloseDto.ChecklistItemDto = transaction { SimulatedSignatureCheck(SimulatedSignatureRepository()).run(start, end) }

    @Test
    fun `SIGNED bill with the simulated serial fails the check`() =
        testApp(setupData = { seedBill(SimulatedSignatureRepository.SIMULATED_SERIAL, TseSigningStatus.SIGNED) }) { _ ->
            val item = runCheck()
            assertEquals("no_simulated_signatures", item.id)
            assertEquals(MonthlyCloseDto.ChecklistStatus.FAILED, item.status)
            assertEquals(1, item.affectedCount)
        }

    /** The form a live database actually holds (2026-09-19): the serial persisted hex-encoded. */
    @Test
    fun `SIGNED bill with the hex-encoded simulated serial fails the check`() =
        testApp(setupData = { seedBill("53494D2D5453452D303031", TseSigningStatus.SIGNED) }) { _ ->
            assertEquals(MonthlyCloseDto.ChecklistStatus.FAILED, runCheck().status)
        }

    @Test
    fun `SIGNED bill with the plain simulated serial fails the check`() =
        testApp(setupData = { seedBill("SIM-TSE-001", TseSigningStatus.SIGNED) }) { _ ->
            assertEquals(MonthlyCloseDto.ChecklistStatus.FAILED, runCheck().status)
        }

    @Test
    fun `SIGNED order with the simulated serial fails the check`() =
        testApp(setupData = { seedOrder("53494D2D5453452D303031") }) { _ ->
            val item = runCheck()
            assertEquals(MonthlyCloseDto.ChecklistStatus.FAILED, item.status)
            assertEquals(1, item.affectedCount)
        }

    @Test
    fun `configured SIMULATION rows are not flagged`() =
        testApp(setupData = { seedBill(SimulatedSignatureRepository.SIMULATED_SERIAL, TseSigningStatus.SIMULATION) }) { _ ->
            assertEquals(MonthlyCloseDto.ChecklistStatus.PASSED, runCheck().status)
        }

    @Test
    fun `a real serial passes`() =
        testApp(setupData = { seedBill("cmVhbC1zZXJpYWw=", TseSigningStatus.SIGNED) }) { _ ->
            assertEquals(MonthlyCloseDto.ChecklistStatus.PASSED, runCheck().status)
        }
}
