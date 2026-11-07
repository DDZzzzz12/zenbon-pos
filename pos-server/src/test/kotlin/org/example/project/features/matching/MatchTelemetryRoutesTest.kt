package org.example.project.features.matching

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.example.project.core.domain.dto.allocation.AllocationDto
import org.example.project.core.domain.dto.matching.MatchTelemetryDto
import org.example.project.features.allocation.AllocationFixtures
import org.example.project.features.allocation.domain.models.AllocationMatchedBy
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
 * Which signal produced an allocation, recorded on the way in and counted on the way out.
 *
 * The counts exist to answer *which of the seven signals earn their place*, so the two things worth
 * pinning are that a manual assignment can never claim one, and that a signal nobody has used yet
 * reads as a zero rather than a gap.
 */
@DisplayName("Match telemetry")
class MatchTelemetryRoutesTest {
    private fun setup() {
        TestData.createShopInfo()
        TestData.createManager()
        TestData.createCashier()
    }

    @Test
    fun `telemetry without a token is 401`() {
        testApp(setupData = { setup() }) { client ->
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/allocations/telemetry").status)
        }
    }

    @Test
    fun `telemetry as a cashier is 403`() {
        testApp(setupData = { setup() }) { client ->
            val token = TestAuthHelper.loginAsCashier(client)
            assertEquals(
                HttpStatusCode.Forbidden,
                client.get("/api/allocations/telemetry") { bearerAuth(token) }.status,
            )
        }
    }

    /**
     * A caller reading `byMatchedBy["SUGGESTION_ACCEPTED"]` must get 0, not null: an absent key
     * renders as a blank where a number belongs, which reads as "unknown" rather than "none yet".
     */
    @Test
    fun `every matched-by constant is present with a zero when nothing has been allocated`() {
        testApp(setupData = { setup() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)

            val body = client.telemetry(token)

            assertEquals(
                AllocationMatchedBy.entries.map { it.name }.toSet(),
                body.byMatchedBy.keys,
                "every constant, including the ones nothing has used",
            )
            assertTrue(body.byMatchedBy.values.all { it == 0L })
            assertEquals(0L, body.total)
            assertTrue(body.bySignal.isEmpty(), "no signal has produced anything yet")
        }
    }

    @Test
    fun `an accepted suggestion is counted under its signal and a manual one is not`() {
        testApp(setupData = { setup() }) { client ->
            val transactionId =
                transaction { AllocationFixtures.newResolvedBankTransaction(BigDecimal("300.0000")).id.value }
            val invoiceA = transaction { AllocationFixtures.newInvoice(BigDecimal("100.0000"), "RE-A").id.value }
            val invoiceB = transaction { AllocationFixtures.newInvoice(BigDecimal("100.0000"), "RE-B").id.value }
            val invoiceC = transaction { AllocationFixtures.newInvoice(BigDecimal("100.0000"), "RE-C").id.value }
            val token = TestAuthHelper.loginAsManager(client)

            client.allocate(token, request(transactionId, invoiceA, "SUGGESTION_ACCEPTED", "STRUCTURED_REFERENCE"))
            client.allocate(token, request(transactionId, invoiceB, "SUGGESTION_ACCEPTED", "SUBSET_SUM"))
            client.allocate(token, request(transactionId, invoiceC, "MANUAL", null))

            val body = client.telemetry(token)

            assertEquals(2L, body.byMatchedBy["SUGGESTION_ACCEPTED"])
            assertEquals(1L, body.byMatchedBy["MANUAL"])
            assertEquals(0L, body.byMatchedBy["AUTO_RULE"])
            assertEquals(mapOf("STRUCTURED_REFERENCE" to 1L, "SUBSET_SUM" to 1L), body.bySignal)
            assertEquals(3L, body.total)
        }
    }

    /**
     * The whole point of the column is to say which signal earned an assignment. A human's own
     * reading carrying a signal name would corrupt exactly those numbers, so it is refused rather
     * than stored or quietly dropped.
     */
    @Test
    fun `a manual allocation claiming a signal is 400`() {
        testApp(setupData = { setup() }) { client ->
            val transactionId =
                transaction { AllocationFixtures.newResolvedBankTransaction(BigDecimal("300.0000")).id.value }
            val invoiceId = transaction { AllocationFixtures.newInvoice(BigDecimal("100.0000"), "RE-A").id.value }
            val token = TestAuthHelper.loginAsManager(client)

            val response =
                client.post("/api/allocations") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(testJson.encodeToString(request(transactionId, invoiceId, "MANUAL", "STRUCTURED_REFERENCE")))
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(
                response.bodyAsText().contains("MANUAL"),
                "the message must say why: ${response.bodyAsText()}",
            )
        }
    }

    private fun request(
        transactionId: Long,
        invoiceId: Long,
        matchedBy: String,
        signal: String?,
    ) = AllocationDto.CreateRequestDto(
        sourceKind = "BANK_TRANSACTION",
        targetKind = "INVOICE",
        amount = "100.0000",
        bankTransactionId = transactionId,
        invoiceId = invoiceId,
        matchedBy = matchedBy,
        matchedSignal = signal,
    )

    private suspend fun HttpClient.allocate(
        token: String,
        request: AllocationDto.CreateRequestDto,
    ) {
        val response =
            post("/api/allocations") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(testJson.encodeToString(request))
            }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
    }

    private suspend fun HttpClient.telemetry(token: String): MatchTelemetryDto.ResponseDto {
        val response = get("/api/allocations/telemetry") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return testJson.decodeFromString(response.bodyAsText())
    }
}
