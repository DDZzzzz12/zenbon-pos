package org.example.project.routes

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.core.error.ProblemDetail
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.table.domain.models.TableSession
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.assertStatus
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Server-side guards for privileged role/status changes on employees.
 *
 * Mirrors the adminApp `StaffViewModel` client guards (feature/wasm-admin):
 * a user cannot change their own role, and the last active ADMIN cannot be
 * demoted or deactivated. Guards must exist at both client and server levels
 * (root CLAUDE.md rule 8). The terminal-role refusal mirrors `EmployeeEditorDialog`,
 * which lists TERMINAL_DEVICE disabled.
 */
@DisplayName("Employee Admin Guards")
class EmployeeAdminGuardsRoutesTest {
    private fun idOf(username: String) =
        transaction {
            Employee
                .find { EmployeeTable.username eq username }
                .first()
                .id.value
        }

    @Nested
    @DisplayName("PUT /api/employees/{id} — role change")
    inner class RoleChange {
        @Test
        fun `rejects an admin changing their own role`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createAdmin(employeeNumber = "ADM002", username = "admin2")
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val selfId = idOf("admin")
                assertStatus(HttpStatusCode.Conflict) {
                    client.put("/api/employees/$selfId") {
                        bearerAuth(token)
                        setBody(TextContent("""{"role":"CASHIER"}""", ContentType.Application.Json))
                    }
                }
            }

        @Test
        fun `rejects demoting the last active admin`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createManager()
                },
            ) { client ->
                // Only an admin may act on an admin record, so the sole admin is the actor here.
                val token = TestAuthHelper.loginAsAdmin(client)
                val adminId = idOf("admin")
                assertStatus(HttpStatusCode.Conflict) {
                    client.put("/api/employees/$adminId") {
                        bearerAuth(token)
                        setBody(TextContent("""{"role":"CASHIER"}""", ContentType.Application.Json))
                    }
                }
            }

        @Test
        fun `refuses a manager changing an admin's role`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createAdmin(employeeNumber = "ADM002", username = "admin2")
                    TestData.createManager()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val otherAdminId = idOf("admin2")
                assertStatus(HttpStatusCode.Forbidden) {
                    client.put("/api/employees/$otherAdminId") {
                        bearerAuth(token)
                        setBody(TextContent("""{"role":"CASHIER"}""", ContentType.Application.Json))
                    }
                }
                assertStatus(HttpStatusCode.Forbidden) {
                    client.put("/api/employees/$otherAdminId") {
                        bearerAuth(token)
                        setBody(TextContent("""{"notes":"x"}""", ContentType.Application.Json))
                    }
                }
            }

        @Test
        fun `allows demoting an admin when another active admin exists`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createAdmin(employeeNumber = "ADM002", username = "admin2")
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val otherAdminId = idOf("admin2")
                assertStatus(HttpStatusCode.OK) {
                    client.put("/api/employees/$otherAdminId") {
                        bearerAuth(token)
                        setBody(TextContent("""{"role":"MANAGER"}""", ContentType.Application.Json))
                    }
                }
            }

        @Test
        fun `stores a blank note and a blank personnel number as null`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val selfId = idOf("admin")
                assertStatus(HttpStatusCode.OK) {
                    client.put("/api/employees/$selfId") {
                        bearerAuth(token)
                        setBody(TextContent("""{"notes":"x","personnelNumber":"P-1"}""", ContentType.Application.Json))
                    }
                }
                assertStatus(HttpStatusCode.OK) {
                    client.put("/api/employees/$selfId") {
                        bearerAuth(token)
                        setBody(TextContent("""{"notes":"  ","personnelNumber":" "}""", ContentType.Application.Json))
                    }
                }
                transaction {
                    val stored = Employee.findById(selfId)!!
                    assertNull(stored.notes)
                    assertNull(stored.personnelNumber)
                }
            }

        @Test
        fun `allows a self update that does not change role`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val selfId = idOf("admin")
                assertStatus(HttpStatusCode.OK) {
                    client.put("/api/employees/$selfId") {
                        bearerAuth(token)
                        setBody(TextContent("""{"languagePreference":"EN"}""", ContentType.Application.Json))
                    }
                }
            }
    }

    /**
     * TERMINAL_DEVICE belongs to the shared order-station account alone (spec 2026-09-14 §10.5): a
     * terminal account never owns a table session, so no person may be created with or moved onto it.
     */
    @Nested
    @DisplayName("PUT/POST /api/employees — terminal role")
    inner class TerminalRole {
        private suspend fun assertValidationFailed(response: HttpResponse) {
            val body = response.bodyAsText()
            assertEquals(HttpStatusCode.BadRequest, response.status, body)
            assertEquals("VALIDATION_FAILED", testJson.decodeFromString<ProblemDetail>(body).code)
        }

        @Test
        fun `refuses moving a table session owner onto the terminal role`() =
            testApp(
                setupData = {
                    TestData.createManager()
                    // The owner is cashier `tbl_T1`, with an active session on table T1.
                    TestData.createTableWithSession(tableNumber = "T1")
                },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val ownerId = idOf("tbl_T1")

                val response =
                    client.put("/api/employees/$ownerId") {
                        bearerAuth(token)
                        setBody(TextContent("""{"role":"TERMINAL_DEVICE"}""", ContentType.Application.Json))
                    }

                assertValidationFailed(response)
                transaction {
                    assertEquals(EmployeeRole.CASHIER, Employee.findById(ownerId)!!.role)
                    val session = TableSession.all().single { it.employeeId.id.value == ownerId }
                    assertFalse(session.completed)
                }
            }

        @Test
        fun `refuses creating an employee with the terminal role`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)

                val response =
                    client.post("/api/employees") {
                        bearerAuth(token)
                        setBody(
                            TextContent(
                                """{"username":"newterminal","password":"secret123","role":"TERMINAL_DEVICE"}""",
                                ContentType.Application.Json,
                            ),
                        )
                    }

                assertValidationFailed(response)
                assertNull(transaction { Employee.find { EmployeeTable.username eq "newterminal" }.firstOrNull() })
            }

        @Test
        fun `refuses a create whose role string would fall back to the terminal role`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)

                // `EmployeeRole.getRole` resolves the legacy alias — and any unknown string — to TERMINAL_DEVICE.
                val response =
                    client.post("/api/employees") {
                        bearerAuth(token)
                        setBody(
                            TextContent(
                                """{"username":"legacyalias","password":"secret123","role":"TERMINAL"}""",
                                ContentType.Application.Json,
                            ),
                        )
                    }

                assertValidationFailed(response)
                assertNull(transaction { Employee.find { EmployeeTable.username eq "legacyalias" }.firstOrNull() })
            }

        /**
         * e2e (back office): creating an employee with role AUDITOR answered "TERMINAL_DEVICE is
         * reserved for the shared order-station account" — the unknown string had fallen back to the
         * terminal role. AUDITOR is a permission GROUP, not a role; the refusal must say so.
         */
        @Test
        fun `refuses a permission-group code as a role and names the real rule`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)

                val response =
                    client.post("/api/employees") {
                        bearerAuth(token)
                        setBody(
                            TextContent(
                                """{"username":"auditor1","password":"secret123","role":"AUDITOR"}""",
                                ContentType.Application.Json,
                            ),
                        )
                    }

                val body = response.bodyAsText()
                assertValidationFailed(response)
                val detail = testJson.decodeFromString<ProblemDetail>(body).detail.orEmpty()
                assertFalse("TERMINAL_DEVICE" in detail, detail)
                assertTrue("'AUDITOR' is not an employee role" in detail, detail)
                assertTrue("CASHIER, MANAGER, ADMIN" in detail, detail)
                assertTrue("is a permission group" in detail, detail)
                assertNull(transaction { Employee.find { EmployeeTable.username eq "auditor1" }.firstOrNull() })
            }

        @Test
        fun `refuses an unknown role on update with the same message`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createCashier()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val cashierId = idOf("cashier")

                val response =
                    client.put("/api/employees/$cashierId") {
                        bearerAuth(token)
                        setBody(TextContent("""{"role":"NOPE"}""", ContentType.Application.Json))
                    }

                val body = response.bodyAsText()
                assertValidationFailed(response)
                val detail = testJson.decodeFromString<ProblemDetail>(body).detail.orEmpty()
                assertTrue("'NOPE' is not an employee role" in detail, detail)
                assertFalse("permission group" in detail, detail)
            }

        @Test
        fun `a person still holding the terminal role can be edited and moved off it`() =
            testApp(
                setupData = {
                    TestData.createManager()
                    TestData.createTerminal()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val holderId = idOf("terminal")

                // The editor always sends the role; an unchanged one must not trip the guard.
                val unchanged =
                    client.put("/api/employees/$holderId") {
                        bearerAuth(token)
                        setBody(TextContent("""{"role":"TERMINAL_DEVICE","notes":"legacy"}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.OK, unchanged.status, unchanged.bodyAsText())

                val moved =
                    client.put("/api/employees/$holderId") {
                        bearerAuth(token)
                        setBody(TextContent("""{"role":"CASHIER"}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.OK, moved.status, moved.bodyAsText())
                assertEquals(EmployeeRole.CASHIER, transaction { Employee.findById(holderId)!!.role })
            }
    }

    @Nested
    @DisplayName("PATCH /api/employees/{id}/deactivate — last admin")
    inner class Deactivate {
        @Test
        fun `rejects deactivating the last active admin`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createManager()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val adminId = idOf("admin")
                assertStatus(HttpStatusCode.Conflict) {
                    client.patch("/api/employees/$adminId/deactivate") { bearerAuth(token) }
                }
            }

        @Test
        fun `refuses a manager deactivating or deleting an admin`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createAdmin(employeeNumber = "ADM002", username = "admin2")
                    TestData.createManager()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val otherAdminId = idOf("admin2")
                assertStatus(HttpStatusCode.Forbidden) {
                    client.patch("/api/employees/$otherAdminId/deactivate") { bearerAuth(token) }
                }
                assertStatus(HttpStatusCode.Forbidden) {
                    client.delete("/api/employees/$otherAdminId") { bearerAuth(token) }
                }
            }

        @Test
        fun `refuses a manager assigning a permission group to an admin`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createAdmin(employeeNumber = "ADM002", username = "admin2")
                    TestData.createManager()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val otherAdminId = idOf("admin2")
                val response =
                    client.put("/api/employees/$otherAdminId/permission-group") {
                        bearerAuth(token)
                        setBody(TextContent("""{"groupCode":"CASHIER"}""", ContentType.Application.Json))
                    }
                // 403 either from the route rule (PERMISSION_ADMIN) or the admin-target guard.
                assertEquals(HttpStatusCode.Forbidden, response.status)
            }

        @Test
        fun `allows deactivating an admin when another active admin exists`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createAdmin(employeeNumber = "ADM002", username = "admin2")
                    TestData.createManager()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val otherAdminId = idOf("admin2")
                assertStatus(HttpStatusCode.OK) {
                    client.patch("/api/employees/$otherAdminId/deactivate") { bearerAuth(token) }
                }
            }
    }
}
