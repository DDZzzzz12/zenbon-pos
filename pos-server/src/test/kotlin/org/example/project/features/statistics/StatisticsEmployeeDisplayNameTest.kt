package org.example.project.features.statistics

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.atTime
import org.example.project.core.domain.dto.statistics.StatisticsDto
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.features.bill.data.service.MapperBillSummaries
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.employee.database.tables.EmployeeInfoTable
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.employee.mapper.toInfoDto
import org.example.project.features.order.domain.models.Cancellation
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.payment.domain.models.Payment
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.statistics.data.repository.StatisticsRepositoryImpl
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.table.mapper.toSessionSummaryDto
import org.example.project.features.terminal.domain.TerminalEmployee
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * UI review follow-ups 5 and 6: the statistics operator rollups name an operator by the HR name
 * (`employee_info` first + last name) only when the caller may read personnel data (`hrNames`, i.e.
 * `EMPLOYEE_READ`), and by the username label otherwise. `employeeName` stays the username on the
 * wire for older clients; `displayName` is always filled. The route-level gate is
 * `StatisticsOperatorNameGateTest`.
 */
class StatisticsEmployeeDisplayNameTest {
    private val repo = StatisticsRepositoryImpl(MapperBillSummaries(SalesInvoiceBillInvoiceLink()))
    private val start = LocalDate(2026, 10, 1).atTime(0, 0, 0)
    private val end = LocalDate(2026, 10, 31).atTime(23, 59, 59)
    private val at = LocalDate(2026, 10, 16).atTime(12, 0, 0)

    private fun groups(hrNames: Boolean) =
        transaction {
            repo
                .getPaginatedEmployeeGroups(start, end, null, 0, 50, StatisticsDto.StatisticsSortOption.RECENT, false, null, hrNames)
                .employeeGroups
                .associateBy { it.employeeNumber }
        }

    @Test
    fun `with hrNames an employee with a personnel record is named by it, one without by the username`() =
        testApp(setupData = { seedThreeOperators() }) { _ ->
            val groups = groups(hrNames = true)

            val named = groups.getValue("TBL_DN1")
            assertEquals("Klara Kern", named.displayName)
            assertEquals("tbl_DN1", named.employeeName, "the username field stays on the wire")

            val unnamed = groups.getValue("TBL_DN2")
            assertEquals("tbl_DN2", unnamed.displayName)
            assertEquals("tbl_DN2", unnamed.employeeName)

            val terminal = groups.getValue(TerminalEmployee.EMPLOYEE_NUMBER)
            assertEquals(TerminalEmployee.DISPLAY_NAME, terminal.displayName)
        }

    @Test
    fun `without hrNames every operator is named by the username, personnel record or not`() =
        testApp(setupData = { seedThreeOperators() }) { _ ->
            val groups = groups(hrNames = false)

            assertEquals("tbl_DN1", groups.getValue("TBL_DN1").displayName)
            assertEquals("tbl_DN2", groups.getValue("TBL_DN2").displayName)
            assertEquals(TerminalEmployee.DISPLAY_NAME, groups.getValue(TerminalEmployee.EMPLOYEE_NUMBER).displayName)
        }

    @Test
    fun `the repository defaults to usernames, so a call site that forgets the gate leaks nothing`() =
        testApp(setupData = { seedThreeOperators() }) { _ ->
            val named = transaction { repo.getPaginatedEmployeeGroups(start, end).employeeGroups.single { it.employeeNumber == "TBL_DN1" } }
            assertEquals("tbl_DN1", named.displayName)
            val cancelled = transaction { repo.getCanceledItems(start, end).canceledItems.single() }
            assertEquals("tbl_DN1", cancelled.displayName)
        }

    @Test
    fun `cancelled items carry the HR name only with hrNames`() =
        testApp(setupData = { seedThreeOperators() }) { _ ->
            val withNames = transaction { repo.getCanceledItems(start, end, null, false, hrNames = true).canceledItems.single() }
            assertEquals("Klara Kern", withNames.displayName)
            assertEquals("tbl_DN1", withNames.employeeName, "the username field stays on the wire")

            val without = transaction { repo.getCanceledItems(start, end, null, false, hrNames = false).canceledItems.single() }
            assertEquals("tbl_DN1", without.displayName)
            assertEquals("tbl_DN1", without.employeeName)
        }

    @Test
    fun `sessions carry the personnel last name only with hrNames`() =
        testApp(setupData = { seedThreeOperators() }) { _ ->
            fun lastNameOf(hrNames: Boolean) =
                transaction {
                    repo
                        .getPaginatedSessions(LocalDate(2000, 1, 1).atTime(0, 0), LocalDate(2100, 1, 1).atTime(0, 0), hrNames = hrNames)
                        .sessions
                        .single { it.employeeInfo.employeeNumber == "TBL_DN1" }
                        .employeeInfo
                        .lastName
                }

            assertEquals("Kern", lastNameOf(hrNames = true))
            assertEquals("tbl_DN1", lastNameOf(hrNames = false))
        }

    /**
     * Follow-up 7: the guard sits in the mappers themselves, so a session summary or a short info
     * row built by any (future) path names the operator by the username unless the caller says it
     * may read personnel data. The colleague roster (`/api/employees/info/active`) opts in explicitly.
     */
    @Test
    fun `the session and info mappers name by the username unless asked for the HR name`() =
        testApp(setupData = { seedThreeOperators() }) { _ ->
            transaction {
                val session =
                    TableSession.all().single { it.employeeId.employeeNumber == "TBL_DN1" }
                assertEquals("tbl_DN1", session.toSessionSummaryDto(SalesInvoiceBillInvoiceLink()).employeeInfo.lastName, "default fails closed")
                assertEquals("tbl_DN1", session.toSessionSummaryDto(SalesInvoiceBillInvoiceLink(), forActiveSession = true).employeeInfo.lastName)
                assertEquals("Kern", session.toSessionSummaryDto(SalesInvoiceBillInvoiceLink(), hrNames = true).employeeInfo.lastName)
                assertEquals("tbl_DN1", session.employeeId.toInfoDto().lastName, "default fails closed")
                assertEquals("Kern", session.employeeId.toInfoDto(hrLastName = true).lastName)
            }
        }

    /** DN1 with a personnel record (and a paid bill + a cancellation), DN2 without, the device account. */
    private fun seedThreeOperators() {
        TestData.createShopInfo()
        val category = TestData.createCategory(name = "DNCat")
        val product = TestData.createProduct(categoryId = category.id.value, productName = "Schnitzel", productNumber = "DN-P1")
        val vat = TestData.createDefaultVatRates().first()
        val paymentType = TestData.createPaymentType()
        val withRecord = TestData.createTableWithSession("DN1")
        val withoutRecord = TestData.createTableWithSession("DN2")
        val device = TestData.createTerminalDeviceEmployee()
        transaction {
            personnelRecord(withRecord.session.employeeId, first = "Klara", last = "Kern")
            val paid = bill("DN1-1", withRecord.session.employeeId, withRecord.session)
            Payment.new {
                this.bill = paid
                this.paymentType = paymentType
                this.amount = BigDecimal("10.00")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = at
            }
            bill("DN2-1", withoutRecord.session.employeeId, withoutRecord.session)
            bill("DN3-1", device, withoutRecord.session)
            val item =
                OrderItem.new {
                    this.session = withRecord.session
                    this.product = product
                    this.unitPrice = BigDecimal("4.5000")
                    this.finalPrice = BigDecimal("4.5000")
                    this.itemStatus = ItemStatus.CANCELLED
                    this.vat = vat
                    this.takeAway = false
                }
            Cancellation.new {
                this.orderItem = item
                this.employeeId = withRecord.session.employeeId
                this.createdAt = at
                this.reason = "dropped"
            }
        }
    }

    private fun bill(
        number: String,
        employee: Employee,
        session: TableSession,
    ) = Bill.new {
        this.employeeId = employee
        this.tableSession = session
        this.billNumber = number
        this.billAmount = BigDecimal("10.00")
        this.paidAmount = BigDecimal("10.00")
        this.tips = BigDecimal("1.00")
        this.startTime = at
        this.endTime = at
        this.isTraining = false
    }

    /** Written on both sides, as `EmployeeInfoRepositoryImpl` does. Requires a transaction. */
    private fun personnelRecord(
        employee: Employee,
        first: String,
        last: String,
    ) {
        val infoId =
            EmployeeInfoTable.insert {
                it[EmployeeInfoTable.employeeId] = employee.id
                it[EmployeeInfoTable.firstName] = first
                it[EmployeeInfoTable.lastName] = last
                it[EmployeeInfoTable.createdAt] = STAMP
                it[EmployeeInfoTable.updatedAt] = STAMP
            }[EmployeeInfoTable.id]
        EmployeeTable.update({ EmployeeTable.id eq employee.id }) { it[EmployeeTable.infoId] = infoId }
    }

    private companion object {
        val STAMP: LocalDateTime = LocalDateTime.parse("2026-10-01T10:00")
    }
}
