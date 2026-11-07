package org.example.project.features.bill

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.core.domain.enums.bill.BillKind
import org.example.project.core.domain.enums.bill.TipRecipient
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.error.PosErrorCode
import org.example.project.core.websocket.NotificationHub
import org.example.project.features.bill.RebookFixture.Sale
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.terminal.data.service.PairAttemptLimiter
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `POST /api/v1/bills/{id}/rebook` — the HTTP surface of the Zahlartwechsel (Storno + Neubuchung).
 * The operation itself is proven by [BillRebookTest]; this pins the route: its body contract, its
 * capability (`BILL_CHANGE_PAYMENT`) and that the station account may not use it.
 */
@DisplayName("POST /api/v1/bills/{id}/rebook")
class BillRebookRouteTest {
    private val deviceId = "zw-rebook-route-device"
    private val stationDevice = "zw-rebook-station-device"
    private val fixture = RebookFixture(deviceId)

    @BeforeEach
    fun resetLimiter() = PairAttemptLimiter.resetAllForTests()

    @AfterEach
    fun tearDown() {
        NotificationHub.resetForTest()
        BillNumberTracker.clearAll()
    }

    private fun cardRebookJson(sale: Sale) = """{"payments":[{"amount":"25.00","paymentTypeId":${sale.cardTypeId}}],"tips":"2.00"}"""

    private suspend fun HttpClient.rebook(
        billId: Long,
        body: String,
        auth: HttpRequestBuilder.() -> Unit,
    ): HttpResponse =
        post("/api/v1/bills/$billId/rebook") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(TextContent(body, ContentType.Application.Json))
        }

    /**
     * The admin the route is called as, and the TSE pinned to SIMULATION — explicitly in every test,
     * because `ShopConfigurationManager` caches the mode process-wide.
     */
    private fun adminAndSimulatedTse() {
        TestData.setTseMode(TseMode.SIMULATION)
        TestData.createAdmin()
    }

    private fun stornoCount(): Long = transaction { Bill.all().count { it.deletedBill != null }.toLong() }

    private fun isCancelled(billId: Long): Boolean = transaction { Bill.findById(billId)!!.deletingReason != null }

    @Test
    fun `a valid body rebooks the bill and returns the Neubuchung`() =
        testApp(setupData = { adminAndSimulatedTse() }) { client ->
            val sale = fixture.closeSale(TipRecipient.EMPLOYEE)
            val token = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)

            val response = client.rebook(sale.billId, cardRebookJson(sale)) { bearerAuth(token) }

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val dto = testJson.decodeFromString<BillDto.BillResponseDto>(response.bodyAsText())
            assertEquals(BillKind.REBOOKING, dto.relations.kind)
            assertEquals(sale.billId, dto.relations.rebooksBill?.billId)
            assertEquals(0, BigDecimal("25.00").compareTo(BigDecimal(dto.paidAmount)), "paid ${dto.paidAmount}")
            assertTrue(isCancelled(sale.billId), "the original is stornoed")
        }

    @Test
    fun `an unparseable body is a 400 INVALID_REQUEST and writes nothing`() =
        testApp(setupData = { adminAndSimulatedTse() }) { client ->
            val sale = fixture.closeSale(TipRecipient.EMPLOYEE)
            val token = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)

            val response = client.rebook(sale.billId, """{"payments":"not a list"}""") { bearerAuth(token) }

            assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
            assertTrue(response.bodyAsText().contains(PosErrorCode.INVALID_REQUEST.name), response.bodyAsText())
            assertEquals(0, stornoCount(), "a refused rebook stornos nothing")
        }

    @Test
    fun `a role without BILL_CHANGE_PAYMENT is refused with 403`() =
        testApp(
            setupData = {
                TestData.setTseMode(TseMode.SIMULATION)
                TestData.createTerminal()
            },
        ) { client ->
            val sale = fixture.closeSale(TipRecipient.EMPLOYEE)
            val token = TestAuthHelper.loginAs(client, "terminal", deviceId = deviceId)

            val response = client.rebook(sale.billId, cardRebookJson(sale)) { bearerAuth(token) }

            assertEquals(HttpStatusCode.Forbidden, response.status, response.bodyAsText())
            assertEquals(0, stornoCount(), "a refused rebook stornos nothing")
        }

    @Test
    fun `the station account may not rebook`() =
        testApp(
            setupData = {
                adminAndSimulatedTse()
                TestData.createTerminalDeviceEmployee()
            },
        ) { client ->
            val sale = fixture.closeSale(TipRecipient.EMPLOYEE)
            val jwt = stationJwt(client, pairStation(client))

            val response = client.rebook(sale.billId, cardRebookJson(sale)) { bearerAuth(jwt) }

            assertEquals(HttpStatusCode.Forbidden, response.status, response.bodyAsText())
            assertEquals(0, stornoCount(), "a refused rebook stornos nothing")
            assertNull(transaction { Bill.findById(sale.billId)!!.deletingReason })
        }

    @Test
    fun `a cashier lowering the tip is refused with 403 BILL_REBOOK_TIP_DECREASE_FORBIDDEN`() =
        testApp(
            setupData = {
                TestData.setTseMode(TseMode.SIMULATION)
                TestData.createCashier()
            },
        ) { client ->
            val sale = fixture.closeSale(TipRecipient.OWNER, originalTip = "2.00")
            val token = TestAuthHelper.loginAs(client, "cashier", deviceId = deviceId)
            val lowered = """{"payments":[{"amount":"23.00","paymentTypeId":${sale.cardTypeId}}],"tips":"0.00"}"""

            val response = client.rebook(sale.billId, lowered) { bearerAuth(token) }

            assertEquals(HttpStatusCode.Forbidden, response.status, response.bodyAsText())
            assertTrue(response.bodyAsText().contains(PosErrorCode.BILL_REBOOK_TIP_DECREASE_FORBIDDEN.name), response.bodyAsText())
            assertEquals(0, stornoCount(), "a refused rebook stornos nothing")
            assertTrue(!isCancelled(sale.billId), "the original still stands")
        }

    /** Pairs an ORDER_STATION to [stationDevice]; returns its device token. */
    private suspend fun pairStation(client: HttpClient): String {
        val admin = TestAuthHelper.loginAsAdmin(client)
        val created =
            client
                .post("/api/v1/terminals") {
                    bearerAuth(admin)
                    setBody(TextContent("""{"name":"Station","role":"ORDER_STATION"}""", ContentType.Application.Json))
                }.let { testJson.decodeFromString<TerminalDto.TerminalResponseDto>(it.bodyAsText()) }
        val code =
            client
                .post("/api/v1/terminals/${created.id}/pairing-code") { bearerAuth(admin) }
                .let { testJson.decodeFromString<TerminalDto.PairingCodeResponseDto>(it.bodyAsText()) }
                .code
        return client
            .post("/api/v1/terminals/pair") {
                setBody(TextContent("""{"code":"$code","deviceId":"$stationDevice"}""", ContentType.Application.Json))
            }.let { testJson.decodeFromString<TerminalDto.PairDeviceResponseDto>(it.bodyAsText()) }
            .deviceToken
    }

    /** Exchanges the station's device token for its masked station JWT. */
    private suspend fun stationJwt(
        client: HttpClient,
        deviceToken: String,
    ): String {
        val session = client.post("/api/v1/terminals/session") { header("X-Device-Token", deviceToken) }
        assertEquals(HttpStatusCode.OK, session.status, session.bodyAsText())
        return testJson.decodeFromString<TerminalDto.StationSessionResponseDto>(session.bodyAsText()).accessToken
    }
}
