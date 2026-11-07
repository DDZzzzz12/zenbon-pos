package org.example.project.routes

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("Product Extras Assignment Routes")
class ProductExtrasAssignmentRoutesTest {
    @Nested
    @DisplayName("GET /api/product-extras-assignment")
    inner class GetAllAssignments {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/product-extras-assignment")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 200 with valid auth`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/product-extras-assignment") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 200 for cashier role`() =
            testApp(
                setupData = { TestData.createCashier() },
            ) { client ->
                val token = TestAuthHelper.loginAsCashier(client)
                val response = client.get("/api/product-extras-assignment") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/product-extras-assignment/product/{productId}")
    inner class GetAssignmentsForProduct {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/product-extras-assignment/product/1")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 for invalid product ID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/product-extras-assignment/product/invalid") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 200 for valid product ID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/product-extras-assignment/product/1") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 200 for cashier role`() =
            testApp(
                setupData = { TestData.createCashier() },
            ) { client ->
                val token = TestAuthHelper.loginAsCashier(client)
                val response = client.get("/api/product-extras-assignment/product/1") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/product-extras-assignment/sync/{lastSyncTime}")
    inner class SyncAssignments {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/product-extras-assignment/sync/2026-01-01T00:00:00")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 200 for valid sync time`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/product-extras-assignment/sync/2026-01-01T00:00:00") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 200 for cashier role`() =
            testApp(
                setupData = { TestData.createCashier() },
            ) { client ->
                val token = TestAuthHelper.loginAsCashier(client)
                val response = client.get("/api/product-extras-assignment/sync/2026-01-01T00:00:00") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }
    }

    @Nested
    @DisplayName("POST /api/product-extras-assignment")
    inner class AssignExtrasToProduct {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.post("/api/product-extras-assignment") {
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 for invalid request body`() =
            testApp(
                setupData = { TestData.createManager() },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val response =
                    client.post("/api/product-extras-assignment") {
                        bearerAuth(token)
                        setBody(TextContent("not json", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }

    @Nested
    @DisplayName("POST /api/product-extras-assignment/bulk-category")
    inner class BulkAssignToCategory {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.post("/api/product-extras-assignment/bulk-category") {
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 for invalid request body`() =
            testApp(
                setupData = { TestData.createManager() },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val response =
                    client.post("/api/product-extras-assignment/bulk-category") {
                        bearerAuth(token)
                        setBody(TextContent("not json", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }

    @Nested
    @DisplayName("DELETE /api/product-extras-assignment/{productId}/{extrasId}")
    inner class RemoveExtrasFromProduct {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.delete("/api/product-extras-assignment/1/1")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 for invalid product ID`() =
            testApp(
                setupData = { TestData.createManager() },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val response = client.delete("/api/product-extras-assignment/invalid/1") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 400 for invalid extras ID`() =
            testApp(
                setupData = { TestData.createManager() },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val response = client.delete("/api/product-extras-assignment/1/invalid") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        // emu20 FAIL-A: an unknown pair escaped the service as NoSuchElementException → 500.
        @Test
        fun `returns 404 for an assignment that does not exist`() =
            testApp(
                setupData = { TestData.createManager() },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val response = client.delete("/api/product-extras-assignment/999999/999999") { bearerAuth(token) }
                assertEquals(HttpStatusCode.NotFound, response.status)
                assertTrue(response.bodyAsText().contains("RESOURCE_NOT_FOUND"), response.bodyAsText())
            }
    }

    @Nested
    @DisplayName("DELETE /api/product-extras-assignment/bulk-category/{categoryId}/{extrasId}")
    inner class BulkRemoveFromCategory {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.delete("/api/product-extras-assignment/bulk-category/1/1")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 for invalid category ID`() =
            testApp(
                setupData = { TestData.createManager() },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val response = client.delete("/api/product-extras-assignment/bulk-category/invalid/1") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 400 for invalid extras ID`() =
            testApp(
                setupData = { TestData.createManager() },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val response = client.delete("/api/product-extras-assignment/bulk-category/1/invalid") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }
}
