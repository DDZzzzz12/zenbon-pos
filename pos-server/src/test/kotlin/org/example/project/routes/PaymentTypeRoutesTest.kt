package org.example.project.routes

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.*
import org.example.project.core.domain.dto.payment.PaymentTypeDto
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("PaymentType Routes")
class PaymentTypeRoutesTest {
    @Nested
    @DisplayName("GET /api/payment-types")
    inner class GetAllPaymentTypes {
        @Test
        fun `returns 200 with payment types`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createPaymentType()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/payment-types") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 200 with includeInactive param`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/payment-types?includeInactive=true") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/payment-types")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/payment-types/active")
    inner class GetActivePaymentTypes {
        @Test
        fun `returns 200 with active payment types`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/payment-types/active") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/payment-types/active")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/payment-types/statistics")
    inner class GetStatistics {
        @Test
        fun `returns 200 with statistics`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/payment-types/statistics") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/payment-types/statistics")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/payment-types/sync/{lastSyncTime}")
    inner class SyncPaymentTypes {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/payment-types/sync/2026-01-01T00:00:00")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 200 with valid sync time`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/payment-types/sync/2026-01-01T00:00:00") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/payment-types/{id}")
    inner class GetPaymentTypeById {
        @Test
        fun `returns 400 for invalid ID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/payment-types/invalid") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/payment-types/1")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("POST /api/payment-types")
    inner class CreatePaymentType {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.post("/api/payment-types") {
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 for invalid request`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.post("/api/payment-types") {
                        bearerAuth(token)
                        setBody(TextContent("not json", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }

    @Nested
    @DisplayName("PUT /api/payment-types/{id}")
    inner class UpdatePaymentType {
        @Test
        fun `returns 400 for invalid ID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.put("/api/payment-types/invalid") {
                        bearerAuth(token)
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.put("/api/payment-types/1") {
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("DELETE /api/payment-types/{id}")
    inner class DeletePaymentType {
        @Test
        fun `returns 400 for invalid ID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.delete("/api/payment-types/invalid") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.delete("/api/payment-types/1")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("creates_receivable round-trips through create and update (4c)")
    inner class ReceivableFlag {
        @Test
        fun `create carries the flag and update toggles it`() =
            testApp(setupData = { TestData.createAdmin() }) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)

                val created: PaymentTypeDto.PaymentTypeResponseDto =
                    client
                        .post("/api/payment-types") {
                            bearerAuth(token)
                            contentType(ContentType.Application.Json)
                            setBody(
                                PaymentTypeDto.CreatePaymentTypeRequestDto(
                                    name = "AUF-RECHNUNG",
                                    isActive = true,
                                    createsReceivable = true,
                                ),
                            )
                        }.body()
                assertEquals(HttpStatusCode.Created.value, 201)
                assertTrue(created.createsReceivable, "a receivable tender must report the flag over the wire")

                val cleared: PaymentTypeDto.PaymentTypeResponseDto =
                    client
                        .put("/api/payment-types/${created.id}") {
                            bearerAuth(token)
                            contentType(ContentType.Application.Json)
                            setBody(PaymentTypeDto.UpdatePaymentTypeRequestDto(createsReceivable = false))
                        }.body()
                assertTrue(!cleared.createsReceivable, "the update must be able to clear the flag")
            }
    }
}
