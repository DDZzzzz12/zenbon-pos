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

@DisplayName("Room Element Routes")
class RoomElementRoutesTest {
    @Nested
    @DisplayName("GET /api/room-elements")
    inner class GetAllElements {
        @Test
        fun `returns 200 with empty list`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createAdmin()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/room-elements") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = testJson.parseToJsonElement(response.bodyAsText()).jsonObject
                val elements = body["elements"]?.jsonArray
                assertTrue(elements != null && elements.isEmpty())
            }

        @Test
        fun `returns 400 for invalid type filter`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createAdmin()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/room-elements?type=INVALID") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 400 for invalid locationTypeId`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createAdmin()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/room-elements?locationTypeId=abc") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/room-elements")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("POST /api/room-elements")
    inner class CreateElement {
        @Test
        fun `returns 201 when creating element`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createAdmin()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val requestBody = """{"elementType":"WALL","positionX":10.0,"positionY":20.0,"width":100.0,"height":50.0,"locationTypeId":1}"""
                val response =
                    client.post("/api/room-elements") {
                        bearerAuth(token)
                        setBody(TextContent(requestBody, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Created, response.status)
                val body = testJson.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals("WALL", body["elementType"]?.jsonPrimitive?.content)
            }

        @Test
        fun `returns 400 for invalid element type`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createAdmin()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val requestBody = """{"elementType":"INVALID","positionX":10.0,"positionY":20.0,"width":100.0,"height":50.0,"locationTypeId":1}"""
                assertStatus(HttpStatusCode.BadRequest) {
                    client.post("/api/room-elements") {
                        bearerAuth(token)
                        setBody(TextContent(requestBody, ContentType.Application.Json))
                    }
                }
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.post("/api/room-elements") {
                        setBody(
                            TextContent(
                                """{"elementType":"WALL","positionX":10.0,"positionY":20.0,"width":100.0,"height":50.0,"locationTypeId":1}""",
                                ContentType.Application.Json,
                            ),
                        )
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/room-elements/{id}")
    inner class GetElementById {
        @Test
        fun `returns 404 for non-existent element`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createAdmin()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/room-elements/99999") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.NotFound, response.status)
            }

        @Test
        fun `returns 400 for invalid id`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createAdmin()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/room-elements/invalid") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/room-elements/1")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("PUT /api/room-elements/{id}")
    inner class UpdateElement {
        @Test
        fun `returns 400 for invalid id`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createAdmin()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val requestBody = """{"elementType":"DOOR"}"""
                val response =
                    client.put("/api/room-elements/invalid") {
                        bearerAuth(token)
                        setBody(TextContent(requestBody, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.put("/api/room-elements/1") {
                        setBody(TextContent("""{"elementType":"DOOR"}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("DELETE /api/room-elements/{id}")
    inner class DeleteElement {
        @Test
        fun `returns 400 for invalid id`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createAdmin()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.delete("/api/room-elements/invalid") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.delete("/api/room-elements/1")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("POST + GET + DELETE room element lifecycle")
    inner class ElementLifecycle {
        @Test
        fun `create, get, update, delete lifecycle`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createAdmin()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)

                // Create
                val createBody = """{"elementType":"WALL","positionX":10.0,"positionY":20.0,"width":100.0,"height":50.0,"locationTypeId":1}"""
                val createResponse =
                    client.post("/api/room-elements") {
                        bearerAuth(token)
                        setBody(TextContent(createBody, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Created, createResponse.status)
                val created = testJson.parseToJsonElement(createResponse.bodyAsText()).jsonObject
                val id = created["id"]?.jsonPrimitive?.content

                // Get
                val getResponse =
                    client.get("/api/room-elements/$id") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, getResponse.status)

                // Update
                val updateBody = """{"elementType":"DOOR"}"""
                val updateResponse =
                    client.put("/api/room-elements/$id") {
                        bearerAuth(token)
                        setBody(TextContent(updateBody, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.OK, updateResponse.status)

                // Delete
                val deleteResponse =
                    client.delete("/api/room-elements/$id") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, deleteResponse.status)

                // Verify deleted
                val getAfterDelete =
                    client.get("/api/room-elements/$id") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.NotFound, getAfterDelete.status)
            }
    }

    @Nested
    @DisplayName("GET /api/room-elements/by-type/{type}")
    inner class GetByType {
        @Test
        fun `returns 200 for valid type`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createAdmin()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/room-elements/by-type/WALL") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 400 for invalid type`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createAdmin()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/room-elements/by-type/INVALID") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/room-elements/by-type/WALL")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/room-elements/by-location/{locationTypeId}")
    inner class GetByLocation {
        @Test
        fun `returns 200 for valid locationTypeId`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createAdmin()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/room-elements/by-location/1") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 400 for invalid locationTypeId`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createAdmin()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/room-elements/by-location/invalid") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/room-elements/by-location/1")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }
}
