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

@DisplayName("Product Routes")
class ProductRoutesTest {
    @Nested
    @DisplayName("GET /api/products")
    inner class GetAllProducts {
        @Test
        fun `returns 200 with products`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    val cat = TestData.createCategory()
                    TestData.createProduct(categoryId = cat.id.value)
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/products") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 200 with empty list`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/products") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `supports pagination params`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    val cat = TestData.createCategory()
                    TestData.createProduct(categoryId = cat.id.value, productNumber = "P001")
                    TestData.createProduct(categoryId = cat.id.value, productNumber = "P002", productName = "Product 2")
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/products?limit=1&offset=0") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/products")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/products/search")
    inner class SearchProducts {
        @Test
        fun `returns 400 when query missing`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/products/search") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 200 with search query`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    val cat = TestData.createCategory()
                    TestData.createProduct(categoryId = cat.id.value)
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/products/search?q=Test") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/products/search?q=test")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/products/by-category/{categoryId}")
    inner class GetByCategory {
        @Test
        fun `returns 400 for invalid category ID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/products/by-category/invalid") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 200 for valid category`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    val cat = TestData.createCategory()
                    TestData.createProduct(categoryId = cat.id.value)
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/products/by-category/1") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/products/by-category/1")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/products/by-number/{productNumber}")
    inner class GetByNumber {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/products/by-number/PRD001")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/products/{id}")
    inner class GetProductById {
        @Test
        fun `returns 400 for invalid ID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/products/invalid") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/products/1")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("POST /api/products")
    inner class CreateProduct {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.post("/api/products") {
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
                    client.post("/api/products") {
                        bearerAuth(token)
                        setBody(TextContent("not json", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }

    @Nested
    @DisplayName("PUT /api/products/{id}")
    inner class UpdateProduct {
        @Test
        fun `returns 400 for invalid ID`() =
            testApp(
                setupData = { TestData.createManager() },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val response =
                    client.put("/api/products/invalid") {
                        bearerAuth(token)
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.put("/api/products/1") {
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("DELETE /api/products/{id}")
    inner class DeleteProduct {
        @Test
        fun `returns 400 for invalid ID`() =
            testApp(
                setupData = { TestData.createManager() },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val response = client.delete("/api/products/invalid") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.delete("/api/products/1")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }
}
