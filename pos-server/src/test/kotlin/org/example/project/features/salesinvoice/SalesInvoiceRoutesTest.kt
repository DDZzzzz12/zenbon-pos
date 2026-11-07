package org.example.project.features.salesinvoice

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import org.example.project.core.domain.dto.salesinvoice.SalesInvoiceDto
import org.example.project.features.bill.domain.models.Bill
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("Sales Invoice Routes")
class SalesInvoiceRoutesTest {
    private fun billId(): Long =
        transaction {
            Bill
                .all()
                .first()
                .id.value
        }

    private fun createRequest(id: Long) =
        SalesInvoiceDto.CreateFromBillRequestDto(
            billId = id,
            buyerName = "Musterfirma GmbH",
            buyerStreet = "Industriestr. 5",
            buyerZip = "10117",
            buyerCity = "Berlin",
        )

    @Test
    fun `POST from-bill returns 201 with a numberless draft`() =
        testApp(setupData = {
            TestData.createSettledBillForInvoicing()
            TestData.createAdmin()
        }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)

            val response =
                client.post("/api/v1/sales-invoices/from-bill") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(createRequest(billId()))
                }

            assertEquals(HttpStatusCode.Created, response.status)
            val dto: SalesInvoiceDto.ResponseDto = response.body()
            assertEquals("DRAFT", dto.status)
            assertEquals("POS_SETTLED", dto.revenueOrigin)
            assertNull(dto.invoiceNumber, "a draft consumes no number")
            assertTrue(dto.lines.isNotEmpty())
        }

    @Test
    fun `POST issue stamps a number, and issuing twice is a 409`() =
        testApp(setupData = {
            TestData.createSettledBillForInvoicing()
            TestData.createAdmin()
        }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val draft: SalesInvoiceDto.ResponseDto =
                client
                    .post("/api/v1/sales-invoices/from-bill") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody(createRequest(billId()))
                    }.body()

            val issued = client.post("/api/v1/sales-invoices/${draft.id}/issue") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, issued.status)
            val issuedDto: SalesInvoiceDto.ResponseDto = issued.body()
            assertEquals("ISSUED", issuedDto.status)
            assertNotNull(issuedDto.invoiceNumber)
            assertNotNull(issuedDto.documentId, "issuing archives the PDF")

            val again = client.post("/api/v1/sales-invoices/${draft.id}/issue") { bearerAuth(token) }
            assertEquals(HttpStatusCode.Conflict, again.status, "the immutability guard must reach HTTP intact")
        }

    @Test
    fun `GET pdf serves the archived document`() =
        testApp(setupData = {
            TestData.createSettledBillForInvoicing()
            TestData.createAdmin()
        }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val draft: SalesInvoiceDto.ResponseDto =
                client
                    .post("/api/v1/sales-invoices/from-bill") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody(createRequest(billId()))
                    }.body()
            client.post("/api/v1/sales-invoices/${draft.id}/issue") { bearerAuth(token) }

            val pdf = client.get("/api/v1/sales-invoices/${draft.id}/pdf") { bearerAuth(token) }

            assertEquals(HttpStatusCode.OK, pdf.status)
            assertEquals(ContentType.Application.Pdf, pdf.contentType()?.withoutParameters())
            assertTrue(pdf.readRawBytes().isNotEmpty())
        }

    @Test
    fun `POST cancel returns the 381 Gutschrift`() =
        testApp(setupData = {
            TestData.createSettledBillForInvoicing()
            TestData.createAdmin()
        }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val draft: SalesInvoiceDto.ResponseDto =
                client
                    .post("/api/v1/sales-invoices/from-bill") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody(createRequest(billId()))
                    }.body()
            client.post("/api/v1/sales-invoices/${draft.id}/issue") { bearerAuth(token) }

            val response =
                client.post("/api/v1/sales-invoices/${draft.id}/cancel") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(SalesInvoiceDto.CancelRequestDto(reason = "Tippfehler"))
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val storno: SalesInvoiceDto.ResponseDto = response.body()
            assertEquals("381", storno.invoiceTypeCode)
            assertEquals(draft.id, storno.precedingInvoiceId)
            assertNotNull(storno.invoiceNumber, "the Gutschrift carries its own number")
        }

    @Test
    fun `POST cancel without a reason or with one over 60 characters is a 400`() =
        testApp(setupData = {
            TestData.createSettledBillForInvoicing()
            TestData.createAdmin()
        }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val draft: SalesInvoiceDto.ResponseDto =
                client
                    .post("/api/v1/sales-invoices/from-bill") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody(createRequest(billId()))
                    }.body()
            client.post("/api/v1/sales-invoices/${draft.id}/issue") { bearerAuth(token) }

            for (reason in listOf(null, "  ", "x".repeat(61))) {
                val response =
                    client.post("/api/v1/sales-invoices/${draft.id}/cancel") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody(SalesInvoiceDto.CancelRequestDto(reason = reason))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status, "reason=$reason")
            }
        }

    @Test
    fun `a blank buyer name is refused before anything is created`() =
        testApp(setupData = {
            TestData.createSettledBillForInvoicing()
            TestData.createAdmin()
        }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)

            val response =
                client.post("/api/v1/sales-invoices/from-bill") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(createRequest(billId()).copy(buyerName = "   "))
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `every route requires a token`() =
        testApp { client ->
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/sales-invoices/1").status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/sales-invoices/1/pdf").status)
            assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/sales-invoices/1/issue").status)
        }

    // FO-03: the adminApp hides new/issue/cancel below INVOICE_WRITE; the routes must refuse too.
    @Test
    fun `a cashier cannot create, issue or cancel a sales invoice`() =
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
            TestData.createCashier()
        }) { client ->
            val token = TestAuthHelper.loginAsCashier(client)

            for (path in listOf("/api/v1/sales-invoices/standalone", "/api/v1/sales-invoices/1/issue", "/api/v1/sales-invoices/1/cancel")) {
                val response =
                    client.post(path) {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody("{}")
                    }
                assertEquals(HttpStatusCode.Forbidden, response.status, path)
            }
        }
}
