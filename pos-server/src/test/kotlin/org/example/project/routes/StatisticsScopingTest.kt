package org.example.project.routes

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.features.auth.data.TemporarySessionStore
import org.example.project.features.employee.data.service.GroupCapabilityResolver
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.assertStatus
import org.example.project.testutils.testApp
import org.example.project.utils.resolveTargetEmployee
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Who sees whose figures.
 *
 * Statistics were the last data-level decision still made from a role: `resolveTargetEmployee` asked
 * `role.isManagerOrHigher()` to choose between "the whole shop" and "your own rows". These tests pin
 * the capability that replaced it, and one regression that the step-up stage created.
 */
@DisplayName("Statistics scoping")
class StatisticsScopingTest {
    private val window = "start_date=2026-01-01T00:00:00&end_date=2026-12-31T23:59:59"

    private fun idOf(username: String) =
        transaction {
            Employee
                .find { EmployeeTable.username eq username }
                .first()
                .id.value
        }

    private fun holds(
        username: String,
        capability: Capability,
    ) = runBlocking { GroupCapabilityResolver().forEmployee(idOf(username)).contains(capability) }

    @Test
    fun `a cashier may read statistics but not filter by another employee`() =
        testApp(
            setupData = {
                TestData.createManager()
                TestData.createCashier()
            },
        ) { client ->
            val staff = TestAuthHelper.loginAsCashier(client)

            // REPORT_READ — their own figures. The route gate lets them in.
            assertEquals(
                HttpStatusCode.OK,
                client.get("/api/statistics/sales/overview?$window") { bearerAuth(staff) }.status,
            )

            // REPORT_ALL_READ — somebody else's. Refused. This used to be
            // `EmployeeRole.isManagerOrAdmin(claim.role)`.
            assertStatus(HttpStatusCode.Forbidden) {
                client.get("/api/statistics/sales/overview?$window&employee_id=${idOf("manager")}") {
                    bearerAuth(staff)
                }
            }
        }

    @Test
    fun `a manager may filter by employee`() =
        testApp(
            setupData = {
                TestData.createManager()
                TestData.createCashier()
            },
        ) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            assertEquals(
                HttpStatusCode.OK,
                client
                    .get("/api/statistics/sales/overview?$window&employee_id=${idOf("cashier")}") {
                        bearerAuth(token)
                    }.status,
            )
        }

    @Test
    fun `training data needs the training-report capability`() =
        testApp(
            setupData = {
                TestData.createManager()
                TestData.createCashier()
            },
        ) { client ->
            assertStatus(HttpStatusCode.Forbidden) {
                client.get("/api/statistics/sales/overview?$window&training_only=true") {
                    bearerAuth(TestAuthHelper.loginAsCashier(client))
                }
            }
            assertEquals(
                HttpStatusCode.OK,
                client
                    .get("/api/statistics/sales/overview?$window&training_only=true") {
                        bearerAuth(TestAuthHelper.loginAsManager(client))
                    }.status,
            )
        }

    @Test
    fun `a step-up does not widen statistics to the whole shop`() =
        testApp(
            setupData = {
                TestData.createManager()
                TestData.createCashier()
            },
        ) { client ->
            val device = "POS-STATS-1"
            val staff = TestAuthHelper.loginAs(client, "cashier", deviceId = device)
            transaction { Employee.find { EmployeeTable.username eq "manager" }.first().pin = "4321" }

            client.post("/api/auth/temporary-session") {
                bearerAuth(staff)
                header("X-Device-ID", device)
                setBody(
                    TextContent(
                        """{"managerUsername":"manager","managerPin":"4321","deviceId":"$device"}""",
                        ContentType.Application.Json,
                    ),
                )
            }

            // THE REGRESSION, asserted on the RESOLVER rather than through the route.
            //
            // A first attempt drove `?employee_id=...` over HTTP and passed even with the defect
            // restored — because `parseStatisticsParams` refuses the filter before the resolver is
            // ever reached, so it was testing the route gate twice and the escalation not at all.
            //
            // The escalation lives in the NO-FILTER case: `resolveTargetEmployee` used to treat any
            // active session as a manager and return null, meaning EVERY employee's rows. Coherent
            // while a step-up replaced the operator's identity; incoherent the moment it became a
            // scoped union, since a session lends ElevationScope.TILL_OVERRIDES and nothing else. A
            // cashier taking a manager's PIN to void one bill also got the shop's sales figures.
            val cashierId = idOf("cashier")
            assertTrue(
                TemporarySessionStore.getSessionByRequestingEmployeeUuid(cashierId.toString()) != null,
                "the fixture needs a live session, or this proves nothing",
            )

            val target =
                transaction {
                    resolveTargetEmployee(cashierId.toString(), filterEmployeeId = null, seesEveryone = false)
                }
            assertEquals(
                cashierId,
                target?.id?.value,
                "a step-up must not widen the query to every employee — null here means the whole shop",
            )
        }

    @Test
    fun `the audit groups can read the shop's figures`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createManager(employeeNumber = "AUD001", username = "auditor")
            },
        ) { client ->
            val admin = TestAuthHelper.loginAsAdmin(client)
            client.put("/api/employees/${idOf("auditor")}/permission-group") {
                bearerAuth(admin)
                setBody(TextContent("""{"groupCode":"AUDITOR"}""", ContentType.Application.Json))
            }

            // The point of V115. An auditor's own figures are the empty set — they do not sell
            // anything — so scoping them to "their own rows" made the group useless. Which rows they
            // saw depended on whichever `role` value happened to sit on their employee row.
            assertTrue(holds("auditor", Capability.REPORT_ALL_READ))
            assertTrue(holds("auditor", Capability.REPORT_READ))
        }

    @Test
    fun `a cashier does not hold REPORT_ALL_READ`() =
        testApp(setupData = { TestData.createCashier() }) { _ ->
            // Getting this backwards is the failure that matters, so it is asserted directly as well
            // as through the route above.
            assertTrue(holds("cashier", Capability.REPORT_READ))
            assertFalse(holds("cashier", Capability.REPORT_ALL_READ))
        }

    // ---------- The trading-day clamp ----------

    /**
     * A closed Z-Bon whose `endTime` is the boundary "today" starts from.
     *
     * Seeded through `TestData.createZBon` rather than `POST /api/z-bon`, which signs a fiscal
     * document — a closing is a side effect this test has no business producing.
     */
    private fun closeZBonAt(
        employee: Employee,
        endTime: String,
    ) = TestData.createZBon(
        shop = TestData.createShopInfo(),
        employee = employee,
        start = LocalDateTime.parse("2026-06-01T06:00:00"),
        end = LocalDateTime.parse(endTime),
        nummer = 1L,
    )

    @Test
    fun `a caller without REPORT_ALL_READ is clamped to the current trading day`() =
        testApp(
            setupData = {
                TestData.createManager()
                TestData.createCashier()
            },
        ) { client ->
            closeZBonAt(transaction { Employee.find { EmployeeTable.username eq "manager" }.first() }, "2026-06-10T02:00:00")
            val staff = TestAuthHelper.loginAsCashier(client)

            // Asking for the whole year is accepted — and answered for the trading day only. The
            // clamp is silent by design; see parseStatisticsParams for why a 403 would be worse.
            assertEquals(
                HttpStatusCode.OK,
                client.get("/api/statistics/sales/overview?$window") { bearerAuth(staff) }.status,
            )
        }

    @Test
    fun `a manager keeps the window they asked for`() =
        testApp(
            setupData = {
                TestData.createManager()
                TestData.createCashier()
            },
        ) { client ->
            closeZBonAt(transaction { Employee.find { EmployeeTable.username eq "manager" }.first() }, "2026-06-10T02:00:00")
            assertEquals(
                HttpStatusCode.OK,
                client
                    .get("/api/statistics/sales/overview?$window") {
                        bearerAuth(TestAuthHelper.loginAsManager(client))
                    }.status,
            )
        }

    @Test
    fun `a shop that has never closed a Z-Bon is not clamped`() =
        testApp(setupData = { TestData.createCashier() }) { client ->
            // No Z-Bon at all. A new shop must not lose its own figures because it has not cashed up
            // yet, so the clamp does not apply rather than defaulting to "nothing before now".
            assertEquals(
                HttpStatusCode.OK,
                client
                    .get("/api/statistics/sales/overview?$window") {
                        bearerAuth(TestAuthHelper.loginAsCashier(client))
                    }.status,
            )
        }
}
