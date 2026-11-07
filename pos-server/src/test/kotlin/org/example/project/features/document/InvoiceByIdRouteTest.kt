package org.example.project.features.document

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDateTime
import org.example.project.features.document.data.repository.DocumentRepositoryImpl
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * HTTP contract for `GET /api/invoices/{id}` — reaching an invoice by its **own** id.
 *
 * The endpoint exists because an invoice may have no source document at all: a bare XRechnung XML,
 * or one entered by hand. Every other read is keyed on a document id and therefore cannot address
 * such an invoice, so the document-less case is the one asserted here.
 *
 * Statuses are asserted exactly rather than as "not 403": a bare `assertNotEquals(Forbidden)` would
 * be satisfied by a 500 from a broken route, while the exact status proves the request reached the
 * service layer.
 */
@DisplayName("GET /api/invoices/{id}")
class InvoiceByIdRouteTest {
    private val repo = DocumentRepositoryImpl()

    private fun setup() {
        TestData.createShopInfo()
        TestData.createManager()
    }

    @Test
    fun `fetching without a token is 401`() {
        testApp(setupData = { setup() }) { client ->
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/invoices/1").status)
        }
    }

    @Test
    fun `a manager reads an invoice that has no source document`() {
        testApp(setupData = { setup() }) { client ->
            val invoiceId =
                transaction {
                    repo
                        .createInvoice(
                            sourceDocumentId = null,
                            invoiceNumber = "RE-OHNE-BELEG-1",
                            issueDate = LocalDateTime(2026, 3, 1, 0, 0),
                            sellerName = "Ohne Beleg GmbH",
                            taxInclusiveTotal = BigDecimal("238.00"),
                        ).id.value
                }

            val token = TestAuthHelper.loginAsManager(client)
            val response = client.get("/api/invoices/$invoiceId") { bearerAuth(token) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("RE-OHNE-BELEG-1"), "the aggregate must carry the invoice number; got:\n$body")
            assertTrue(body.contains("Ohne Beleg GmbH"), "the seller must be on the wire; got:\n$body")
            assertTrue(body.contains("\"238.00\""), "money crosses the wire as a string; got:\n$body")
            assertTrue(
                body.contains("\"extractionSource\":\"MANUAL\""),
                "provenance must be on the wire; got:\n$body",
            )
        }
    }

    @Test
    fun `an unknown id is 404`() {
        testApp(setupData = { setup() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            assertEquals(
                HttpStatusCode.NotFound,
                client.get("/api/invoices/999999") { bearerAuth(token) }.status,
            )
        }
    }
}
