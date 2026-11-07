package org.example.project.features.salesinvoice

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import org.example.project.core.domain.dto.datevxml.DatevArExportDto
import org.example.project.core.domain.dto.salesinvoice.SalesInvoiceDto
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@DisplayName("Standalone sales invoice routes")
class SalesInvoiceStandaloneRoutesTest {
    private fun request() =
        SalesInvoiceDto.CreateStandaloneRequestDto(
            buyerName = "Musterfirma GmbH",
            buyerStreet = "Industriestr. 5",
            buyerZip = "10117",
            buyerCity = "Berlin",
            serviceDate = "2026-08-16T10:00:00",
            lines =
                listOf(
                    SalesInvoiceDto.StandaloneLineInputDto(
                        itemName = "Raummiete",
                        quantity = "1",
                        grossUnitPrice = "119.00",
                        vatRate = "19.00",
                    ),
                ),
        )

    @Test
    fun `POST standalone returns 201 with a STANDALONE draft`() =
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)

            val response =
                client.post("/api/v1/sales-invoices/standalone") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request())
                }

            assertEquals(HttpStatusCode.Created, response.status)
            val dto: SalesInvoiceDto.ResponseDto = response.body()
            assertEquals("DRAFT", dto.status)
            assertEquals("STANDALONE", dto.revenueOrigin)
            assertEquals("119.0000", dto.taxInclusiveTotal, "totals are derived server-side")
            assertEquals("Raummiete", dto.lines.single().itemName)
        }

    @Test
    fun `POST standalone with a blank buyer is a 400`() =
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)

            val response =
                client.post("/api/v1/sales-invoices/standalone") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request().copy(buyerName = "  "))
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `PUT replaces the draft content and DELETE removes it`() =
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val created: SalesInvoiceDto.ResponseDto =
                client
                    .post("/api/v1/sales-invoices/standalone") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody(request())
                    }.body()

            val updated =
                client.put("/api/v1/sales-invoices/${created.id}") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        SalesInvoiceDto.UpdateStandaloneRequestDto(
                            buyerName = "Neue Firma GmbH",
                            serviceDate = "2026-08-17T10:00:00",
                            lines =
                                listOf(
                                    SalesInvoiceDto.StandaloneLineInputDto(
                                        itemName = "Tagungsraum",
                                        quantity = "1",
                                        grossUnitPrice = "107.00",
                                        vatRate = "7.00",
                                    ),
                                ),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, updated.status)
            val updatedDto: SalesInvoiceDto.ResponseDto = updated.body()
            assertEquals("Neue Firma GmbH", updatedDto.buyerName)
            assertEquals("Tagungsraum", updatedDto.lines.single().itemName)

            val deleted = client.delete("/api/v1/sales-invoices/${created.id}") { bearerAuth(token) }
            assertEquals(HttpStatusCode.NoContent, deleted.status)

            val gone = client.get("/api/v1/sales-invoices/${created.id}") { bearerAuth(token) }
            assertEquals(HttpStatusCode.NotFound, gone.status)
        }

    @Test
    fun `PUT with a negative VAT rate is a 4xx, never a 500 and never a stored draft`() =
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val created: SalesInvoiceDto.ResponseDto =
                client
                    .post("/api/v1/sales-invoices/standalone") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody(request())
                    }.body()

            for (rate in listOf("-100.00", "-50.00")) {
                val response =
                    client.put("/api/v1/sales-invoices/${created.id}") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody(
                            SalesInvoiceDto.UpdateStandaloneRequestDto(
                                buyerName = "Musterfirma GmbH",
                                serviceDate = "2026-08-16T10:00:00",
                                lines =
                                    listOf(
                                        SalesInvoiceDto.StandaloneLineInputDto(
                                            itemName = "Raummiete",
                                            quantity = "1",
                                            grossUnitPrice = "119.00",
                                            vatRate = rate,
                                        ),
                                    ),
                            ),
                        )
                    }
                assertTrue(
                    response.status.value in 400..499,
                    "rate $rate must be refused with a 4xx, got ${response.status}",
                )
            }

            val after = client.get("/api/v1/sales-invoices/${created.id}") { bearerAuth(token) }
            val dto: SalesInvoiceDto.ResponseDto = after.body()
            assertEquals(1, dto.lines.size)
            assertEquals("119.0000", dto.taxInclusiveTotal, "the rejected edits must not have landed")
        }

    @Test
    fun `PUT on an issued invoice is a 409`() =
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val created: SalesInvoiceDto.ResponseDto =
                client
                    .post("/api/v1/sales-invoices/standalone") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody(request())
                    }.body()

            val issued = client.post("/api/v1/sales-invoices/${created.id}/issue") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, issued.status)

            val response =
                client.put("/api/v1/sales-invoices/${created.id}") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        SalesInvoiceDto.UpdateStandaloneRequestDto(
                            buyerName = "Später geändert",
                            serviceDate = "2026-08-17T10:00:00",
                            lines = request().lines,
                        ),
                    )
                }
            assertEquals(HttpStatusCode.Conflict, response.status, "the immutability guard must reach HTTP intact")
        }

    @Test
    fun `the open filter returns an issued STANDALONE invoice`() =
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val created: SalesInvoiceDto.ResponseDto =
                client
                    .post("/api/v1/sales-invoices/standalone") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody(request())
                    }.body()
            client.post("/api/v1/sales-invoices/${created.id}/issue") { bearerAuth(token) }

            val response = client.get("/api/v1/sales-invoices?open=true") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, response.status)
            val list: SalesInvoiceDto.ListResponseDto = response.body()
            assertNotNull(list.items.firstOrNull { it.id == created.id }, "the open bucket must show the issued STANDALONE invoice")
            assertTrue(list.items.single { it.id == created.id }.invoiceNumber != null)
        }

    @Test
    fun `overdue alone implies open, so a draft is never returned`() =
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            // A draft exists; an overdue-only request must not degrade to an unfiltered list.
            client.post("/api/v1/sales-invoices/standalone") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(request())
            }

            val response = client.get("/api/v1/sales-invoices?overdue=true") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, response.status)
            val list: SalesInvoiceDto.ListResponseDto = response.body()
            assertEquals(0L, list.total, "an overdue-only request must not return a DRAFT")
        }

    @Test
    fun `unauthenticated requests are refused`() =
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { client ->
            val response =
                client.post("/api/v1/sales-invoices/standalone") {
                    contentType(ContentType.Application.Json)
                    setBody(request())
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `POST settle-at-till with a non-positive amount is a 400`() =
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val cash = TestData.createPaymentType("Bar", isCash = true)

            val created: SalesInvoiceDto.ResponseDto =
                client
                    .post("/api/v1/sales-invoices/standalone") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody(request())
                    }.body()

            val response =
                client.post("/api/v1/sales-invoices/${created.id}/settle-at-till") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(SalesInvoiceDto.SettleAtTillRequestDto(paymentTypeId = cash.id.value, amount = "0"))
                }

            assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        }

    @Test
    fun `POST settle-at-till on a STANDALONE invoice is a 409`() =
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val cash = TestData.createPaymentType("Bar", isCash = true)

            val created: SalesInvoiceDto.ResponseDto =
                client
                    .post("/api/v1/sales-invoices/standalone") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody(request())
                    }.body()
            client.post("/api/v1/sales-invoices/${created.id}/issue") { bearerAuth(token) }

            // A STANDALONE invoice was never a Kassenvorgang, so it cannot be cleared at the till.
            val response =
                client.post("/api/v1/sales-invoices/${created.id}/settle-at-till") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(SalesInvoiceDto.SettleAtTillRequestDto(paymentTypeId = cash.id.value, amount = "119.00"))
                }

            assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
            assertTrue(response.bodyAsText().contains("STANDALONE"), "the refusal names why: ${response.bodyAsText()}")
        }

    @Nested
    @DisplayName("The DATEV AR export routes")
    inner class DatevExportRoutes {
        /** Writes account numbers straight onto the booking row so the export resolver is satisfied. */
        private fun makeResolvable(invoiceId: Long) =
            transaction {
                val booking = SalesInvoice.findById(invoiceId)!!.booking.first()
                booking.bpAccountNo = "10001"
                booking.revenueAccountNo = "8400"
            }

        @Test
        fun `GET export datev statistics returns 200 with the resolvable invoice counted`() =
            testApp(setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
            }) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val created: SalesInvoiceDto.ResponseDto =
                    client
                        .post("/api/v1/sales-invoices/standalone") {
                            bearerAuth(token)
                            contentType(ContentType.Application.Json)
                            setBody(request())
                        }.body()
                client.post("/api/v1/sales-invoices/${created.id}/issue") { bearerAuth(token) }
                makeResolvable(created.id)

                val response =
                    client.get("/api/v1/sales-invoices/export/datev/statistics?start=2000-01-01&end=2100-01-01") {
                        bearerAuth(token)
                    }

                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                val stats: DatevArExportDto.ArExportStatisticsDto = response.body()
                assertEquals(1, stats.totalInvoices)
            }

        @Test
        fun `GET export datev returns 200 with a non-empty ZIP body`() =
            testApp(setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
            }) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val created: SalesInvoiceDto.ResponseDto =
                    client
                        .post("/api/v1/sales-invoices/standalone") {
                            bearerAuth(token)
                            contentType(ContentType.Application.Json)
                            setBody(request())
                        }.body()
                client.post("/api/v1/sales-invoices/${created.id}/issue") { bearerAuth(token) }
                makeResolvable(created.id)

                val response =
                    client.get("/api/v1/sales-invoices/export/datev?start=2000-01-01&end=2100-01-01") {
                        bearerAuth(token)
                    }

                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                assertTrue(response.readRawBytes().isNotEmpty(), "the ZIP body must not be empty")
            }

        @Test
        fun `both export routes are 401 without auth`() =
            testApp(setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
            }) { client ->
                val stats = client.get("/api/v1/sales-invoices/export/datev/statistics?start=2000-01-01&end=2100-01-01")
                assertEquals(HttpStatusCode.Unauthorized, stats.status)

                val export = client.get("/api/v1/sales-invoices/export/datev?start=2000-01-01&end=2100-01-01")
                assertEquals(HttpStatusCode.Unauthorized, export.status)
            }
    }
}
