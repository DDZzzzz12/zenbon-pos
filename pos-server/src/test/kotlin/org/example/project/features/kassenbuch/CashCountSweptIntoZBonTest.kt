package org.example.project.features.kassenbuch

import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.Json
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testKassenbuchDocuments
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * A3's load-bearing guarantee: the drawer difference must land in the closing that CAUSED it.
 *
 * The whole ordering argument for A3 is that the count is booked BEFORE `createReport`, so the
 * closing's sweep picks the difference up. A difference entry that is otherwise perfect but
 * belongs to the *next* Z-Bon is still wrong — it attributes a shortfall to the wrong business
 * day, which is exactly what a Kassenprüfung reconstructs.
 *
 * The mutation this catches: call the cash-count AFTER createReport and this test goes RED.
 */
@DisplayName("A drawer difference booked before the Z-Bon is swept into that Z-Bon")
class CashCountSweptIntoZBonTest {
    private companion object {
        /** H2 stand-in for the Postgres advisory lock `createReport` takes before anything else. */
        const val ADVISORY_LOCK_ALIAS =
            "CREATE ALIAS IF NOT EXISTS PG_ADVISORY_XACT_LOCK AS " +
                "'long pgAdvisoryXactLock(long key) { return key; }'"
    }

    /** Seeds a signing terminal on the default device id plus a fiscal till holding 100.00. */
    private fun seedTillWith100() {
        val employee = TestData.createAdmin()
        val shop = TestData.createShopInfo()

        val repo = KassenbuchRepositoryImpl(testKassenbuchDocuments())
        val books = CashBookRepositoryImpl()
        transaction {
            exec(ADVISORY_LOCK_ALIAS)
            val now = currentTimeStamp()
            Terminal.new {
                deviceId = "unknown"
                clientId = "TERM-TEST-SWEEP"
                registeredAt = now
                lastSeenAt = now
            }

            val bookId = books.fiscalBookFor(shop).id.value
            repo.createEntry(
                shop = shop,
                employee = employee,
                headerData =
                    KassenbuchDto.KassenbuchData(
                        entryType = KassenbuchEntryType.CASH_DEPOSIT,
                        belegNr = repo.nextBelegNrBlocking(bookId),
                        vorzBetrag = "100.00",
                        belegDatum = "01.08.2026",
                        belegtext = "Anfangsbestand",
                        cashBookId = bookId,
                    ),
                lineItemsData = listOf(KassenbuchDto.LineItemData(vorzBetrag = "100.00", ustSatz = null)),
                tseTransaction = null,
            )
        }
    }

    @Test
    fun `the difference belongs to the closing that followed it`() =
        testApp(setupData = { seedTillWith100() }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)

            val countResponse =
                client.post("/api/v1/kassenbuch/cash-count") {
                    bearerAuth(token)
                    setBody(
                        TextContent(
                            Json.encodeToString(
                                KassenbuchDto.CashCountRequestDto.serializer(),
                                KassenbuchDto.CashCountRequestDto(countedAmount = "130.00"),
                            ),
                            ContentType.Application.Json,
                        ),
                    )
                }
            assertEquals(HttpStatusCode.OK, countResponse.status, countResponse.bodyAsText())
            val entryId = assertNotNull(countResponse.body<KassenbuchDto.CashCountResponseDto>().entryId)

            val zBonResponse = client.post("/api/z-bon") { bearerAuth(token) }
            assertEquals(HttpStatusCode.Created, zBonResponse.status, zBonResponse.bodyAsText())

            transaction {
                val difference = KassenbuchHeader.findById(entryId)!!
                assertNotNull(
                    difference.zBon,
                    "the drawer difference must be swept into the closing that followed it, " +
                        "not stranded for the next one",
                )
                assertEquals(
                    KassenbuchEntryType.CASH_SURPLUS,
                    difference.entryType,
                    "sanity: the swept entry is the difference this test booked",
                )
            }
        }
}
