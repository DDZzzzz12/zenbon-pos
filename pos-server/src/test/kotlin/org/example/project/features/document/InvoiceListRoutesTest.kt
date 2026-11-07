package org.example.project.features.document

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus
import org.example.project.core.domain.dto.document.InvoiceListDto
import org.example.project.features.document.data.repository.DocumentRepositoryImpl
import org.example.project.features.document.domain.models.Invoice
import org.example.project.features.employee.domain.models.Employee
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HTTP contract for the Rechnungen list — the union of uncaptured documents and invoices.
 *
 * Statuses are asserted exactly rather than as "not 403": a bare `assertNotEquals(Forbidden)` would
 * be satisfied by a 500 from a broken route, while the exact status proves the request reached the
 * service layer.
 */
@DisplayName("Invoice List Routes")
class InvoiceListRoutesTest {
    private val repo = DocumentRepositoryImpl()

    /** uploadedBy is a real FK: a random UUID fails the write with "Employee not found". */
    private fun uploaderId(): UUID =
        Employee
            .all()
            .first()
            .id.value

    private fun createDocument(filename: String = "scan.pdf"): Long =
        repo
            .create(
                filename = filename,
                fileType = "INVOICE",
                filePath = "documents/$filename",
                fileSize = 1_024L,
                uploadedAt = LocalDateTime(2026, 3, 1, 9, 0),
                uploadedBy = uploaderId(),
                contentHash = "hash-${System.nanoTime()}",
                metadataJson = null,
                ocrText = null,
                mediaType = "application/pdf",
            ).id.value

    private fun setup() {
        TestData.createShopInfo()
        TestData.createManager()
    }

    private fun parse(body: String): InvoiceListDto.ListResponseDto = testJson.decodeFromString(body)

    @Test
    fun `listing without a token is 401`() {
        testApp(setupData = { setup() }) { client ->
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/invoices").status)
        }
    }

    @Test
    fun `ZU_ERFASSEN carries the document id and no invoice id`() {
        testApp(setupData = { setup() }) { client ->
            val documentId = transaction { createDocument("erfassen.pdf") }

            val token = TestAuthHelper.loginAsManager(client)
            val response = client.get("/api/invoices?bucket=ZU_ERFASSEN") { bearerAuth(token) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = parse(response.bodyAsText())
            assertEquals(1, body.items.size)
            val row = body.items.first()
            assertEquals(InvoiceListDto.Bucket.ZU_ERFASSEN, row.bucket)
            assertEquals(documentId, row.documentId)
            assertNull(row.invoiceId, "an uncaptured document has no invoice row yet")
            assertEquals("erfassen.pdf", row.filename)
        }
    }

    @Test
    fun `ZU_PRUEFEN carries the invoice id and no document id`() {
        testApp(setupData = { setup() }) { client ->
            val invoiceId =
                transaction {
                    repo
                        .createInvoice(
                            invoiceNumber = "RE-2026-1",
                            sellerName = "Lieferant GmbH",
                            taxInclusiveTotal = BigDecimal("238.00"),
                        ).id.value
                }

            val token = TestAuthHelper.loginAsManager(client)
            val response = client.get("/api/invoices?bucket=ZU_PRUEFEN") { bearerAuth(token) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = parse(response.bodyAsText())
            assertEquals(1, body.items.size)
            val row = body.items.first()
            assertEquals(InvoiceListDto.Bucket.ZU_PRUEFEN, row.bucket)
            assertEquals(invoiceId, row.invoiceId)
            assertNull(row.documentId, "a bare XRechnung has no source document")
            assertEquals("RE-2026-1", row.invoiceNumber)
            assertEquals("Lieferant GmbH", row.supplierName)
            assertEquals("238.00", row.grossTotal, "money crosses the wire as a string")
        }
    }

    /**
     * An invoice extracted from a scanned PDF still carries its document, so the list can offer a
     * preview without a second call. `invoiceId` is the discriminator between the two populations —
     * not the absence of `documentId`.
     */
    @Test
    fun `an invoice extracted from a document carries both ids`() {
        testApp(setupData = { setup() }) { client ->
            val documentId = transaction { createDocument("scan.pdf") }
            val invoiceId =
                transaction {
                    repo.createInvoice(sourceDocumentId = documentId, sellerName = "Metro AG").id.value
                }

            val token = TestAuthHelper.loginAsManager(client)
            val response = client.get("/api/invoices?bucket=ZU_PRUEFEN") { bearerAuth(token) }

            assertEquals(HttpStatusCode.OK, response.status)
            val row = parse(response.bodyAsText()).items.single()
            assertEquals(invoiceId, row.invoiceId)
            assertEquals(documentId, row.documentId, "the source document must ride along for preview")
        }
    }

    @Test
    fun `reviewing moves an invoice from ZU_PRUEFEN to FERTIG`() {
        testApp(setupData = { setup() }) { client ->
            val invoiceId =
                transaction {
                    repo.createInvoice(invoiceNumber = "RE-2026-2", sellerName = "Lieferant GmbH").id.value
                }

            val token = TestAuthHelper.loginAsManager(client)
            val reviewed = client.put("/api/invoices/$invoiceId/review") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, reviewed.status)
            assertTrue(
                reviewed.bodyAsText().contains("FERTIG"),
                "the reviewed row must come back in the FERTIG bucket; got:\n${reviewed.bodyAsText()}",
            )

            val pruefen = parse(client.get("/api/invoices?bucket=ZU_PRUEFEN") { bearerAuth(token) }.bodyAsText())
            assertEquals(0, pruefen.items.size)

            val fertig = parse(client.get("/api/invoices?bucket=FERTIG") { bearerAuth(token) }.bodyAsText())
            assertEquals(1, fertig.items.size)
            assertEquals(invoiceId, fertig.items.first().invoiceId)
        }
    }

    @Test
    fun `reviewing twice keeps the first stamp`() {
        testApp(setupData = { setup() }) { client ->
            val invoiceId =
                transaction {
                    repo.createInvoice(invoiceNumber = "RE-2026-3", sellerName = "Lieferant GmbH").id.value
                }

            val token = TestAuthHelper.loginAsManager(client)
            client.put("/api/invoices/$invoiceId/review") { bearerAuth(token) }
            val first = transaction { repo.getInvoice(invoiceId)?.reviewedAt }
            assertNotNull(first)

            client.put("/api/invoices/$invoiceId/review") { bearerAuth(token) }
            val second = transaction { repo.getInvoice(invoiceId)?.reviewedAt }

            assertEquals(first, second, "re-confirming must not overwrite the first confirmation")
        }
    }

    @Test
    fun `reviewing an unknown invoice is 404`() {
        testApp(setupData = { setup() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            assertEquals(
                HttpStatusCode.NotFound,
                client.put("/api/invoices/999999/review") { bearerAuth(token) }.status,
            )
        }
    }

    @Test
    fun `an unpaid invoice appears in OFFEN with its residual`() {
        testApp(setupData = { setup() }) { client ->
            transaction {
                repo.createInvoice(
                    invoiceNumber = "RE-OFFEN",
                    dueDate = LocalDateTime(2099, 1, 1, 0, 0),
                    taxInclusiveTotal = BigDecimal("119.0000"),
                )
            }

            val token = TestAuthHelper.loginAsManager(client)
            val body = parse(client.get("/api/invoices?bucket=OFFEN") { bearerAuth(token) }.bodyAsText())

            val row = body.items.single()
            assertEquals(InvoiceListDto.Bucket.OFFEN, row.bucket)
            assertEquals("119.0000", row.openAmount, "the residual keeps its DECIMAL(19,4) scale")
            assertNotNull(row.dueDate)
            assertEquals(1L, body.bucketCounts["OFFEN"])
            assertEquals(0L, body.bucketCounts["UEBERFAELLIG"])
        }
    }

    @Test
    fun `an invoice past its due date appears in UEBERFAELLIG as well as OFFEN`() {
        testApp(setupData = { setup() }) { client ->
            transaction {
                repo.createInvoice(
                    invoiceNumber = "RE-SPAET",
                    dueDate = LocalDateTime(2020, 1, 1, 0, 0),
                    taxInclusiveTotal = BigDecimal("50.0000"),
                )
            }

            val token = TestAuthHelper.loginAsManager(client)
            val body = parse(client.get("/api/invoices?bucket=UEBERFAELLIG") { bearerAuth(token) }.bodyAsText())

            assertEquals("RE-SPAET", body.items.single().invoiceNumber)
            // Both, deliberately: overdue is a narrowing of open, not a bucket beside it.
            assertEquals(1L, body.bucketCounts["OFFEN"])
            assertEquals(1L, body.bucketCounts["UEBERFAELLIG"])
        }
    }

    @Test
    fun `a row is flagged overdue only after its due day, in any bucket`() {
        testApp(setupData = { setup() }) { client ->
            val today =
                org.example.project.utils.formatter
                    .currentTimeStamp()
                    .date
            val midnight = { days: Int -> LocalDateTime(today.plus(kotlinx.datetime.DatePeriod(days = days)), kotlinx.datetime.LocalTime(0, 0)) }
            transaction {
                repo.createInvoice(invoiceNumber = "RE-GESTERN", dueDate = midnight(-1), taxInclusiveTotal = BigDecimal("10.0000"))
                repo.createInvoice(invoiceNumber = "RE-HEUTE", dueDate = midnight(0), taxInclusiveTotal = BigDecimal("10.0000"))
                repo.createInvoice(invoiceNumber = "RE-MORGEN", dueDate = midnight(1), taxInclusiveTotal = BigDecimal("10.0000"))
            }

            val token = TestAuthHelper.loginAsManager(client)
            // All three are unreviewed, so they are listed under Zu prüfen — the flag must still be set.
            val pruefen = parse(client.get("/api/invoices?bucket=ZU_PRUEFEN") { bearerAuth(token) }.bodyAsText())
            val flags = pruefen.items.associate { it.invoiceNumber to it.overdue }
            assertEquals(mapOf<String?, Boolean>("RE-GESTERN" to true, "RE-HEUTE" to false, "RE-MORGEN" to false), flags)

            // The bucket and the flag are one predicate.
            assertEquals(1L, pruefen.bucketCounts["UEBERFAELLIG"])
            assertEquals("10.00", pruefen.bucketSums["UEBERFAELLIG"])
            val ueberfaellig = parse(client.get("/api/invoices?bucket=UEBERFAELLIG") { bearerAuth(token) }.bodyAsText())
            assertEquals(listOf("RE-GESTERN"), ueberfaellig.items.map { it.invoiceNumber })
            assertTrue(ueberfaellig.items.single().overdue)
            assertTrue(pruefen.items.none { it.cancelled })
        }
    }

    @Test
    fun `an unreviewed invoice counts in both its capture bucket and its payment bucket`() {
        testApp(setupData = { setup() }) { client ->
            transaction {
                repo.createInvoice(invoiceNumber = "RE-BEIDES", taxInclusiveTotal = BigDecimal("10.0000"))
            }

            val token = TestAuthHelper.loginAsManager(client)
            val body = parse(client.get("/api/invoices") { bearerAuth(token) }.bodyAsText())

            assertEquals(1L, body.bucketCounts["ZU_PRUEFEN"])
            assertEquals(1L, body.bucketCounts["OFFEN"])
        }
    }

    @Test
    fun `bucketCounts carries every bucket key even when a bucket is empty`() {
        testApp(setupData = { setup() }) { client ->
            transaction { createDocument("nur-erfassen.pdf") }

            val token = TestAuthHelper.loginAsManager(client)
            val body = parse(client.get("/api/invoices?bucket=ZU_ERFASSEN") { bearerAuth(token) }.bodyAsText())

            assertEquals(1L, body.bucketCounts["ZU_ERFASSEN"])
            // Driven off the enum rather than a hand-written list of names: a bucket added later is
            // covered without anyone remembering to extend this, which is how the name of this test
            // came to promise three keys while the enum had grown to six.
            InvoiceListDto.Bucket.entries
                .filter { it != InvoiceListDto.Bucket.ZU_ERFASSEN }
                .forEach { bucket ->
                    assertEquals(0L, body.bucketCounts[bucket.name], "$bucket must be 0, not a missing key")
                }
        }
    }

    @Test
    fun `an overpaid invoice appears in UEBERZAHLT with its negative residual`() {
        testApp(setupData = { setup() }) { client ->
            transaction {
                val invoice =
                    repo.createInvoice(
                        invoiceNumber = "RE-ZUVIEL",
                        taxInclusiveTotal = BigDecimal("100.0000"),
                    )
                // Set directly rather than through an allocation: this test is about the bucket
                // wiring, and AllocationQueueRepositoryTest already proves the real write path
                // produces this value.
                Invoice.findById(invoice.id.value)!!.openAmount = BigDecimal("-20.0000")
            }

            val token = TestAuthHelper.loginAsManager(client)
            val body = parse(client.get("/api/invoices?bucket=UEBERZAHLT") { bearerAuth(token) }.bodyAsText())

            val row = body.items.single()
            assertEquals(InvoiceListDto.Bucket.UEBERZAHLT, row.bucket)
            assertEquals("RE-ZUVIEL", row.invoiceNumber)
            assertEquals("-20.0000", row.openAmount, "the sign is the whole point; do not clamp it")
            assertEquals(1L, body.bucketCounts["UEBERZAHLT"])
            assertEquals(0L, body.bucketCounts["OFFEN"], "an overpaid invoice owes nothing")
        }
    }

    @Test
    fun `bucketCounts carries UEBERZAHLT even when nothing is overpaid`() {
        testApp(setupData = { setup() }) { client ->
            transaction {
                repo.createInvoice(invoiceNumber = "RE-NORMAL", taxInclusiveTotal = BigDecimal("10.0000"))
            }

            val token = TestAuthHelper.loginAsManager(client)
            val body = parse(client.get("/api/invoices?bucket=OFFEN") { bearerAuth(token) }.bodyAsText())

            assertEquals(0L, body.bucketCounts["UEBERZAHLT"], "an empty bucket must be 0, not a missing key")
        }
    }
}
