package org.example.project.routes

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `/api/admin/permission-groups` — the Stage 5b CRUD API, driven through the real server.
 *
 * The §7 guardrails are asserted here rather than against the service in isolation, because two of
 * them (system-group immutability, member-count on delete) are only meaningful against the seeded
 * rows a real boot produces. The two that the route genuinely cannot provoke — the escalation bound
 * and the lockout floor — are in `PermissionGroupGuardrailTest`, which reaches the service directly
 * and says why.
 */
@DisplayName("Permission group CRUD")
class PermissionGroupCrudRoutesTest {
    private val base = "/api/admin/permission-groups"

    private fun json(raw: String) = TextContent(raw, ContentType.Application.Json)

    private fun idOf(username: String) =
        transaction {
            Employee
                .find { EmployeeTable.username eq username }
                .first()
                .id.value
        }

    @Test
    fun `the list reports the seeded groups and flags them as system groups`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val body = client.get(base) { bearerAuth(token) }.bodyAsText()

            listOf("ADMIN", "MANAGER", "CASHIER", "TERMINAL_DEVICE", "ACCOUNTANT", "AUDITOR", "ORDER_ONLY")
                .forEach { assertTrue(body.contains("\"$it\""), "the list must contain $it") }
            assertFalse(body.contains("\"isSystem\":false"), "every seeded group is a system group")
        }

    @Test
    fun `the capability catalog marks reserved capabilities as not assignable`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val body = client.get("$base/capabilities") { bearerAuth(token) }.bodyAsText()

            // The editor greys these out. Reporting them as assignable would let an operator pick one
            // and collect a rejection they cannot act on.
            assertTrue(
                body.contains("""{"name":"PERMISSION_ADMIN","assignable":false}"""),
                "PERMISSION_ADMIN is RESERVED and must be reported as not assignable",
            )
            assertTrue(
                body.contains("""{"name":"ORDER_READ","assignable":true}"""),
                "an ordinary capability must be assignable",
            )
        }

    @Test
    fun `a created group is editable, holds members, and is deletable once empty`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createCashier()
            },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)

            val created =
                client.post(base) {
                    bearerAuth(token)
                    setBody(
                        json(
                            """{"code":"floor_lead","name":"Floor lead",
                               "capabilities":["SELF_READ","EMPLOYEE_READ"]}""",
                        ),
                    )
                }
            assertEquals(HttpStatusCode.Created, created.status)
            // The code is normalised, so "floor_lead" and "FLOOR_LEAD" cannot become two groups that
            // look identical in the editor.
            assertTrue(created.bodyAsText().contains("\"FLOOR_LEAD\""))
            assertTrue(created.bodyAsText().contains("\"isSystem\":false"))
            assertTrue(created.bodyAsText().contains("\"isDeletable\":true"))

            val cashier = idOf("cashier")
            assertEquals(
                HttpStatusCode.OK,
                client
                    .put("/api/employees/$cashier/permission-group") {
                        bearerAuth(token)
                        setBody(json("""{"groupCode":"FLOOR_LEAD"}"""))
                    }.status,
            )
            assertTrue(
                client.get("$base/FLOOR_LEAD") { bearerAuth(token) }.bodyAsText().contains("\"memberCount\":1"),
            )

            // Deleting it now would leave the cashier with no group — and an employee with no group
            // resolves to no capabilities and can reach nothing, silently.
            assertStatus(HttpStatusCode.Conflict) { client.delete("$base/FLOOR_LEAD") { bearerAuth(token) } }

            client.put("/api/employees/$cashier/permission-group") {
                bearerAuth(token)
                setBody(json("""{"groupCode":"CASHIER"}"""))
            }
            assertEquals(
                HttpStatusCode.NoContent,
                client.delete("$base/FLOOR_LEAD") { bearerAuth(token) }.status,
            )
            assertStatus(HttpStatusCode.NotFound) { client.get("$base/FLOOR_LEAD") { bearerAuth(token) } }
        }

    @Test
    fun `a reserved capability cannot be granted by creating a group`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)

            // The whole point of Assignability.RESERVED: an admin who may manage groups still cannot
            // manufacture a group that closes the day or restores a backup.
            assertStatus(HttpStatusCode.Forbidden) {
                client.post(base) {
                    bearerAuth(token)
                    setBody(json("""{"code":"SNEAKY","name":"Sneaky","capabilities":["ZBON_CLOSE"]}"""))
                }
            }
            assertStatus(HttpStatusCode.NotFound) { client.get("$base/SNEAKY") { bearerAuth(token) } }
        }

    @Test
    fun `a reserved capability cannot be added to an existing group either`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            client.post(base) {
                bearerAuth(token)
                setBody(json("""{"code":"LEADS","name":"Leads","capabilities":["SELF_READ"]}"""))
            }

            // The create path and the update path are separate call sites, and a guardrail applied to
            // only one of them is no guardrail: an operator would simply create the group empty and
            // then edit the reserved capability in.
            assertStatus(HttpStatusCode.Forbidden) {
                client.put("$base/LEADS") {
                    bearerAuth(token)
                    setBody(json("""{"capabilities":["SELF_READ","BACKUP_RESTORE"]}"""))
                }
            }
            assertFalse(
                client.get("$base/LEADS") { bearerAuth(token) }.bodyAsText().contains("BACKUP_RESTORE"),
            )
        }

    @Test
    fun `an unknown capability name is rejected, not silently dropped`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)

            // Dropping it would create a group the operator believes grants ORDER_WRITE and which
            // grants nothing of the sort — on the one screen whose job is to show what a group can do.
            assertStatus(HttpStatusCode.BadRequest) {
                client.post(base) {
                    bearerAuth(token)
                    setBody(json("""{"code":"TYPO","name":"Typo","capabilities":["ORDER_WRIT"]}"""))
                }
            }
            assertStatus(HttpStatusCode.NotFound) { client.get("$base/TYPO") { bearerAuth(token) } }
        }

    @Test
    fun `a duplicate code is a conflict rather than an overwrite`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val body = json("""{"code":"HOSTS","name":"Hosts","capabilities":["SELF_READ"]}""")

            assertEquals(
                HttpStatusCode.Created,
                client
                    .post(base) {
                        bearerAuth(token)
                        setBody(body)
                    }.status,
            )
            assertStatus(HttpStatusCode.Conflict) {
                client.post(base) {
                    bearerAuth(token)
                    setBody(body)
                }
            }
        }

    @Test
    fun `a system group's capabilities are fixed but its label is not`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)

            // Editing a seeded set would put the database and the compiled CapabilityBundles
            // permanently out of step, and SchemaManager's reconciling seed would put it back on the
            // next boot — a change that appears to work and silently reverts.
            assertStatus(HttpStatusCode.Forbidden) {
                client.put("$base/CASHIER") {
                    bearerAuth(token)
                    setBody(json("""{"capabilities":["SELF_READ"]}"""))
                }
            }
            assertTrue(
                client.get("$base/CASHIER") { bearerAuth(token) }.bodyAsText().contains("ORDER_WRITE"),
                "the rejected edit must have changed nothing",
            )

            val renamed =
                client.put("$base/CASHIER") {
                    bearerAuth(token)
                    setBody(json("""{"name":"Service"}"""))
                }
            assertEquals(HttpStatusCode.OK, renamed.status)
            assertTrue(renamed.bodyAsText().contains("\"Service\""))
        }

    @Test
    fun `a system group cannot be deleted`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            assertStatus(HttpStatusCode.Forbidden) { client.delete("$base/AUDITOR") { bearerAuth(token) } }
            assertEquals(HttpStatusCode.OK, client.get("$base/AUDITOR") { bearerAuth(token) }.status)
        }

    @Test
    fun `editing a group takes effect on an already-issued token`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createCashier()
            },
        ) { client ->
            val admin = TestAuthHelper.loginAsAdmin(client)
            client.post(base) {
                bearerAuth(admin)
                setBody(json("""{"code":"FLOOR","name":"Floor","capabilities":["SELF_READ","EMPLOYEE_READ"]}"""))
            }
            val cashier = idOf("cashier")
            client.put("/api/employees/$cashier/permission-group") {
                bearerAuth(admin)
                setBody(json("""{"groupCode":"FLOOR"}"""))
            }

            val staff = TestAuthHelper.loginAsCashier(client)
            assertEquals(HttpStatusCode.OK, client.get("/api/employees/summary") { bearerAuth(staff) }.status)

            client.put("$base/FLOOR") {
                bearerAuth(admin)
                setBody(json("""{"capabilities":["SELF_READ"]}"""))
            }

            // The same token, no re-login. A revocation that only lands at the next login is not a
            // revocation — it is the exact defect TerminalCapabilityResolver's conditional eviction
            // shipped once already.
            assertStatus(HttpStatusCode.Forbidden) { client.get("/api/employees/summary") { bearerAuth(staff) } }
        }

    @Test
    fun `a manager cannot reach the group API at all`() =
        testApp(setupData = { TestData.createManager() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)

            // Not EMPLOYEE_WRITE: a manager who may edit staff must not thereby be able to author the
            // groups that decide what staff may do.
            assertStatus(HttpStatusCode.Forbidden) { client.get(base) { bearerAuth(token) } }
            assertStatus(HttpStatusCode.Forbidden) { client.get("$base/capabilities") { bearerAuth(token) } }
            assertStatus(HttpStatusCode.Forbidden) {
                client.post(base) {
                    bearerAuth(token)
                    setBody(json("""{"code":"MINE","name":"Mine","capabilities":[]}"""))
                }
            }
            assertStatus(HttpStatusCode.Forbidden) { client.delete("$base/CASHIER") { bearerAuth(token) } }
        }
}
