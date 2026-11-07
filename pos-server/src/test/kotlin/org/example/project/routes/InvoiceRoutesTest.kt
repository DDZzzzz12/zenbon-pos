package org.example.project.routes

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.datetime.LocalDateTime
import org.example.project.features.document.data.repository.DocumentRepositoryImpl
import org.example.project.features.employee.domain.models.Employee
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * HTTP contract for the normalized invoice aggregate.
 *
 * Follows `DocumentRoutesTest`'s shape: one `@Nested inner class` per endpoint, `@DisplayName`d with
 * the literal route template, bodies as `TextContent` rather than typed DTOs so the test exercises
 * the real deserialization, and `bearerAuth(token)` present ⇒ expect the real status, absent ⇒ 401.
 *
 * Statuses are asserted exactly rather than as "not 403". A mere `assertNotEquals(Forbidden)` would
 * be satisfied by a 500 from a broken route; the exact status proves the request reached the
 * service layer.
 */
@DisplayName("Invoice Routes")
class InvoiceRoutesTest {
    private val repo = DocumentRepositoryImpl()

    /** uploadedBy is a real FK: a random UUID fails the write with "Employee not found". */
    private fun uploaderId(): UUID =
        Employee
            .all()
            .first()
            .id.value

    private fun createDocument(): Long =
        repo
            .create(
                filename = "scan.pdf",
                fileType = "INVOICE",
                filePath = "documents/scan.pdf",
                fileSize = 1_024L,
                uploadedAt = LocalDateTime(2026, 3, 1, 9, 0),
                uploadedBy = uploaderId(),
                contentHash = "hash-${System.nanoTime()}",
                metadataJson = null,
                ocrText = null,
                mediaType = "application/pdf",
            ).id.value

    private fun json(body: String) = TextContent(body, ContentType.Application.Json)

    @Nested
    @DisplayName("GET /api/documents/{id}/invoice")
    inner class GetDocumentInvoice {
        @Test
        fun `returns 200 with the aggregate once an invoice exists`() {
            testApp(setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
            }) { client ->
                val documentId =
                    transaction {
                        val id = createDocument()
                        repo.createInvoice(
                            sourceDocumentId = id,
                            invoiceNumber = "RE-GET-1",
                            sellerName = "Lieferant GmbH",
                            taxInclusiveTotal = BigDecimal("238.00"),
                        )
                        id
                    }

                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/documents/$documentId/invoice") { bearerAuth(token) }

                assertEquals(HttpStatusCode.OK, response.status)
                val body = response.bodyAsText()
                assertTrue(body.contains("RE-GET-1"), "the aggregate must carry the invoice number; got:\n$body")
                assertTrue(body.contains("\"extractionSource\":\"MANUAL\""), "provenance must be on the wire; got:\n$body")
                assertTrue(body.contains("\"238.00\""), "money crosses the wire as a string; got:\n$body")
            }
        }

        @Test
        fun `returns 404 for a document with no invoice`() {
            testApp(setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
            }) { client ->
                val documentId = transaction { createDocument() }
                val token = TestAuthHelper.loginAsAdmin(client)
                assertEquals(
                    HttpStatusCode.NotFound,
                    client.get("/api/documents/$documentId/invoice") { bearerAuth(token) }.status,
                )
            }
        }

        @Test
        fun `returns 400 for invalid ID`() {
            testApp(setupData = { TestData.createAdmin() }) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                assertEquals(
                    HttpStatusCode.BadRequest,
                    client.get("/api/documents/invalid/invoice") { bearerAuth(token) }.status,
                )
            }
        }

        @Test
        fun `returns 401 without auth`() {
            testApp { client ->
                assertEquals(HttpStatusCode.Unauthorized, client.get("/api/documents/1/invoice").status)
            }
        }
    }

    @Nested
    @DisplayName("POST /api/documents/{id}/invoice")
    inner class CreateDocumentInvoice {
        @Test
        fun `returns 201 and stamps MANUAL provenance`() {
            testApp(setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
            }) { client ->
                val documentId = transaction { createDocument() }
                val token = TestAuthHelper.loginAsAdmin(client)

                val response =
                    client.post("/api/documents/$documentId/invoice") {
                        bearerAuth(token)
                        setBody(
                            json(
                                """
                                {"invoiceNumber":"RE-NEU-1","issueDate":"2026-03-04",
                                 "sellerName":"Neu Erfasst GmbH","taxInclusiveTotal":"238.00"}
                                """.trimIndent(),
                            ),
                        )
                    }

                assertEquals(HttpStatusCode.Created, response.status)
                val body = response.bodyAsText()
                assertTrue(body.contains("\"extractionSource\":\"MANUAL\""), "got:\n$body")
            }
        }

        @Test
        fun `returns 400 for invalid ID`() {
            testApp(setupData = { TestData.createAdmin() }) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.post("/api/documents/invalid/invoice") {
                        bearerAuth(token)
                        setBody(json("{}"))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
        }

        @Test
        fun `returns 401 without auth`() {
            testApp { client ->
                val response = client.post("/api/documents/1/invoice") { setBody(json("{}")) }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
        }
    }

    @Nested
    @DisplayName("PUT /api/invoices/{id}")
    inner class UpdateInvoiceContent {
        @Test
        fun `returns 200 for a manually entered invoice`() {
            testApp(setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
            }) { client ->
                val invoiceId =
                    transaction {
                        repo.createInvoice(sourceDocumentId = createDocument(), invoiceNumber = "RE-M-1").id.value
                    }
                val token = TestAuthHelper.loginAsAdmin(client)

                val response =
                    client.put("/api/invoices/$invoiceId") {
                        bearerAuth(token)
                        setBody(json("""{"invoiceNumber":"RE-M-1","sellerName":"Korrigiert GmbH"}"""))
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                assertTrue(response.bodyAsText().contains("Korrigiert GmbH"))
            }
        }

        // The guard, at the HTTP boundary. 409 rather than 400 or 403: the payload is fine and the
        // caller is permitted -- the invoice's state forbids the write.
        @Test
        fun `returns 409 for an invoice that came from a supplier e-invoice`() {
            testApp(setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
            }) { client ->
                val invoiceId =
                    transaction {
                        repo
                            .createInvoice(
                                sourceDocumentId = createDocument(),
                                eInvoiceFormat = "ubl",
                                extractionSource = "EINVOICE_XML",
                                invoiceNumber = "RE-XML-1",
                                sellerName = "Lieferant aus XML GmbH",
                            ).id.value
                    }
                val token = TestAuthHelper.loginAsAdmin(client)

                val response =
                    client.put("/api/invoices/$invoiceId") {
                        bearerAuth(token)
                        setBody(json("""{"invoiceNumber":"TIPPFEHLER-9","sellerName":"Von Hand Getippt GmbH"}"""))
                    }
                assertEquals(HttpStatusCode.Conflict, response.status)
                assertTrue(
                    response.bodyAsText().contains("INVOICE_SUPPLIER_CONTENT_READ_ONLY"),
                    "the error envelope must carry the code so a client can branch on it; got:\n" +
                        response.bodyAsText(),
                )
            }
        }

        @Test
        fun `returns 400 for invalid ID`() {
            testApp(setupData = { TestData.createAdmin() }) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.put("/api/invoices/invalid") {
                        bearerAuth(token)
                        setBody(json("{}"))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
        }

        @Test
        fun `returns 401 without auth`() {
            testApp { client ->
                val response = client.put("/api/invoices/1") { setBody(json("{}")) }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
        }
    }

    @Nested
    @DisplayName("PUT /api/invoices/{id}/booking")
    inner class UpdateInvoiceBooking {
        @Test
        fun `returns 200 and clears a named field`() {
            testApp(setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
            }) { client ->
                val invoiceId =
                    transaction {
                        val invoice =
                            repo.createInvoice(sourceDocumentId = createDocument(), invoiceNumber = "RE-B-1")
                        repo.upsertInvoiceBooking(
                            invoice.id.value,
                            accountNo = "3400",
                            paidAt = LocalDateTime(2026, 2, 1, 9, 15, 30),
                        )
                        invoice.id.value
                    }
                val token = TestAuthHelper.loginAsAdmin(client)

                val response =
                    client.put("/api/invoices/$invoiceId/booking") {
                        bearerAuth(token)
                        setBody(json("""{"clearFields":["PAID_AT"]}"""))
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = response.bodyAsText()
                assertTrue(body.contains("\"accountNo\":\"3400\""), "an unnamed field must survive; got:\n$body")
                assertTrue(!body.contains("2026-02-01T09:15:30"), "PAID_AT was named for clearing; got:\n$body")
            }
        }

        @Test
        fun `returns 400 for invalid ID`() {
            testApp(setupData = { TestData.createAdmin() }) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.put("/api/invoices/invalid/booking") {
                        bearerAuth(token)
                        setBody(json("{}"))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
        }

        @Test
        fun `returns 401 without auth`() {
            testApp { client ->
                val response = client.put("/api/invoices/1/booking") { setBody(json("{}")) }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
        }
    }

    @Nested
    @DisplayName("PUT /api/documents/{id}/status")
    inner class UpdateDocumentStatus {
        @Test
        fun `returns 200 for REVIEWED`() {
            testApp(setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
            }) { client ->
                val documentId = transaction { createDocument() }
                val token = TestAuthHelper.loginAsAdmin(client)

                val response =
                    client.put("/api/documents/$documentId/status") {
                        bearerAuth(token)
                        setBody(json("""{"status":"REVIEWED"}"""))
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                assertTrue(response.bodyAsText().contains("REVIEWED"))
            }
        }

        @Test
        fun `returns 400 for a parser-owned status`() {
            testApp(setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
            }) { client ->
                val documentId = transaction { createDocument() }
                val token = TestAuthHelper.loginAsAdmin(client)

                val response =
                    client.put("/api/documents/$documentId/status") {
                        bearerAuth(token)
                        setBody(json("""{"status":"COMPLETED"}"""))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
        }

        @Test
        fun `returns 400 for invalid ID`() {
            testApp(setupData = { TestData.createAdmin() }) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.put("/api/documents/invalid/status") {
                        bearerAuth(token)
                        setBody(json("""{"status":"REVIEWED"}"""))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
        }

        @Test
        fun `returns 401 without auth`() {
            testApp { client ->
                val response =
                    client.put("/api/documents/1/status") { setBody(json("""{"status":"REVIEWED"}""")) }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
        }
    }
}
