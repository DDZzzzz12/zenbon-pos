package org.example.project.routes

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import org.example.project.core.error.ProblemDetail
import org.example.project.features.employee.database.tables.EmployeePermissionGroupTable
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.database.tables.PermissionGroupTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.assertStatus
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `PUT /api/employees/{id}/permission-group` — Stage 3's one new route.
 *
 * The route is what makes `ACCOUNTANT`, `AUDITOR` and `ORDER_ONLY` reachable at all: an employee's
 * group is otherwise derived from their four-value `role`, so a purpose-built group could never hold
 * a member.
 *
 * **One guardrail is deliberately untested here** — "the actor must hold every capability the target
 * group confers". It is unreachable today and cannot be provoked through the route: only `ADMIN`
 * holds `PERMISSION_ADMIN`, and the `ADMIN` group is every capability by construction, so the
 * difference set is always empty. It was written anyway because Stage 5 widens who may hold
 * `PERMISSION_ADMIN`, and a guardrail added after that widening is a guardrail that was missing in
 * between. Stage 5 must add its test along with the widening.
 */
@DisplayName("Permission group assignment")
class PermissionGroupAssignmentRoutesTest {
    private fun idOf(username: String) =
        transaction {
            Employee
                .find { EmployeeTable.username eq username }
                .first()
                .id.value
        }

    private fun groupCodeOf(employeeId: UUID): String? =
        transaction {
            val groupId =
                EmployeePermissionGroupTable
                    .selectAll()
                    .firstOrNull { it[EmployeePermissionGroupTable.employee].value == employeeId }
                    ?.get(EmployeePermissionGroupTable.group)
                    ?: return@transaction null
            PermissionGroupTable
                .selectAll()
                .firstOrNull { it[PermissionGroupTable.id] == groupId }
                ?.get(PermissionGroupTable.code)
        }

    private fun body(code: String) = TextContent("""{"groupCode":"$code"}""", ContentType.Application.Json)

    @Test
    fun `an admin can move an employee into a purpose-built group`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createManager(employeeNumber = "ACC001", username = "bookkeeper")
            },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val target = idOf("bookkeeper")

            // Created as a MANAGER, so the role-derived assignment put them in MANAGER.
            assertEquals("MANAGER", groupCodeOf(target))

            val response =
                client.put("/api/employees/$target/permission-group") {
                    bearerAuth(token)
                    setBody(body("ACCOUNTANT"))
                }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("ACCOUNTANT"))

            assertEquals("ACCOUNTANT", groupCodeOf(target), "the assignment must be persisted")
        }

    @Test
    fun `an explicit assignment survives an unrelated employee edit`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createManager(employeeNumber = "ACC002", username = "bookkeeper2")
            },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val target = idOf("bookkeeper2")

            client.put("/api/employees/$target/permission-group") {
                bearerAuth(token)
                setBody(body("AUDITOR"))
            }
            assertEquals("AUDITOR", groupCodeOf(target))

            // The defect this guards: `assignPermissionGroup` used to re-derive the group from `role`
            // on EVERY update, so changing a note would silently revoke the auditor's access with
            // nothing in the log to explain it.
            val edit =
                client.put("/api/employees/$target") {
                    bearerAuth(token)
                    setBody(TextContent("""{"notes":"unrelated edit"}""", ContentType.Application.Json))
                }
            assertEquals(HttpStatusCode.OK, edit.status)
            assertEquals("AUDITOR", groupCodeOf(target), "an unrelated edit must not reset the group")
        }

    @Test
    fun `a genuine role change still wins over an explicit assignment`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createManager(employeeNumber = "ACC003", username = "bookkeeper3")
            },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val target = idOf("bookkeeper3")

            client.put("/api/employees/$target/permission-group") {
                bearerAuth(token)
                setBody(body("AUDITOR"))
            }

            // Moving someone to CASHIER must not leave them holding an auditor's reads. The clobber
            // fix narrows WHEN the group is re-derived; it does not stop a role change re-deriving it.
            val edit =
                client.put("/api/employees/$target") {
                    bearerAuth(token)
                    setBody(TextContent("""{"role":"CASHIER"}""", ContentType.Application.Json))
                }
            assertEquals(HttpStatusCode.OK, edit.status)
            assertEquals("CASHIER", groupCodeOf(target), "a role change must re-derive the group")
        }

    @Test
    fun `an unknown group code is rejected rather than silently unassigning`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createManager(employeeNumber = "ACC004", username = "bookkeeper4")
            },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val target = idOf("bookkeeper4")

            assertStatus(HttpStatusCode.BadRequest) {
                client.put("/api/employees/$target/permission-group") {
                    bearerAuth(token)
                    setBody(body("ACCOUNTENT")) // typo, one letter
                }
            }
            // An unassigned employee resolves to NO capabilities and can reach nothing — failing
            // closed, but with nothing for an operator to go on. Rejecting is the kinder failure.
            assertEquals("MANAGER", groupCodeOf(target), "a rejected assignment must change nothing")
        }

    @Test
    fun `an admin cannot change their own group`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val self = idOf("admin")

            // Escalation is not the hazard — only ADMIN reaches this route and ADMIN already holds
            // everything. Self-DEMOTION is: undoing it needs the PERMISSION_ADMIN just given away.
            assertStatus(HttpStatusCode.Forbidden) {
                client.put("/api/employees/$self/permission-group") {
                    bearerAuth(token)
                    setBody(body("ORDER_ONLY"))
                }
            }
            assertEquals("ADMIN", groupCodeOf(self))
        }

    @Test
    fun `a manager cannot hand out permission groups`() =
        testApp(
            setupData = {
                TestData.createManager()
                TestData.createManager(employeeNumber = "ACC005", username = "bookkeeper5")
            },
        ) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val target = idOf("bookkeeper5")

            // PERMISSION_ADMIN is RESERVED, so only ADMIN holds it. Deliberately not EMPLOYEE_WRITE:
            // a manager who may edit staff records must not thereby be able to grant capabilities.
            assertStatus(HttpStatusCode.Forbidden) {
                client.put("/api/employees/$target/permission-group") {
                    bearerAuth(token)
                    setBody(body("ACCOUNTANT"))
                }
            }
        }

    @Test
    fun `the employee response reports which group they belong to`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createManager(employeeNumber = "ACC006", username = "bookkeeper6")
            },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val target = idOf("bookkeeper6")

            // Without this the adminApp staff screen has nothing to display: the group is the
            // authority for what an employee may reach, and the response used to omit it entirely.
            assertTrue(
                client.get("/api/employees/$target") { bearerAuth(token) }.bodyAsText().contains("\"permissionGroupCode\":\"MANAGER\""),
            )

            client.put("/api/employees/$target/permission-group") {
                bearerAuth(token)
                setBody(body("ACCOUNTANT"))
            }

            assertTrue(
                client.get("/api/employees/$target") { bearerAuth(token) }.bodyAsText().contains("\"permissionGroupCode\":\"ACCOUNTANT\""),
                "the response must reflect the new group, not the role-derived one",
            )
        }

    @Test
    fun `assigning an unknown employee is a 404`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            assertStatus(HttpStatusCode.NotFound) {
                client.put("/api/employees/${UUID.randomUUID()}/permission-group") {
                    bearerAuth(token)
                    setBody(body("ACCOUNTANT"))
                }
            }
        }

    @Test
    fun `the terminal-device group cannot be handed to a person`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createManager(employeeNumber = "ACC007", username = "bookkeeper7")
            },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val target = idOf("bookkeeper7")

            // The ROLE half of this pair is already guarded (`guardRoleChange`). Without the matching
            // GROUP guard the same identity stayed reachable by the other half: the group is what
            // actually confers capabilities since Stage 2a, so this handed a person the shared
            // order-station's whole set while their role still read MANAGER.
            val response =
                client.put("/api/employees/$target/permission-group") {
                    bearerAuth(token)
                    setBody(body("TERMINAL_DEVICE"))
                }

            assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
            assertEquals("VALIDATION_FAILED", testJson.decodeFromString<ProblemDetail>(response.bodyAsText()).code)
            assertEquals("MANAGER", groupCodeOf(target), "a rejected assignment must change nothing")
        }

    @Test
    fun `the terminal-device group is refused whatever case it is written in`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createManager(employeeNumber = "ACC008", username = "bookkeeper8")
            },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val target = idOf("bookkeeper8")

            val response =
                client.put("/api/employees/$target/permission-group") {
                    bearerAuth(token)
                    setBody(body("terminal_device"))
                }

            // A lower-case code is refused today as well — but as an UNKNOWN group, because codes are
            // matched exactly. Asserting the guard's OWN sentence is what tells the two apart, so this
            // fails if the guard is dropped and that incidental rejection is left to stand in for it.
            assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
            val problem = testJson.decodeFromString<ProblemDetail>(response.bodyAsText())
            assertTrue(
                problem.detail.orEmpty().contains("reserved for the shared order-station account"),
                "expected the reservation guard, got: ${problem.detail}",
            )
            assertEquals("MANAGER", groupCodeOf(target))
        }

    @Test
    fun `ORDER_ONLY stays assignable to a person`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createManager(employeeNumber = "ACC009", username = "bookkeeper9")
            },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val target = idOf("bookkeeper9")

            // ORDER_ONLY is a strict SUBSET of TERMINAL_DEVICE (CapabilityBundles), so a guard keyed on
            // capability overlap rather than on the group code would refuse this one too — and
            // order-taking staff are exactly who the group was built for.
            val response =
                client.put("/api/employees/$target/permission-group") {
                    bearerAuth(token)
                    setBody(body("ORDER_ONLY"))
                }

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertEquals("ORDER_ONLY", groupCodeOf(target))
        }
}
