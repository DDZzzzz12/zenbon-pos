package org.example.project.features.zBon

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.features.audit.database.tables.TransactionAuditLogTable
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.terminal.domain.TSE_DISABLED_CLIENT_ID
import org.example.project.features.tse.database.tables.TseSigningQueueTable
import org.example.project.features.tse.database.tables.TseTransactionTable
import org.example.project.features.zBon.domain.ClosingSigner
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TippedDayFixture
import org.example.project.testutils.TippedDayFixture.Sale
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * With the TSE DISABLED nothing is signed, so the adminApp (an `admin-web-*` device, no till) closes
 * the day with the full dialog: a drawer count, a cash tip payout and the configured daily Geldtransit.
 * Every entry is stamped DISABLED, no TSE transaction and no signing-queue row exists, and the Z-number
 * is consumed once. (testApp's TSE mode is DISABLED; this suite never mutates the singleton.)
 */
@DisplayName("Z-Bon close from the adminApp with the TSE disabled")
class ZBonCloseTseDisabledTest {
    private val adminWeb = "admin-web-9c2e"
    private val fixture = TippedDayFixture("tse-disabled-till", "TDI")
    private val day = listOf(Sale("anna", "3.00"), Sale("anna", "2.00", byCard = true), Sale("bert", "4.00"))

    @AfterEach
    fun resetBillNumberTracker() = BillNumberTracker.clearAll()

    private suspend fun io.ktor.client.HttpClient.asAdminWeb(request: ZBonDto.CreateZBonRequestDto): HttpResponse {
        val token = TestAuthHelper.loginAs(this, "admin", deviceId = adminWeb)
        return post("/api/z-bon") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    @Test
    fun `a counted closing with a cash tip payout and a daily Geldtransit succeeds and signs nothing`() =
        testApp(setupData = {
            fixture.seed(day)
            fixture.routeTakingsToSafe()
        }) { client ->
            try {
                assertEquals(TseMode.DISABLED, ShopConfigurationManager.getTseMode())
                val response =
                    client.asAdminWeb(
                        ZBonDto.CreateZBonRequestDto(
                            countedAmount = "39.50",
                            tipPayouts = listOf(ZBonDto.CreateTipPayoutRequestDto(fixture.waiters.getValue("anna").toString(), "5.00")),
                        ),
                    )
                assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
                val zBon = testJson.decodeFromString(ZBonDto.ZBonResponseDto.serializer(), response.bodyAsText())
                assertEquals(1, zBon.zbonNummer, "the Z-number is consumed exactly once")
                transaction {
                    assertEquals(1L, ZBon.all().count())
                    val headers = KassenbuchHeader.all().toList()
                    val aggregate = headers.single { it.isZbonAggregate }
                    assertEquals(TseSigningStatus.NOT_APPLICABLE, aggregate.tseSigningStatus)
                    val movements = headers.filterNot { it.isZbonAggregate }
                    assertTrue(movements.size >= 3, "payout, difference and Geldtransit legs were booked: ${movements.size}")
                    movements
                        .filter { it.cashBook.isFiscal }
                        .forEach {
                            assertEquals(TseSigningStatus.DISABLED, it.tseSigningStatus, "${it.entryType}")
                            assertNull(it.tseTransaction)
                        }
                    assertEquals(0L, TseTransactionTable.selectAll().count(), "no TSE transaction")
                    assertEquals(0L, TseSigningQueueTable.selectAll().count(), "nothing queued under the placeholder id")

                    // The closing's audit row says so: the placeholder, stated as such — not a till.
                    val audit =
                        TransactionAuditLogTable
                            .selectAll()
                            .where { TransactionAuditLogTable.operation eq "CREATE_REPORT" }
                            .single()
                    assertEquals(adminWeb, audit[TransactionAuditLogTable.deviceId])
                    val state = Json.parseToJsonElement(audit[TransactionAuditLogTable.afterState]!!).jsonObject
                    assertEquals(TSE_DISABLED_CLIENT_ID, state["signingClientId"]!!.jsonPrimitive.content)
                    assertEquals(
                        ClosingSigner.Source.TSE_DISABLED_PLACEHOLDER.name,
                        state["signingClientSource"]!!.jsonPrimitive.content,
                    )
                }
            } finally {
                fixture.stopRoutingToSafe()
            }
        }

    @Test
    fun `readiness reports tseDisabled with no problem for the adminApp`() =
        testApp(setupData = { fixture.seed(day) }) { client ->
            val token = TestAuthHelper.loginAs(client, "admin", deviceId = adminWeb)
            val response = client.get("/api/z-bon/closing-readiness") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val readiness = testJson.decodeFromString(ZBonDto.ClosingReadinessDto.serializer(), response.bodyAsText())
            assertTrue(readiness.tseDisabled)
            assertNull(readiness.signerProblem)
            assertNull(readiness.signerName)
            assertTrue(readiness.movementsAllowed)
            assertFalse(readiness.blocksClosing)
        }
}
