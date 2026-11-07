package org.example.project.features.kassenbuch

import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.Json
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.kassenbuch.database.tables.KassenbuchHeaderTable
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testKassenbuchDocuments
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A3 — the drawer count booked at Z-Bon time.
 *
 * The count targets the shop's FISCAL Ladenkasse. A count that matches the book must write
 * nothing at all; a difference must become one signed CASH_SURPLUS/CASH_SHORTAGE entry carrying
 * an auto-generated Eigenbeleg.
 */
@DisplayName("POST /api/v1/kassenbuch/cash-count")
class CashCountServiceTest {
    private val deviceId = "cash-count-device"

    /** Seeds an admin, a signing terminal, and a fiscal Ladenkasse holding exactly 100.00. */
    private fun seedTillWith100() {
        val employee = TestData.createAdmin()
        val shop = TestData.createShopInfo()
        TestData.createFiscalRegisteredTerminal(deviceId = deviceId, clientId = "TERM-TEST-CC")

        val repo = KassenbuchRepositoryImpl(testKassenbuchDocuments())
        val books = CashBookRepositoryImpl()
        transaction {
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

    private fun countHeaders(): Long = transaction { KassenbuchHeaderTable.selectAll().count() }

    private fun entryTypeOf(headerId: Long): KassenbuchEntryType = transaction { KassenbuchHeader.findById(headerId)!!.entryType }

    private fun documentIdOf(headerId: Long): Long? =
        transaction {
            KassenbuchHeader
                .findById(headerId)!!
                .linkedDocumentId
        }

    private fun countBody(amount: String) =
        TextContent(
            Json.encodeToString(
                KassenbuchDto.CashCountRequestDto.serializer(),
                KassenbuchDto.CashCountRequestDto(countedAmount = amount),
            ),
            ContentType.Application.Json,
        )

    @Test
    fun `a count matching the book writes nothing`() =
        testApp(setupData = { seedTillWith100() }) { client ->
            val before = countHeaders()
            val token = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)

            val response =
                client.post("/api/v1/kassenbuch/cash-count") {
                    bearerAuth(token)
                    setBody(countBody("100.00"))
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.body<KassenbuchDto.CashCountResponseDto>()
            assertEquals("0.00", body.difference)
            assertNull(body.entryId, "a matching count must create no entry")
            assertNull(body.belegNr, "a matching count must consume no belegNr")
            assertEquals(before, countHeaders(), "a matching count must write nothing at all")
        }

    @Test
    fun `counting more than the book books a signed CASH_SURPLUS with an Eigenbeleg`() =
        testApp(setupData = { seedTillWith100() }) { client ->
            val token = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)

            val response =
                client.post("/api/v1/kassenbuch/cash-count") {
                    bearerAuth(token)
                    setBody(countBody("130.00"))
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.body<KassenbuchDto.CashCountResponseDto>()
            assertEquals("100.00", body.bookBalance)
            assertEquals("30.00", body.difference)
            val entryId = assertNotNull(body.entryId)
            assertEquals(KassenbuchEntryType.CASH_SURPLUS, entryTypeOf(entryId))
            assertNotNull(documentIdOf(entryId), "the difference must carry an Eigenbeleg")
        }

    @Test
    fun `counting less than the book books a CASH_SHORTAGE`() =
        testApp(setupData = { seedTillWith100() }) { client ->
            val token = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)

            val response =
                client.post("/api/v1/kassenbuch/cash-count") {
                    bearerAuth(token)
                    setBody(countBody("80.00"))
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.body<KassenbuchDto.CashCountResponseDto>()
            assertEquals("-20.00", body.difference)
            val entryId = assertNotNull(body.entryId)
            assertEquals(KassenbuchEntryType.CASH_SHORTAGE, entryTypeOf(entryId))
            assertNotNull(documentIdOf(entryId))
        }

    @Test
    fun `a negative count is rejected and writes nothing`() =
        testApp(setupData = { seedTillWith100() }) { client ->
            val before = countHeaders()
            val token = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)

            val response =
                client.post("/api/v1/kassenbuch/cash-count") {
                    bearerAuth(token)
                    setBody(countBody("-5.00"))
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(before, countHeaders())
        }

    @Test
    fun `an unparseable count is rejected`() =
        testApp(setupData = { seedTillWith100() }) { client ->
            val token = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)

            val response =
                client.post("/api/v1/kassenbuch/cash-count") {
                    bearerAuth(token)
                    setBody(countBody("abc"))
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
}
