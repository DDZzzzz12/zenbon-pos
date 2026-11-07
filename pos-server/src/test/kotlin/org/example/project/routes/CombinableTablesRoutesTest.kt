package org.example.project.routes

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.example.project.core.domain.constants.WellKnownLocationTypes
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("Combinable Tables Routes")
class CombinableTablesRoutesTest {
    @Nested
    @DisplayName("GET /api/tables/combinable")
    inner class GetAllCombinablePairs {
        @Test
        fun `returns 200 with all pairs`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    val t1 = TestData.createTable("T1", 4)
                    val t2 = TestData.createTable("T2", 4)
                    val t3 = TestData.createTable("T3", 4)
                    TestData.createCombinablePair(t1.id.value, t2.id.value)
                    TestData.createCombinablePair(t2.id.value, t3.id.value)
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/tables/combinable") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val pairs = testJson.parseToJsonElement(response.bodyAsText()).jsonArray
                assertEquals(2, pairs.size)
            }

        @Test
        fun `returns empty when no pairs configured`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createTable("T1", 4)
                    TestData.createTable("T2", 4)
                    // No combinable pairs created
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/tables/combinable") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val pairs = testJson.parseToJsonElement(response.bodyAsText()).jsonArray
                assertTrue(pairs.isEmpty())
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/tables/combinable")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/tables/{id}/combinable")
    inner class GetPartnersForTable {
        @Test
        fun `returns partner IDs for table`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    val t1 = TestData.createTable("T1", 4)
                    val t2 = TestData.createTable("T2", 4)
                    val t3 = TestData.createTable("T3", 4)
                    TestData.createCombinablePair(t1.id.value, t2.id.value)
                    TestData.createCombinablePair(t1.id.value, t3.id.value)
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/tables/1/combinable") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val partners = testJson.parseToJsonElement(response.bodyAsText()).jsonArray
                assertEquals(2, partners.size)
            }

        @Test
        fun `returns empty when no partners`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createTable("T1", 4)
                    TestData.createTable("T2", 4)
                    // No pairs for T1
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/tables/1/combinable") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val partners = testJson.parseToJsonElement(response.bodyAsText()).jsonArray
                assertTrue(partners.isEmpty())
            }

        @Test
        fun `returns 400 for invalid table ID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/tables/invalid/combinable") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/tables/1/combinable")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("PUT /api/tables/{id}/combinable")
    inner class SetCombinablePartners {
        @Test
        fun `returns 200 when manager sets partners`() =
            testApp(
                setupData = {
                    TestData.createManager()
                    TestData.createTable("T1", 4)
                    TestData.createTable("T2", 4)
                    TestData.createTable("T3", 4)
                },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                // Set T2 and T3 as partners of T1
                val requestBody = "[2, 3]"
                val response =
                    client.put("/api/tables/1/combinable") {
                        bearerAuth(token)
                        setBody(TextContent(requestBody, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 403 when cashier attempts`() =
            testApp(
                setupData = {
                    TestData.createCashier()
                    TestData.createTable("T1", 4)
                    TestData.createTable("T2", 4)
                },
            ) { client ->
                val token = TestAuthHelper.loginAsCashier(client)
                val requestBody = "[2]"
                val response =
                    client.put("/api/tables/1/combinable") {
                        bearerAuth(token)
                        setBody(TextContent(requestBody, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Forbidden, response.status)
            }

        @Test
        fun `returns error when combining with self`() =
            testApp(
                setupData = {
                    TestData.createManager()
                    TestData.createTable("T1", 4)
                },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                // Trying to combine T1 (id=1) with itself
                val requestBody = "[1]"
                val response =
                    client.put("/api/tables/1/combinable") {
                        bearerAuth(token)
                        setBody(TextContent(requestBody, ContentType.Application.Json))
                    }
                assertTrue(response.status.value in 400..499)
            }

        @Test
        fun `returns error when combining with virtual table`() =
            testApp(
                setupData = {
                    TestData.createManager()
                    TestData.createTable("T1", 4, WellKnownLocationTypes.INDOOR)
                    TestData.createTable("VOUCHER", 0, WellKnownLocationTypes.VOUCHER)
                },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                // Trying to combine T1 (id=1) with VOUCHER table (id=2) — VOUCHER is virtual
                val requestBody = "[2]"
                val response =
                    client.put("/api/tables/1/combinable") {
                        bearerAuth(token)
                        setBody(TextContent(requestBody, ContentType.Application.Json))
                    }
                assertTrue(response.status.value in 400..499)
            }

        @Test
        fun `replaces existing partners on subsequent call`() =
            testApp(
                setupData = {
                    TestData.createManager()
                    val t1 = TestData.createTable("T1", 4)
                    val t2 = TestData.createTable("T2", 4)
                    val t3 = TestData.createTable("T3", 4)
                    // Initial pair: T1 <-> T2
                    TestData.createCombinablePair(t1.id.value, t2.id.value)
                },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)

                // Replace T1's partners with only T3
                val requestBody = "[3]"
                val updateResponse =
                    client.put("/api/tables/1/combinable") {
                        bearerAuth(token)
                        setBody(TextContent(requestBody, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.OK, updateResponse.status)

                // Verify T1 now only has T3 as partner
                val getResponse =
                    client.get("/api/tables/1/combinable") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, getResponse.status)
                val partners = testJson.parseToJsonElement(getResponse.bodyAsText()).jsonArray
                assertEquals(1, partners.size)
                assertEquals(
                    3L,
                    partners
                        .first()
                        .jsonPrimitive.content
                        .toLong(),
                )
            }

        @Test
        fun `can set empty partner list to remove all partners`() =
            testApp(
                setupData = {
                    TestData.createManager()
                    val t1 = TestData.createTable("T1", 4)
                    val t2 = TestData.createTable("T2", 4)
                    TestData.createCombinablePair(t1.id.value, t2.id.value)
                },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)

                // Clear T1's partners
                val requestBody = "[]"
                val updateResponse =
                    client.put("/api/tables/1/combinable") {
                        bearerAuth(token)
                        setBody(TextContent(requestBody, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.OK, updateResponse.status)

                // Verify no partners remain
                val getResponse =
                    client.get("/api/tables/1/combinable") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, getResponse.status)
                val partners = testJson.parseToJsonElement(getResponse.bodyAsText()).jsonArray
                assertTrue(partners.isEmpty())
            }

        @Test
        fun `returns 400 for invalid table ID`() =
            testApp(
                setupData = { TestData.createManager() },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val response =
                    client.put("/api/tables/invalid/combinable") {
                        bearerAuth(token)
                        setBody(TextContent("[1]", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.put("/api/tables/1/combinable") {
                        setBody(TextContent("[2]", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }
}
