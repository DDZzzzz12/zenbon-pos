package org.example.project.routes

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.assertStatus
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `/api/admin/permissions/effective` and `/explain` — the Stage 5c diagnostics.
 *
 * The last test is the one that matters: it drives real requests and compares each outcome with
 * what `explain` predicted. A diagnostic is consulted exactly when somebody is already confused, so
 * one that disagrees with the gate is worse than none — it confirms the wrong answer with
 * authority. Sharing `AccessDecision` makes drift unlikely; this makes it *visible*.
 */
@DisplayName("Permission diagnostics")
class PermissionDiagnosticsRoutesTest {
    private val effective = "/api/admin/permissions/effective"
    private val explain = "/api/admin/permissions/explain"

    private fun idOf(username: String) =
        transaction {
            Employee
                .find { EmployeeTable.username eq username }
                .first()
                .id.value
        }

    @Test
    fun `effective access reports what a group reaches and what it does not`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val body = client.get("$effective?group=ORDER_ONLY") { bearerAuth(token) }.bodyAsText()

            assertTrue(body.contains("\"groupCode\":\"ORDER_ONLY\""))
            assertTrue(body.contains("\"station\":\"UNBOUND\""), "no station named means UNBOUND, the full mask")
            assertTrue(body.contains("ORDER_WRITE"), "the group's own capabilities must be reported")
            // A group that reached everything or nothing would make the endpoint useless; both lists
            // being populated is the actual assertion.
            assertTrue(body.contains("\"reachable\":[{"))
            assertTrue(body.contains("\"unreachable\":[{"))
            assertTrue(body.contains("PERMISSION_ADMIN"), "the admin routes it cannot reach must name what it lacks")
        }

    @Test
    fun `a station mask shows up as capabilities the group holds but the station does not`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)

            val unbound = client.get("$effective?group=TERMINAL_DEVICE") { bearerAuth(token) }.bodyAsText()
            assertTrue(unbound.contains("\"maskedOut\":[]"), "UNBOUND masks nothing")

            // A kitchen display holds the terminal group's capabilities on paper and reaches almost
            // none of them. "The group has it but this station does not" is the confusion the station
            // axis introduced, and the reason maskedOut is reported separately at all.
            val kitchen = client.get("$effective?group=TERMINAL_DEVICE&station=KITCHEN") { bearerAuth(token) }
            val body = kitchen.bodyAsText()
            assertEquals(HttpStatusCode.OK, kitchen.status)
            assertTrue(body.contains("\"station\":\"KITCHEN\""))
            assertFalse(body.contains("\"maskedOut\":[]"), "a kitchen display must lose capabilities to the mask")
        }

    @Test
    fun `an unknown group is a 404 and an unnamed one a 400`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            assertStatus(HttpStatusCode.NotFound) { client.get("$effective?group=NOPE") { bearerAuth(token) } }
            assertStatus(HttpStatusCode.BadRequest) { client.get(effective) { bearerAuth(token) } }
        }

    @Test
    fun `explain names the missing capability`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createCashier()
            },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val cashier = idOf("cashier")

            val body =
                client
                    .get("$explain?employeeId=$cashier&path=/api/employees/summary&method=GET") { bearerAuth(token) }
                    .bodyAsText()

            assertTrue(body.contains("\"allowed\":false"))
            assertTrue(body.contains("\"ruleFound\":true"))
            assertTrue(body.contains("\"groupCode\":\"CASHIER\""))
            assertTrue(body.contains("EMPLOYEE_READ"), "the answer is useless without naming what is missing")
        }

    @Test
    fun `explain names deny-by-default when no rule is registered`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val admin = idOf("admin")

            // An admin holds every capability and is still refused, because the route carries no
            // rule at all. Nothing in the group's capability list explains that, which makes it the
            // single most confusing failure this system produces — and most of this endpoint's value.
            val body =
                client
                    .get("$explain?employeeId=$admin&path=/api/not-a-real-route&method=GET") { bearerAuth(token) }
                    .bodyAsText()

            assertTrue(body.contains("\"ruleFound\":false"))
            assertTrue(body.contains("\"allowed\":false"))
            assertTrue(body.contains("denied"), "the reason must say the route is denied by default")
        }

    @Test
    fun `explain flags that an ownership route is only a floor`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val admin = idOf("admin")

            val body =
                client
                    .get("$explain?employeeId=$admin&path=/api/employees/$admin&method=GET") { bearerAuth(token) }
                    .bodyAsText()

            assertTrue(body.contains("\"allowed\":true"))
            assertTrue(body.contains("\"ownershipApplies\":true"))
            // Passing the floor is not a promise of 200: the handler still decides whose row this is.
            assertTrue(body.contains("caveat"))
        }

    @Test
    fun `a malformed employee id is rejected rather than answered`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            assertStatus(HttpStatusCode.BadRequest) {
                client.get("$explain?employeeId=not-a-uuid&path=/api/employees/me") { bearerAuth(token) }
            }
            assertStatus(HttpStatusCode.BadRequest) {
                client.get("$explain?employeeId=${UUID.randomUUID()}&path=employees/me") { bearerAuth(token) }
            }
        }

    @Test
    fun `a manager cannot read the diagnostics`() =
        testApp(setupData = { TestData.createManager() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)

            // The rule table maps the whole attack surface; the diagnostics say precisely which
            // account is missing which capability. Both are PERMISSION_ADMIN and nothing weaker.
            assertStatus(HttpStatusCode.Forbidden) { client.get("$effective?group=CASHIER") { bearerAuth(token) } }
            assertStatus(HttpStatusCode.Forbidden) {
                client.get("$explain?employeeId=${idOf("manager")}&path=/api/employees/me") { bearerAuth(token) }
            }
        }

    @Test
    fun `explain agrees with what the server actually does`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createCashier()
            },
        ) { client ->
            val adminToken = TestAuthHelper.loginAsAdmin(client)
            val staffToken = TestAuthHelper.loginAsCashier(client)
            val cashier = idOf("cashier")

            // Plain `Cap` GETs only: an ownership route's verdict is a floor, so a 403 from the
            // handler would look like a disagreement when it is the design.
            val paths =
                listOf(
                    "/api/employees/me",
                    "/api/employees/info/active",
                    "/api/employees/summary",
                    "/api/admin/permissions",
                )

            paths.forEach { path ->
                val predicted =
                    client
                        .get("$explain?employeeId=$cashier&path=$path&method=GET") { bearerAuth(adminToken) }
                        .bodyAsText()
                        .contains("\"allowed\":true")

                val actual = client.get(path) { bearerAuth(staffToken) }.status
                if (predicted) {
                    assertTrue(
                        actual != HttpStatusCode.Forbidden,
                        "explain said the cashier may GET $path, but the server refused it",
                    )
                } else {
                    assertEquals(
                        HttpStatusCode.Forbidden,
                        actual,
                        "explain said the cashier may not GET $path, but the server allowed it",
                    )
                }
            }
        }
}
