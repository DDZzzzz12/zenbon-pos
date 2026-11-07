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

@DisplayName("Product Category Routes")
class ProductCategoryRoutesTest {
    @Nested
    @DisplayName("GET /api/product-categories")
    inner class GetAllCategories {
        @Test
        fun `returns 200 with categories`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createCategory()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/product-categories") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 200 with empty list`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/product-categories") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/product-categories")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/product-categories/{id}")
    inner class GetCategoryById {
        @Test
        fun `returns 400 for invalid ID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/product-categories/invalid") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/product-categories/1")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("POST /api/product-categories")
    inner class CreateCategory {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.post("/api/product-categories") {
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 for invalid request`() =
            testApp(
                setupData = { TestData.createManager() },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val response =
                    client.post("/api/product-categories") {
                        bearerAuth(token)
                        setBody(TextContent("not json", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }

    @Nested
    @DisplayName("PUT /api/product-categories/{id}")
    inner class UpdateCategory {
        @Test
        fun `returns 400 for invalid ID`() =
            testApp(
                setupData = { TestData.createManager() },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val response =
                    client.put("/api/product-categories/invalid") {
                        bearerAuth(token)
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.put("/api/product-categories/1") {
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("DELETE /api/product-categories/{id}")
    inner class DeleteCategory {
        @Test
        fun `returns 400 for invalid ID`() =
            testApp(
                setupData = { TestData.createManager() },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val response = client.delete("/api/product-categories/invalid") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.delete("/api/product-categories/1")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        // QA MD-02: the soft delete hid the category while its products stayed active (orphaned).
        @Test
        fun `returns 409 naming the count while the category holds active products`() {
            var categoryId = 0
            testApp(
                setupData = {
                    TestData.createManager()
                    val category = TestData.createCategory(name = "MD02_Full")
                    categoryId = category.id.value
                    TestData.createProduct(categoryId = categoryId, productNumber = "MD02_A")
                    TestData.createProduct(categoryId = categoryId, productNumber = "MD02_B")
                },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val response = client.delete("/api/product-categories/$categoryId") { bearerAuth(token) }
                assertEquals(HttpStatusCode.Conflict, response.status)
                val body = response.bodyAsText()
                assertTrue("DELETE_HAS_REFERENCES" in body, body)
                assertTrue("2 active product" in body, body)
                val stillThere = client.get("/api/product-categories/$categoryId") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, stillThere.status)
                assertTrue("\"isActive\":true" in stillThere.bodyAsText().replace(" ", ""), "the refused delete must not deactivate it")
            }
        }

        @Test
        fun `returns 204 for an empty category and one holding only inactive products`() {
            var emptyId = 0
            var inactiveOnlyId = 0
            testApp(
                setupData = {
                    TestData.createManager()
                    emptyId = TestData.createCategory(name = "MD02_Empty").id.value
                    inactiveOnlyId = TestData.createCategory(name = "MD02_Retired").id.value
                    TestData.createProduct(categoryId = inactiveOnlyId, productNumber = "MD02_OLD", isActive = false)
                },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                assertEquals(
                    HttpStatusCode.NoContent,
                    client.delete("/api/product-categories/$emptyId") { bearerAuth(token) }.status,
                )
                assertEquals(
                    HttpStatusCode.NoContent,
                    client.delete("/api/product-categories/$inactiveOnlyId") { bearerAuth(token) }.status,
                )
            }
        }

        @Test
        fun `returns 404 for an unknown category`() =
            testApp(
                setupData = { TestData.createManager() },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val response = client.delete("/api/product-categories/987654") { bearerAuth(token) }
                assertEquals(HttpStatusCode.NotFound, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/product-categories/sync/{lastSyncTime}")
    inner class SyncCategories {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/product-categories/sync/2026-01-01T00:00:00")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }
}
