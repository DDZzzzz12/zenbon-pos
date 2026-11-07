package org.example.project.routes

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.http.content.*
import org.example.project.core.error.ProblemDetail
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.terminal.domain.TerminalEmployee
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@DisplayName("Employee Rotation Pool Routes")
class EmployeeRotationPoolRoutesTest {
    @Nested
    @DisplayName("PATCH /api/employees/{id}/rotation-pool")
    inner class PatchRotationPool {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.patch("/api/employees/00000000-0000-0000-0000-000000000001/rotation-pool") {
                        setBody(TextContent("""{"inRotationPool":true}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `manager can toggle rotation pool`() =
            testApp(
                setupData = { TestData.createManager() },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                // Bootstrap always seeds the shared terminal-device account (SchemaManager.bootstrapInitialData),
                // so `Employee.all().first()` is not reliably the manager created above; target it by username.
                val targetId =
                    transaction {
                        Employee
                            .find { EmployeeTable.username eq "manager" }
                            .first()
                            .id.value
                    }
                val response =
                    client.patch("/api/employees/$targetId/rotation-pool") {
                        bearerAuth(token)
                        setBody(TextContent("""{"inRotationPool":true}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            }

        @Test
        fun `cashier is forbidden from changing rotation pool`() =
            testApp(
                setupData = { TestData.createCashier() },
            ) { client ->
                val token = TestAuthHelper.loginAsCashier(client)
                val targetId =
                    transaction {
                        Employee
                            .all()
                            .first()
                            .id.value
                    }
                val response =
                    client.patch("/api/employees/$targetId/rotation-pool") {
                        bearerAuth(token)
                        setBody(TextContent("""{"inRotationPool":true}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Forbidden, response.status)
            }

        @Test
        fun `returns 400 for invalid employee ID`() =
            testApp(
                setupData = { TestData.createManager() },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val response =
                    client.patch("/api/employees/invalid-uuid/rotation-pool") {
                        bearerAuth(token)
                        setBody(TextContent("""{"inRotationPool":true}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `a terminal account cannot join the rotation pool`() =
            testApp(
                setupData = {
                    TestData.createManager()
                    TestData.createTerminalDeviceEmployee()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val terminalId =
                    transaction {
                        Employee
                            .find { EmployeeTable.username eq TerminalEmployee.USERNAME }
                            .first()
                            .id.value
                    }

                val response =
                    client.patch("/api/employees/$terminalId/rotation-pool") {
                        bearerAuth(token)
                        setBody(TextContent("""{"inRotationPool":true}""", ContentType.Application.Json))
                    }

                assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
                assertEquals("VALIDATION_FAILED", testJson.decodeFromString<ProblemDetail>(response.bodyAsText()).code)
                assertFalse(transaction { Employee.findById(terminalId)!!.inRotationPool })
            }

        @Test
        fun `a terminal account can always be taken out of the pool`() =
            testApp(
                setupData = {
                    TestData.createManager()
                    TestData.createTerminalDeviceEmployee()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val terminalId =
                    transaction {
                        val terminal = Employee.find { EmployeeTable.username eq TerminalEmployee.USERNAME }.first()
                        // A row flagged before the guard existed must stay cleanable.
                        terminal.inRotationPool = true
                        terminal.id.value
                    }

                val response =
                    client.patch("/api/employees/$terminalId/rotation-pool") {
                        bearerAuth(token)
                        setBody(TextContent("""{"inRotationPool":false}""", ContentType.Application.Json))
                    }

                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                assertFalse(transaction { Employee.findById(terminalId)!!.inRotationPool })
            }
    }
}
