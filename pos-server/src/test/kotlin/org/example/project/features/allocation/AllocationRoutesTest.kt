package org.example.project.features.allocation

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import org.example.project.core.domain.dto.allocation.AllocationDto
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * HTTP contract for Zuordnung.
 *
 * Statuses are asserted exactly rather than as "not 403": a bare `assertNotEquals(Forbidden)` would
 * also be satisfied by a 500 from a broken route, while the exact status proves the request reached
 * the service.
 */
@DisplayName("Allocation Routes")
class AllocationRoutesTest {
    private fun setup() {
        TestData.createShopInfo()
        TestData.createManager()
        TestData.createCashier()
    }

    @Test
    fun `the queue without a token is 401`() {
        testApp(setupData = { setup() }) { client ->
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/allocations/queue").status)
        }
    }

    @Test
    fun `the queue as a cashier is 403`() {
        testApp(setupData = { setup() }) { client ->
            val token = TestAuthHelper.loginAsCashier(client)
            assertEquals(
                HttpStatusCode.Forbidden,
                client.get("/api/allocations/queue") { bearerAuth(token) }.status,
            )
        }
    }

    @Test
    fun `a manager sees a resolved transaction in the queue`() {
        testApp(setupData = { setup() }) { client ->
            transaction { AllocationFixtures.newResolvedBankTransaction(BigDecimal("120.5000")) }

            val token = TestAuthHelper.loginAsManager(client)
            val response = client.get("/api/allocations/queue") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, response.status)

            val body: AllocationDto.QueueResponseDto = testJson.decodeFromString(response.bodyAsText())
            assertEquals(1L, body.total)
            assertEquals("120.5000", body.items.single().openAmount)
        }
    }

    @Test
    fun `an unknown filter is 400`() {
        testApp(setupData = { setup() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            assertEquals(
                HttpStatusCode.BadRequest,
                client.get("/api/allocations/queue?filter=IRGENDWAS") { bearerAuth(token) }.status,
            )
        }
    }

    @Test
    fun `open invoices are listed for a manager`() {
        testApp(setupData = { setup() }) { client ->
            transaction { AllocationFixtures.newInvoice(BigDecimal("250.0000")) }

            val token = TestAuthHelper.loginAsManager(client)
            val response = client.get("/api/allocations/open-invoices") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, response.status)

            val body: AllocationDto.OpenInvoiceListDto = testJson.decodeFromString(response.bodyAsText())
            assertEquals(1L, body.total)
            assertEquals("250.0000", body.items.single().openAmount)
        }
    }

    @Test
    fun `open instalments are listed for a manager`() {
        testApp(setupData = { setup() }) { client ->
            // Due well after the Buchungsbeginn, which defaults to the first of the current month:
            // the fixture's own default due date is in the past and would be filtered out here.
            transaction {
                ContractFixtures.newInstalment(
                    expected = BigDecimal("450.0000"),
                    dueDate = LocalDateTime(2099, 6, 1, 0, 0),
                )
            }

            val token = TestAuthHelper.loginAsManager(client)
            val response = client.get("/api/allocations/open-instalments") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, response.status)

            val body: AllocationDto.OpenInstalmentListDto = testJson.decodeFromString(response.bodyAsText())
            assertEquals(1L, body.total)
            assertEquals("450.0000", body.items.single().openAmount)
            assertEquals("Miete Ladenlokal", body.items.single().contractName)
        }
    }

    @Test
    fun `open instalments without a token is 401`() {
        testApp(setupData = { setup() }) { client ->
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/allocations/open-instalments").status)
        }
    }

    @Test
    fun `open instalments as a cashier is 403`() {
        testApp(setupData = { setup() }) { client ->
            val token = TestAuthHelper.loginAsCashier(client)
            assertEquals(
                HttpStatusCode.Forbidden,
                client.get("/api/allocations/open-instalments") { bearerAuth(token) }.status,
            )
        }
    }

    @Test
    fun `posting an allocation assigns it and returns both residuals`() {
        testApp(setupData = { setup() }) { client ->
            val (tx, invoice) =
                transaction {
                    AllocationFixtures.newResolvedBankTransaction(BigDecimal("100.0000")).id.value to
                        AllocationFixtures.newInvoice(BigDecimal("80.0000")).id.value
                }

            val token = TestAuthHelper.loginAsManager(client)
            val response =
                client.post("/api/allocations") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        testJson.encodeToString(
                            AllocationDto.CreateRequestDto.serializer(),
                            AllocationDto.CreateRequestDto(
                                sourceKind = "BANK_TRANSACTION",
                                targetKind = "INVOICE",
                                amount = "60.00",
                                bankTransactionId = tx,
                                invoiceId = invoice,
                            ),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.Created, response.status)
            val body: AllocationDto.CreateResponseDto = testJson.decodeFromString(response.bodyAsText())
            assertEquals("40.0000", body.sourceOpenAmount)
            assertEquals("20.0000", body.targetOpenAmount)
        }
    }

    @Test
    fun `over-allocating is 409`() {
        testApp(setupData = { setup() }) { client ->
            val (tx, invoice) =
                transaction {
                    AllocationFixtures.newResolvedBankTransaction(BigDecimal("100.0000")).id.value to
                        AllocationFixtures.newInvoice(BigDecimal("500.0000")).id.value
                }

            val token = TestAuthHelper.loginAsManager(client)
            val response =
                client.post("/api/allocations") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        testJson.encodeToString(
                            AllocationDto.CreateRequestDto.serializer(),
                            AllocationDto.CreateRequestDto(
                                sourceKind = "BANK_TRANSACTION",
                                targetKind = "INVOICE",
                                amount = "100.01",
                                bankTransactionId = tx,
                                invoiceId = invoice,
                            ),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertTrue(response.bodyAsText().contains("ALLOCATION_EXCEEDS_SOURCE"), response.bodyAsText())
        }
    }

    @Test
    fun `deleting an allocation is 204 and deleting a missing one is 404`() {
        testApp(setupData = { setup() }) { client ->
            val (tx, invoice) =
                transaction {
                    AllocationFixtures.newResolvedBankTransaction(BigDecimal("100.0000")).id.value to
                        AllocationFixtures.newInvoice(BigDecimal("80.0000")).id.value
                }

            val token = TestAuthHelper.loginAsManager(client)
            val created =
                client.post("/api/allocations") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        testJson.encodeToString(
                            AllocationDto.CreateRequestDto.serializer(),
                            AllocationDto.CreateRequestDto(
                                sourceKind = "BANK_TRANSACTION",
                                targetKind = "INVOICE",
                                amount = "60.00",
                                bankTransactionId = tx,
                                invoiceId = invoice,
                            ),
                        ),
                    )
                }
            val body: AllocationDto.CreateResponseDto = testJson.decodeFromString(created.bodyAsText())

            assertEquals(
                HttpStatusCode.NoContent,
                client.delete("/api/allocations/${body.allocation.id}") { bearerAuth(token) }.status,
            )
            assertEquals(
                HttpStatusCode.NotFound,
                client.delete("/api/allocations/999999") { bearerAuth(token) }.status,
            )
        }
    }

    @Test
    fun `the batch endpoint without a token is 401`() {
        testApp(setupData = { setup() }) { client ->
            assertEquals(
                HttpStatusCode.Unauthorized,
                client
                    .post("/api/allocations/batch") {
                        contentType(ContentType.Application.Json)
                        setBody("[]")
                    }.status,
            )
        }
    }

    @Test
    fun `the batch endpoint as a cashier is 403`() {
        testApp(setupData = { setup() }) { client ->
            val token = TestAuthHelper.loginAsCashier(client)
            assertEquals(
                HttpStatusCode.Forbidden,
                client
                    .post("/api/allocations/batch") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody("[]")
                    }.status,
            )
        }
    }

    @Test
    fun `posting a pair of allocations is 201 and returns both`() {
        testApp(setupData = { setup() }) { client ->
            val (tx, invoices) =
                transaction {
                    AllocationFixtures.newResolvedBankTransaction(BigDecimal("100.0000")).id.value to
                        listOf(
                            AllocationFixtures.newInvoice(BigDecimal("80.0000"), "RE-A").id.value,
                            AllocationFixtures.newInvoice(BigDecimal("50.0000"), "RE-B").id.value,
                        )
                }

            val token = TestAuthHelper.loginAsManager(client)
            val response =
                client.post("/api/allocations/batch") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(batchBody(tx, listOf(invoices[0] to "60.00", invoices[1] to "30.00")))
                }

            assertEquals(HttpStatusCode.Created, response.status)
            val body: List<AllocationDto.CreateResponseDto> = testJson.decodeFromString(response.bodyAsText())
            assertEquals(2, body.size)
            assertEquals("SUGGESTION_ACCEPTED", body.first().allocation.matchedBy)
            assertEquals("10.0000", body.last().sourceOpenAmount)
        }
    }

    @Test
    fun `a batch whose second item over-allocates is 409 and writes nothing`() {
        testApp(setupData = { setup() }) { client ->
            val (tx, invoices) =
                transaction {
                    AllocationFixtures.newResolvedBankTransaction(BigDecimal("100.0000")).id.value to
                        listOf(
                            AllocationFixtures.newInvoice(BigDecimal("80.0000"), "RE-A").id.value,
                            AllocationFixtures.newInvoice(BigDecimal("500.0000"), "RE-B").id.value,
                        )
                }

            val token = TestAuthHelper.loginAsManager(client)
            val response =
                client.post("/api/allocations/batch") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(batchBody(tx, listOf(invoices[0] to "60.00", invoices[1] to "60.00")))
                }

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertTrue(response.bodyAsText().contains("ALLOCATION_EXCEEDS_SOURCE"), response.bodyAsText())

            val queue = client.get("/api/allocations/queue?filter=ALLE") { bearerAuth(token) }
            val body: AllocationDto.QueueResponseDto = testJson.decodeFromString(queue.bodyAsText())
            val row = body.items.single()
            assertEquals(emptyList(), row.allocations)
            assertEquals("100.0000", row.openAmount)
        }
    }

    @Test
    fun `an empty batch is 400`() {
        testApp(setupData = { setup() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            assertEquals(
                HttpStatusCode.BadRequest,
                client
                    .post("/api/allocations/batch") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody("[]")
                    }.status,
            )
        }
    }

    /** The wire shape of an accepted suggestion: one request per invoice, all against one payment. */
    private fun batchBody(
        transactionId: Long,
        targets: List<Pair<Long, String>>,
    ): String =
        testJson.encodeToString(
            ListSerializer(AllocationDto.CreateRequestDto.serializer()),
            targets.map { (invoiceId, amount) ->
                AllocationDto.CreateRequestDto(
                    sourceKind = "BANK_TRANSACTION",
                    targetKind = "INVOICE",
                    amount = amount,
                    bankTransactionId = transactionId,
                    invoiceId = invoiceId,
                    matchedBy = "SUGGESTION_ACCEPTED",
                )
            },
        )

    @Test
    fun `a non-numeric allocation id is 400`() {
        testApp(setupData = { setup() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            assertEquals(
                HttpStatusCode.BadRequest,
                client.delete("/api/allocations/abc") { bearerAuth(token) }.status,
            )
        }
    }

    @Test
    fun `an unknown matchedSignal is 400`() {
        testApp(setupData = { setup() }) { client ->
            val (tx, invoice) =
                transaction {
                    AllocationFixtures.newResolvedBankTransaction(BigDecimal("100.0000")).id.value to
                        AllocationFixtures.newInvoice(BigDecimal("100.0000")).id.value
                }

            val token = TestAuthHelper.loginAsManager(client)
            val response =
                client.post("/api/allocations") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        testJson.encodeToString(
                            AllocationDto.CreateRequestDto.serializer(),
                            AllocationDto.CreateRequestDto(
                                sourceKind = "BANK_TRANSACTION",
                                targetKind = "INVOICE",
                                amount = "60.00",
                                bankTransactionId = tx,
                                invoiceId = invoice,
                                matchedBy = "SUGGESTION_ACCEPTED",
                                matchedSignal = "REMITANCE_TEXT",
                            ),
                        ),
                    )
                }

            // A near-miss on purpose: a typo must not become its own telemetry category.
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("REMITANCE_TEXT"), response.bodyAsText())
        }
    }

    @Test
    fun `a real matchedSignal is accepted`() {
        testApp(setupData = { setup() }) { client ->
            val (tx, invoice) =
                transaction {
                    AllocationFixtures.newResolvedBankTransaction(BigDecimal("100.0000")).id.value to
                        AllocationFixtures.newInvoice(BigDecimal("100.0000")).id.value
                }

            val token = TestAuthHelper.loginAsManager(client)
            val response =
                client.post("/api/allocations") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        testJson.encodeToString(
                            AllocationDto.CreateRequestDto.serializer(),
                            AllocationDto.CreateRequestDto(
                                sourceKind = "BANK_TRANSACTION",
                                targetKind = "INVOICE",
                                amount = "60.00",
                                bankTransactionId = tx,
                                invoiceId = invoice,
                                matchedBy = "SUGGESTION_ACCEPTED",
                                matchedSignal = "REMITTANCE_TEXT",
                            ),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.Created, response.status)
        }
    }
}
