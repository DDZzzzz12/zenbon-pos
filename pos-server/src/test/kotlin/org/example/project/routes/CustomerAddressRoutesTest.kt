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

@DisplayName("Customer Address Routes")
class CustomerAddressRoutesTest {
    @Nested
    @DisplayName("GET /api/customer-addresses")
    inner class GetAllAddresses {
        @Test
        fun `returns 200 with addresses`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/customer-addresses") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `supports pagination params`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/customer-addresses?limit=10&offset=0") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/customer-addresses")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/customer-addresses/customer/{customerId}")
    inner class GetAddressesByCustomer {
        @Test
        fun `returns 400 for invalid customer ID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/customer-addresses/customer/invalid-uuid") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/customer-addresses/customer/00000000-0000-0000-0000-000000000001")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("POST /api/customer-addresses")
    inner class CreateAddress {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.post("/api/customer-addresses") {
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
                    client.post("/api/customer-addresses") {
                        bearerAuth(token)
                        setBody(TextContent("not json", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }
}
