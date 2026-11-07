package org.example.project.routes

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.assertStatus
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("RTable Routes")
class RTableRoutesTest {
    @Nested
    @DisplayName("GET /api/tables")
    inner class GetAllTables {
        @Test
        fun `returns 200 with tables`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createTable("T1", 4)
                    TestData.createTable("T2", 6)
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/tables") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = testJson.parseToJsonElement(response.bodyAsText()).jsonObject
                val tables = body["tables"]?.jsonArray
                assertTrue(tables != null && tables.size >= 2)
            }

        @Test
        fun `returns 400 for invalid status filter`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/tables?status=INVALID") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/tables")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/tables/{id}")
    inner class GetTableById {
        @Test
        fun `returns 200 with table`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createTable("T1", 4)
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/tables/1") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = testJson.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals("T1", body["tableNumber"]?.jsonPrimitive?.content)
            }

        @Test
        fun `returns 404 for non-existent table`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/tables/99999") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.NotFound, response.status)
            }

        @Test
        fun `returns 400 for invalid id`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/tables/invalid") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/tables/1")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("POST /api/tables")
    inner class CreateTable {
        @Test
        fun `returns 201 when creating table`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val requestBody = """{"tableNumber":"T10","seats":6,"locationTypeId":1}"""
                val response =
                    client.post("/api/tables") {
                        bearerAuth(token)
                        setBody(TextContent(requestBody, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Created, response.status)
                val body = testJson.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals("T10", body["tableNumber"]?.jsonPrimitive?.content)
            }

        @Test
        fun `returns 400 for invalid request`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val requestBody = """{"tableNumber":"","seats":6,"locationTypeId":1}"""
                assertStatus(HttpStatusCode.BadRequest) {
                    client.post("/api/tables") {
                        bearerAuth(token)
                        setBody(TextContent(requestBody, ContentType.Application.Json))
                    }
                }
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.post("/api/tables") {
                        setBody(TextContent("""{"tableNumber":"T1","seats":4,"locationTypeId":1}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("PUT /api/tables/{id}")
    inner class UpdateTable {
        @Test
        fun `returns 200 when updating table`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createTable("T1", 4)
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val requestBody = """{"seats":8}"""
                val response =
                    client.put("/api/tables/1") {
                        bearerAuth(token)
                        setBody(TextContent(requestBody, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 400 for invalid id`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val requestBody = """{"seats":8}"""
                val response =
                    client.put("/api/tables/invalid") {
                        bearerAuth(token)
                        setBody(TextContent(requestBody, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.put("/api/tables/1") {
                        setBody(TextContent("""{"seats":8}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("DELETE /api/tables/{id}")
    inner class DeleteTable {
        @Test
        fun `returns 200 when deleting table`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createTable("T1", 4)
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.delete("/api/tables/1") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 400 for invalid id`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.delete("/api/tables/invalid") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.delete("/api/tables/1")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/tables/search")
    inner class SearchTables {
        @Test
        fun `returns 200 with matching tables`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createTable("T1", 4)
                    TestData.createTable("T2", 6)
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/tables/search?q=T1") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 400 when query parameter missing`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/tables/search") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/tables/search?q=T1")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/tables/{id}/currentSession")
    inner class GetCurrentSession {
        @Test
        fun `returns 400 for invalid table id`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/tables/invalid/currentSession") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/tables/1/currentSession")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }
}
