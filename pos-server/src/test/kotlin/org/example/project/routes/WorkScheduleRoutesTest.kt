package org.example.project.routes

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@DisplayName("WorkSchedule Routes")
class WorkScheduleRoutesTest {
    @Nested
    @DisplayName("GET /api/employees/{employeeId}/schedule")
    inner class GetWeeklySchedule {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/employees/00000000-0000-0000-0000-000000000001/schedule")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 for invalid employee ID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/employees/invalid-uuid/schedule") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 200 for valid employee`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                // Get admin's ID from the created employee
                val adminId =
                    transaction {
                        org.example.project.features.employee.domain.models.Employee
                            .all()
                            .first()
                            .id.value
                    }
                val response = client.get("/api/employees/$adminId/schedule") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }
    }

    @Nested
    @DisplayName("PUT /api/employees/{employeeId}/schedule/day")
    inner class UpdateDaySchedule {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.put("/api/employees/00000000-0000-0000-0000-000000000001/schedule/day") {
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 for invalid employee ID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val body = """{"dayOfWeek":1,"shifts":[]}"""
                val response =
                    client.put("/api/employees/invalid-uuid/schedule/day") {
                        bearerAuth(token)
                        setBody(TextContent(body, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 400 for invalid request body`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val adminId =
                    transaction {
                        org.example.project.features.employee.domain.models.Employee
                            .all()
                            .first()
                            .id.value
                    }
                val response =
                    client.put("/api/employees/$adminId/schedule/day") {
                        bearerAuth(token)
                        setBody(TextContent("not json", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 200 for valid day update`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val adminId =
                    transaction {
                        org.example.project.features.employee.domain.models.Employee
                            .all()
                            .first()
                            .id.value
                    }
                val body =
                    """
                    {
                        "dayOfWeek": 1,
                        "shifts": [
                            {
                                "startTime": "09:00",
                                "endTime": "17:00",
                                "breakDuration": 30
                            }
                        ]
                    }
                    """.trimIndent()
                val response =
                    client.put("/api/employees/$adminId/schedule/day") {
                        bearerAuth(token)
                        setBody(TextContent(body, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }
    }

    @Nested
    @DisplayName("PUT /api/employees/{employeeId}/schedule")
    inner class UpdateWeekSchedule {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.put("/api/employees/00000000-0000-0000-0000-000000000001/schedule") {
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 for invalid employee ID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val body = """{"days":[]}"""
                val response =
                    client.put("/api/employees/invalid-uuid/schedule") {
                        bearerAuth(token)
                        setBody(TextContent(body, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 400 for invalid request body`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val adminId =
                    transaction {
                        org.example.project.features.employee.domain.models.Employee
                            .all()
                            .first()
                            .id.value
                    }
                val response =
                    client.put("/api/employees/$adminId/schedule") {
                        bearerAuth(token)
                        setBody(TextContent("not json", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 200 for valid week update`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val adminId =
                    transaction {
                        org.example.project.features.employee.domain.models.Employee
                            .all()
                            .first()
                            .id.value
                    }
                val body =
                    """
                    {
                        "days": [
                            {
                                "dayOfWeek": 1,
                                "shifts": [
                                    {
                                        "startTime": "09:00",
                                        "endTime": "17:00",
                                        "breakDuration": 30
                                    }
                                ]
                            }
                        ]
                    }
                    """.trimIndent()
                val response =
                    client.put("/api/employees/$adminId/schedule") {
                        bearerAuth(token)
                        setBody(TextContent(body, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }
    }
}
