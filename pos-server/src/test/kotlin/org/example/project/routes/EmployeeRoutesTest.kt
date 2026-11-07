package org.example.project.routes

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.terminal.domain.TerminalEmployee
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.assertStatus
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@DisplayName("Employee Routes")
class EmployeeRoutesTest {
    @Nested
    @DisplayName("GET /api/employees/me")
    inner class GetMe {
        @Test
        fun `returns 200 with authenticated employee`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/employees/me") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/employees/me")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/employees/info/active")
    inner class GetActiveEmployees {
        @Test
        fun `returns 200 with active employees`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/employees/info/active") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/employees/info/active")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/employees/summary")
    inner class GetSummary {
        @Test
        fun `returns 200 for admin`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/employees/summary") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `supports pagination params`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/employees/summary?limit=10&offset=0&active=true") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/employees/summary")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        /**
         * The shared login-less device account is not a member of staff.
         *
         * It is a device principal — empty password hash, one row however many order stations run —
         * that exists only so an unattended station has a real employee id to attribute orders to.
         * Bootstrap provisions it on every install (`V79` on an upgrade,
         * `SchemaManager.seedTerminalDeviceEmployee` on a fresh one), so this list would otherwise
         * offer it to a manager as a colleague and add one to any headcount taken from it.
         *
         * The row's presence is asserted first, so this cannot pass by the account simply not having
         * been seeded.
         */
        @Test
        fun `omits the shared terminal-device account`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val seeded =
                    transaction {
                        Employee.find { EmployeeTable.username eq TerminalEmployee.USERNAME }.count()
                    }
                assertEquals(1, seeded, "the device account must exist for this test to mean anything")

                val token = TestAuthHelper.loginAsAdmin(client)
                val body = client.get("/api/employees/summary") { bearerAuth(token) }.bodyAsText()

                assertFalse(
                    body.contains(TerminalEmployee.USERNAME) || body.contains(TerminalEmployee.EMPLOYEE_NUMBER),
                    "the staff list named the device account: $body",
                )
            }
    }

    @Nested
    @DisplayName("POST /api/employees")
    inner class CreateEmployee {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.post("/api/employees") {
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 for invalid request body`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.post("/api/employees") {
                        bearerAuth(token)
                        setBody(TextContent("not json", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 201 for valid creation by admin`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val body =
                    """
                    {
                        "username": "newuser",
                        "password": "password123",
                        "role": "CASHIER"
                    }
                    """.trimIndent()
                val response =
                    client.post("/api/employees") {
                        bearerAuth(token)
                        setBody(TextContent(body, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Created, response.status)
            }

        /** admin19 FAIL-A: the personnel number entered at create used to be dropped. */
        @Test
        fun `stores the personnel number given at creation`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val body = """{"username":"withpno","password":"password123","role":"CASHIER","personnelNumber":"K905"}"""
                val response =
                    client.post("/api/employees") {
                        bearerAuth(token)
                        setBody(TextContent(body, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
                val stored =
                    transaction {
                        Employee.find { EmployeeTable.username eq "withpno" }.single().personnelNumber
                    }
                assertEquals("K905", stored)
            }

        @Test
        fun `rejects a personnel number longer than the column`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val tooLong = "P".repeat(21)
                val body = """{"username":"longpno","password":"password123","role":"CASHIER","personnelNumber":"$tooLong"}"""
                val response =
                    client.post("/api/employees") {
                        bearerAuth(token)
                        setBody(TextContent(body, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
            }
    }

    @Nested
    @DisplayName("GET /api/employees/{id}")
    inner class GetById {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/employees/00000000-0000-0000-0000-000000000001")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 for invalid UUID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/employees/invalid-uuid") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 404 for non-existent employee`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                assertStatus(HttpStatusCode.NotFound) {
                    client.get("/api/employees/00000000-0000-0000-0000-000000000099") {
                        bearerAuth(token)
                    }
                }
            }
    }

    @Nested
    @DisplayName("PUT /api/employees/{id}")
    inner class UpdateEmployee {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.put("/api/employees/00000000-0000-0000-0000-000000000001") {
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 for invalid UUID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.put("/api/employees/invalid-uuid") {
                        bearerAuth(token)
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        private fun trainingOf(id: String): Boolean = transaction { Employee.findById(java.util.UUID.fromString(id))!!.trainingMode }

        private fun json(body: String) = TextContent(body, ContentType.Application.Json)

        /**
         * SET-01: the rule is `CapWithOwnership(SELF_WRITE, EMPLOYEE_WRITE)`, so a cashier reaches
         * their own row — and used to flip their own `trainingMode` (200). Training mode is staff
         * management: it now needs EMPLOYEE_WRITE even on one's own record.
         */
        @Test
        fun `a cashier cannot switch their own training mode`() {
            lateinit var cashierId: String
            testApp(setupData = {
                cashierId =
                    TestData
                        .createCashier()
                        .id.value
                        .toString()
            }) { client ->
                val token = TestAuthHelper.loginAsCashier(client)
                val response =
                    client.put("/api/employees/$cashierId") {
                        bearerAuth(token)
                        setBody(json("""{"trainingMode":true}"""))
                    }
                assertEquals(HttpStatusCode.Forbidden, response.status, response.bodyAsText())
                assertFalse(trainingOf(cashierId), "the refused change must not be stored")
            }
        }

        /** A self-edit that echoes the current value back still saves: only a real change is refused. */
        @Test
        fun `a cashier's own edit that leaves training mode unchanged still saves`() {
            lateinit var cashierId: String
            testApp(setupData = {
                cashierId =
                    TestData
                        .createCashier()
                        .id.value
                        .toString()
            }) { client ->
                val token = TestAuthHelper.loginAsCashier(client)
                val response =
                    client.put("/api/employees/$cashierId") {
                        bearerAuth(token)
                        setBody(json("""{"trainingMode":false,"languagePreference":"en"}"""))
                    }
                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            }
        }

        @Test
        fun `a manager may switch a cashier and themselves into training mode`() {
            lateinit var cashierId: String
            lateinit var managerId: String
            testApp(
                setupData = {
                    cashierId =
                        TestData
                            .createCashier()
                            .id.value
                            .toString()
                    managerId =
                        TestData
                            .createManager()
                            .id.value
                            .toString()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val other =
                    client.put("/api/employees/$cashierId") {
                        bearerAuth(token)
                        setBody(json("""{"trainingMode":true}"""))
                    }
                val own =
                    client.put("/api/employees/$managerId") {
                        bearerAuth(token)
                        setBody(json("""{"trainingMode":true}"""))
                    }
                assertEquals(HttpStatusCode.OK, other.status, other.bodyAsText())
                assertEquals(HttpStatusCode.OK, own.status, own.bodyAsText())
                assertEquals(true, trainingOf(cashierId))
                assertEquals(true, trainingOf(managerId))
            }
        }

        @Test
        fun `a cashier cannot switch another employee's training mode`() {
            lateinit var otherId: String
            testApp(
                setupData = {
                    TestData.createCashier()
                    otherId =
                        TestData
                            .createCashier(employeeNumber = "CSH002", username = "cashier2")
                            .id.value
                            .toString()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsCashier(client)
                val response =
                    client.put("/api/employees/$otherId") {
                        bearerAuth(token)
                        setBody(json("""{"trainingMode":true}"""))
                    }
                assertEquals(HttpStatusCode.Forbidden, response.status)
                assertFalse(trainingOf(otherId))
            }
        }

        private fun employee(id: String): Employee = transaction { Employee.findById(java.util.UUID.fromString(id))!! }

        /**
         * SET-01 follow-up: every administrative field of the self-edit DTO needs EMPLOYEE_WRITE, also
         * on one's own record. Each field alone, so one missed field cannot hide behind another.
         */
        @Test
        fun `a cashier cannot change any administrative field of their own record`() {
            lateinit var cashierId: String
            testApp(setupData = {
                cashierId =
                    TestData
                        .createCashier()
                        .id.value
                        .toString()
            }) { client ->
                val token = TestAuthHelper.loginAsCashier(client)
                val before = transaction { employee(cashierId).let { listOf(it.employmentStatus, it.notes, it.personnelNumber) } }
                val bodies =
                    listOf(
                        """{"employmentStatus":"ON_LEAVE"}""",
                        """{"notes":"promote me"}""",
                        """{"personnelNumber":"P-999"}""",
                        """{"defaultCostCenterId":"00000000-0000-0000-0000-0000000000cc"}""",
                    )
                bodies.forEach { body ->
                    val response =
                        client.put("/api/employees/$cashierId") {
                            bearerAuth(token)
                            setBody(json(body))
                        }
                    assertEquals(HttpStatusCode.Forbidden, response.status, "$body -> ${response.bodyAsText()}")
                }
                val after = transaction { employee(cashierId).let { listOf(it.employmentStatus, it.notes, it.personnelNumber) } }
                assertEquals(before, after, "no refused change may be stored")
                assertEquals(null, transaction { employee(cashierId).defaultCostCenterId })
            }
        }

        /** PIN and language are a person's own business; echoing the administrative values back still saves. */
        @Test
        fun `a cashier may change their own PIN and language and echo the rest unchanged`() {
            lateinit var cashierId: String
            testApp(setupData = {
                cashierId =
                    TestData
                        .createCashier()
                        .id.value
                        .toString()
            }) { client ->
                val token = TestAuthHelper.loginAsCashier(client)
                val status = transaction { employee(cashierId).employmentStatus.name }
                val response =
                    client.put("/api/employees/$cashierId") {
                        bearerAuth(token)
                        setBody(json("""{"pin":"4711","languagePreference":"en","employmentStatus":"$status","trainingMode":false}"""))
                    }
                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                assertEquals("en", transaction { employee(cashierId).languagePreference })
            }
        }

        @Test
        fun `a manager may change a cashier's administrative fields`() {
            lateinit var cashierId: String
            testApp(
                setupData = {
                    cashierId =
                        TestData
                            .createCashier()
                            .id.value
                            .toString()
                    TestData.createManager()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val response =
                    client.put("/api/employees/$cashierId") {
                        bearerAuth(token)
                        setBody(json("""{"notes":"Einarbeitung bis Freitag","personnelNumber":"P-123"}"""))
                    }
                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                assertEquals("P-123", transaction { employee(cashierId).personnelNumber })
            }
        }
    }

    @Nested
    @DisplayName("DELETE /api/employees/{id}")
    inner class DeleteEmployee {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.delete("/api/employees/00000000-0000-0000-0000-000000000001")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 for invalid UUID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.delete("/api/employees/invalid-uuid") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }

    @Nested
    @DisplayName("PATCH /api/employees/{id}/deactivate")
    inner class DeactivateEmployee {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.patch("/api/employees/00000000-0000-0000-0000-000000000001/deactivate")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 for invalid UUID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.patch("/api/employees/invalid-uuid/deactivate") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }

    @Nested
    @DisplayName("PATCH /api/employees/{id}/password")
    inner class ChangePassword {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.patch("/api/employees/00000000-0000-0000-0000-000000000001/password") {
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 for invalid UUID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val body = """{"currentPassword":"test1234","newPassword":"newpass123"}"""
                val response =
                    client.patch("/api/employees/invalid-uuid/password") {
                        bearerAuth(token)
                        setBody(TextContent(body, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }

    @Nested
    @DisplayName("PATCH /api/employees/language")
    inner class UpdateLanguage {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.patch("/api/employees/language?languageCode=EN")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 when language code missing`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.patch("/api/employees/language") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 200 with valid language code`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.patch("/api/employees/language?languageCode=EN") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }
    }

    @Nested
    @DisplayName("PATCH /api/employees/me/pin")
    inner class ChangePin {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.patch("/api/employees/me/pin?pin=1234")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 when pin missing`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.patch("/api/employees/me/pin") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 400 for invalid pin format`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.patch("/api/employees/me/pin?pin=ab") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 200 for valid pin`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.patch("/api/employees/me/pin?pin=1234") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }
    }

    @Nested
    @DisplayName("POST /api/employees/authenticate")
    inner class AuthenticateEmployee {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val body = """{"username":"admin","password":"test1234"}"""
                val response =
                    client.post("/api/employees/authenticate") {
                        setBody(TextContent(body, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 200 with valid credentials`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val body = """{"username":"admin","password":"test1234"}"""
                val response =
                    client.post("/api/employees/authenticate") {
                        bearerAuth(token)
                        setBody(TextContent(body, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 401 with invalid credentials`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val body = """{"username":"admin","password":"wrongpassword"}"""
                val response =
                    client.post("/api/employees/authenticate") {
                        bearerAuth(token)
                        setBody(TextContent(body, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("POST /api/employees/{id}/reset-password")
    inner class ResetPassword {
        private fun idOf(username: String) =
            transaction {
                Employee
                    .find { EmployeeTable.username eq username }
                    .first()
                    .id.value
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.post("/api/employees/00000000-0000-0000-0000-000000000001/reset-password") {
                        setBody(TextContent("""{"newPassword":"newpass123"}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 200 when a manager resets a cashier's password`() =
            testApp(
                setupData = {
                    TestData.createManager()
                    TestData.createCashier()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val cashierId = idOf("cashier")
                val response =
                    client.post("/api/employees/$cashierId/reset-password") {
                        bearerAuth(token)
                        setBody(TextContent("""{"newPassword":"newpass123"}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 403 for a non-manager actor`() =
            testApp(
                setupData = {
                    TestData.createCashier()
                    TestData.createCashier(employeeNumber = "CSH002", username = "cashier2")
                },
            ) { client ->
                val token = TestAuthHelper.loginAsCashier(client)
                val targetId = idOf("cashier2")
                assertStatus(HttpStatusCode.Forbidden) {
                    client.post("/api/employees/$targetId/reset-password") {
                        bearerAuth(token)
                        setBody(TextContent("""{"newPassword":"newpass123"}""", ContentType.Application.Json))
                    }
                }
            }

        @Test
        fun `returns 400 for a password shorter than 6 characters`() =
            testApp(
                setupData = {
                    TestData.createManager()
                    TestData.createCashier()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val cashierId = idOf("cashier")
                val response =
                    client.post("/api/employees/$cashierId/reset-password") {
                        bearerAuth(token)
                        setBody(TextContent("""{"newPassword":"abc"}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 403 when a manager resets an admin's password`() =
            testApp(
                setupData = {
                    TestData.createManager()
                    TestData.createAdmin()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val adminId = idOf("admin")
                assertStatus(HttpStatusCode.Forbidden) {
                    client.post("/api/employees/$adminId/reset-password") {
                        bearerAuth(token)
                        setBody(TextContent("""{"newPassword":"newpass123"}""", ContentType.Application.Json))
                    }
                }
            }
    }

    @Nested
    @DisplayName("POST /api/employees/{id}/reset-pin")
    inner class ResetPin {
        private fun idOf(username: String) =
            transaction {
                Employee
                    .find { EmployeeTable.username eq username }
                    .first()
                    .id.value
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.post("/api/employees/00000000-0000-0000-0000-000000000001/reset-pin") {
                        setBody(TextContent("""{"pin":"1234"}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 200 for a valid pin reset`() =
            testApp(
                setupData = {
                    TestData.createManager()
                    TestData.createCashier()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val cashierId = idOf("cashier")
                val response =
                    client.post("/api/employees/$cashierId/reset-pin") {
                        bearerAuth(token)
                        setBody(TextContent("""{"pin":"1234"}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 400 for an invalid pin format`() =
            testApp(
                setupData = {
                    TestData.createManager()
                    TestData.createCashier()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val cashierId = idOf("cashier")
                val response =
                    client.post("/api/employees/$cashierId/reset-pin") {
                        bearerAuth(token)
                        setBody(TextContent("""{"pin":"12"}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 403 for a non-manager actor`() =
            testApp(
                setupData = {
                    TestData.createCashier()
                    TestData.createCashier(employeeNumber = "CSH002", username = "cashier2")
                },
            ) { client ->
                val token = TestAuthHelper.loginAsCashier(client)
                val targetId = idOf("cashier2")
                assertStatus(HttpStatusCode.Forbidden) {
                    client.post("/api/employees/$targetId/reset-pin") {
                        bearerAuth(token)
                        setBody(TextContent("""{"pin":"1234"}""", ContentType.Application.Json))
                    }
                }
            }

        @Test
        fun `returns 403 when a manager resets an admin's pin`() =
            testApp(
                setupData = {
                    TestData.createManager()
                    TestData.createAdmin()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val adminId = idOf("admin")
                assertStatus(HttpStatusCode.Forbidden) {
                    client.post("/api/employees/$adminId/reset-pin") {
                        bearerAuth(token)
                        setBody(TextContent("""{"pin":"1234"}""", ContentType.Application.Json))
                    }
                }
            }
    }
}
