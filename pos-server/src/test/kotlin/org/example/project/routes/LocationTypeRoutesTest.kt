package org.example.project.routes

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@DisplayName("LocationType Routes")
class LocationTypeRoutesTest {
    @Nested
    @DisplayName("GET /api/location-types")
    inner class GetAll {
        @Test
        fun `returns 200 with location types`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/location-types") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/location-types")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/location-types/{id}")
    inner class GetById {
        @Test
        fun `returns 200 for existing location type`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                // Well-known location type INDOOR has id 1
                val response =
                    client.get("/api/location-types/1") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 404 for non-existent location type`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/location-types/99999") {
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
                    client.get("/api/location-types/invalid") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/location-types/1")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("POST /api/location-types")
    inner class Create {
        @Test
        fun `returns 201 when manager creates`() =
            testApp(
                setupData = { TestData.createManager() },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val requestBody = """{"name":"Terrasse","englishName":"Terrace","chineseName":"露台","isVirtual":false,"isTakeaway":false,"sortOrder":10}"""
                val response =
                    client.post("/api/location-types") {
                        bearerAuth(token)
                        setBody(TextContent(requestBody, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Created, response.status)
                val body = testJson.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals("Terrasse", body["name"]?.jsonPrimitive?.content)
            }

        @Test
        fun `returns 403 when cashier attempts`() =
            testApp(
                setupData = { TestData.createCashier() },
            ) { client ->
                val token = TestAuthHelper.loginAsCashier(client)
                val requestBody = """{"name":"Terrasse","englishName":"Terrace","chineseName":"露台","isVirtual":false,"isTakeaway":false,"sortOrder":10}"""
                val response =
                    client.post("/api/location-types") {
                        bearerAuth(token)
                        setBody(TextContent(requestBody, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Forbidden, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.post("/api/location-types") {
                        setBody(TextContent("""{"name":"Test","englishName":"Test","chineseName":"Test"}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("PUT /api/location-types/{id}")
    inner class Update {
        @Test
        fun `returns 200 when manager updates`() =
            testApp(
                setupData = { TestData.createManager() },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                // Create one first
                val createBody = """{"name":"Terrasse","englishName":"Terrace","chineseName":"露台","sortOrder":10}"""
                val createResponse =
                    client.post("/api/location-types") {
                        bearerAuth(token)
                        setBody(TextContent(createBody, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Created, createResponse.status)
                val created = testJson.parseToJsonElement(createResponse.bodyAsText()).jsonObject
                val id = created["id"]?.jsonPrimitive?.content

                // Update
                val updateBody = """{"name":"Terrasse Updated"}"""
                val response =
                    client.put("/api/location-types/$id") {
                        bearerAuth(token)
                        setBody(TextContent(updateBody, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 403 when cashier attempts`() =
            testApp(
                setupData = { TestData.createCashier() },
            ) { client ->
                val token = TestAuthHelper.loginAsCashier(client)
                val response =
                    client.put("/api/location-types/1") {
                        bearerAuth(token)
                        setBody(TextContent("""{"name":"Updated"}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Forbidden, response.status)
            }

        @Test
        fun `returns 400 for invalid id`() =
            testApp(
                setupData = { TestData.createManager() },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val response =
                    client.put("/api/location-types/invalid") {
                        bearerAuth(token)
                        setBody(TextContent("""{"name":"Updated"}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.put("/api/location-types/1") {
                        setBody(TextContent("""{"name":"Updated"}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("DELETE /api/location-types/{id}")
    inner class Delete {
        @Test
        fun `returns 204 when manager deletes`() =
            testApp(
                setupData = { TestData.createManager() },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                // Create one to delete
                val createBody = """{"name":"ToDelete","englishName":"ToDelete","chineseName":"ToDelete","sortOrder":99}"""
                val createResponse =
                    client.post("/api/location-types") {
                        bearerAuth(token)
                        setBody(TextContent(createBody, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Created, createResponse.status)
                val created = testJson.parseToJsonElement(createResponse.bodyAsText()).jsonObject
                val id = created["id"]?.jsonPrimitive?.content

                val response =
                    client.delete("/api/location-types/$id") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.NoContent, response.status)
            }

        @Test
        fun `returns 403 when cashier attempts`() =
            testApp(
                setupData = { TestData.createCashier() },
            ) { client ->
                val token = TestAuthHelper.loginAsCashier(client)
                val response =
                    client.delete("/api/location-types/1") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.Forbidden, response.status)
            }

        @Test
        fun `returns 400 for invalid id`() =
            testApp(
                setupData = { TestData.createManager() },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val response =
                    client.delete("/api/location-types/invalid") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.delete("/api/location-types/1")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }
}
