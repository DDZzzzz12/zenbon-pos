package org.example.project.routes

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.statistics.StatisticsDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.employee.database.tables.EmployeeInfoTable
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.database.tables.PermissionGroupCapabilityTable
import org.example.project.features.employee.database.tables.PermissionGroupTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.domain.models.Cancellation
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * UI review follow-up 6: the statistics operator rollups give a colleague's HR name only to a
 * caller holding `EMPLOYEE_READ`. Pinned through real requests, so the route really hands the
 * caller's capabilities to the service:
 *
 * - MANAGER (holds `EMPLOYEE_READ`) sees "Klara Kern";
 * - a purpose-built group with `REPORT_ALL_READ` but without `EMPLOYEE_READ` sees the whole shop
 *   but only the username — no seeded group splits these two, which is how a missing gate would
 *   look fine forever;
 * - the operator herself, a CASHIER (no `EMPLOYEE_READ`), sees her own figures under her username.
 *
 * One test per endpoint: by-employee, canceled-items, sessions.
 */
@DisplayName("Statistics operator names follow EMPLOYEE_READ")
class StatisticsOperatorNameGateTest {
    private val window = "start_date=2000-01-01T00:00:00&end_date=2100-12-31T23:59:59"

    @Test
    fun `by-employee names the operator by HR name only for EMPLOYEE_READ`() =
        gateTest { client, callers ->
            suspend fun nameFor(token: String) =
                decode<StatisticsDto.PaginatedEmployeeGroupsResponseDto>(client, "/api/statistics/sales/by-employee?$window", token)
                    .employeeGroups
                    .single { it.employeeName == OPERATOR }
                    .displayName

            assertEquals("Klara Kern", nameFor(callers.manager))
            assertEquals(OPERATOR, nameFor(callers.reportAllWithoutHr))
            assertEquals(OPERATOR, nameFor(callers.operatorHerself))
        }

    @Test
    fun `canceled-items names the operator by HR name only for EMPLOYEE_READ`() =
        gateTest { client, callers ->
            suspend fun nameFor(token: String) =
                decode<StatisticsDto.CanceledItemsResponseDto>(client, "/api/statistics/sales/canceled-items?$window", token)
                    .canceledItems
                    .single()
                    .also { assertEquals(OPERATOR, it.employeeName, "the username field stays on the wire") }
                    .displayName

            assertEquals("Klara Kern", nameFor(callers.manager))
            assertEquals(OPERATOR, nameFor(callers.reportAllWithoutHr))
            assertEquals(OPERATOR, nameFor(callers.operatorHerself))
        }

    @Test
    fun `sessions carry the personnel last name only for EMPLOYEE_READ`() =
        gateTest { client, callers ->
            suspend fun lastNameFor(token: String) =
                decode<StatisticsDto.PaginatedSessionsResponseDto>(client, "/api/statistics/sales/sessions?$window", token)
                    .sessions
                    .single { it.employeeInfo.employeeNumber == "TBL_$TABLE" }
                    .employeeInfo
                    .lastName

            assertEquals("Kern", lastNameFor(callers.manager))
            assertEquals(OPERATOR, lastNameFor(callers.reportAllWithoutHr))
            assertEquals(OPERATOR, lastNameFor(callers.operatorHerself))
        }

    private class Callers(
        val manager: String,
        val reportAllWithoutHr: String,
        val operatorHerself: String,
    )

    private fun gateTest(block: suspend (HttpClient, Callers) -> Unit) =
        testApp(
            setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
                TestData.createManager()
                TestData.createManager(employeeNumber = "RPT001", username = REPORTER)
                seedReportAllWithoutHrGroup()
                seedOperatorWithSale()
            },
        ) { client ->
            // Assigned through the API, so the capability caches are invalidated as in production.
            val admin = TestAuthHelper.loginAsAdmin(client)
            assertEquals(
                HttpStatusCode.OK,
                client
                    .put("/api/employees/${idOf(REPORTER)}/permission-group") {
                        bearerAuth(admin)
                        setBody(TextContent("""{"groupCode":"$GROUP"}""", ContentType.Application.Json))
                    }.status,
                "precondition: the narrowed group must be assignable",
            )
            block(
                client,
                Callers(
                    manager = TestAuthHelper.loginAsManager(client),
                    reportAllWithoutHr = TestAuthHelper.loginAs(client, REPORTER),
                    operatorHerself = TestAuthHelper.loginAs(client, OPERATOR),
                ),
            )
        }

    private suspend inline fun <reified T> decode(
        client: HttpClient,
        path: String,
        token: String,
    ): T {
        val response = client.get(path) { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, response.status, "GET $path")
        return testJson.decodeFromString<T>(response.bodyAsText())
    }

    private fun idOf(username: String) =
        transaction {
            Employee
                .find { EmployeeTable.username eq username }
                .first()
                .id.value
        }

    /** Whole-shop reporting without personnel data. `SELF_READ` only so the principal can log in. */
    private fun seedReportAllWithoutHrGroup() {
        transaction {
            val now = currentTimeStamp()
            val groupId =
                PermissionGroupTable.insertAndGetId {
                    it[code] = GROUP
                    it[name] = "Reports without personnel data"
                    it[description] = "holds REPORT_ALL_READ and deliberately not EMPLOYEE_READ"
                    it[isSystem] = false
                    it[isDeletable] = true
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            listOf(Capability.SELF_READ, Capability.REPORT_READ, Capability.REPORT_ALL_READ).forEach { capability ->
                PermissionGroupCapabilityTable.insert {
                    it[group] = groupId
                    it[this.capability] = capability.name
                }
            }
        }
    }

    /** The cashier `tbl_GATE` with a personnel record, one bill and one cancellation in her session. */
    private fun seedOperatorWithSale() {
        val category = TestData.createCategory(name = "GateCat")
        val product = TestData.createProduct(categoryId = category.id.value, productName = "Schnitzel", productNumber = "GATE-P1")
        val vat = TestData.createDefaultVatRates().first()
        val tws = TestData.createTableWithSession(TABLE)
        val at = currentTimeStamp()
        transaction {
            val operator = tws.session.employeeId
            val infoId =
                EmployeeInfoTable.insert {
                    it[EmployeeInfoTable.employeeId] = operator.id
                    it[EmployeeInfoTable.firstName] = "Klara"
                    it[EmployeeInfoTable.lastName] = "Kern"
                    it[EmployeeInfoTable.createdAt] = STAMP
                    it[EmployeeInfoTable.updatedAt] = STAMP
                }[EmployeeInfoTable.id]
            EmployeeTable.update({ EmployeeTable.id eq operator.id }) { it[EmployeeTable.infoId] = infoId }

            Bill.new {
                this.employeeId = operator
                this.tableSession = tws.session
                this.billNumber = "GATE-1"
                this.billAmount = BigDecimal("10.00")
                this.paidAmount = BigDecimal("10.00")
                this.tips = BigDecimal("1.00")
                this.startTime = at
                this.endTime = at
                this.isTraining = false
            }
            val item =
                OrderItem.new {
                    this.session = tws.session
                    this.product = product
                    this.unitPrice = BigDecimal("4.5000")
                    this.finalPrice = BigDecimal("4.5000")
                    this.itemStatus = ItemStatus.CANCELLED
                    this.vat = vat
                    this.takeAway = false
                }
            Cancellation.new {
                this.orderItem = item
                this.employeeId = operator
                this.createdAt = at
                this.reason = "dropped"
            }
        }
    }

    private companion object {
        const val TABLE = "GATE"
        const val OPERATOR = "tbl_GATE"
        const val REPORTER = "reporter"
        const val GROUP = "REPORTS_WITHOUT_HR"
        val STAMP: LocalDateTime = LocalDateTime.parse("2026-10-01T10:00")
    }
}
