package org.example.project.routes

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.http.content.*
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testKassenbuchDocuments
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("Kassenbuch Routes")
class KassenbuchRoutesTest {
    @Nested
    @DisplayName("POST /api/v1/kassenbuch")
    inner class CreateKassenbuchEntry {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.post("/api/v1/kassenbuch") {
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 403 for cashier role`() =
            testApp(
                setupData = { TestData.createCashier() },
            ) { client ->
                val token = TestAuthHelper.loginAsCashier(client)
                val response =
                    client.post("/api/v1/kassenbuch") {
                        bearerAuth(token)
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Forbidden, response.status)
            }

        @Test
        fun `returns 400 for invalid request body`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.post("/api/v1/kassenbuch") {
                        bearerAuth(token)
                        setBody(TextContent("not json", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 400 for empty lines`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val json =
                    """
                    {
                        "entryType": "CASH_SALE",
                        "lines": [],
                        "belegtext": "Test sale"
                    }
                    """.trimIndent()
                val response =
                    client.post("/api/v1/kassenbuch") {
                        bearerAuth(token)
                        setBody(TextContent(json, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 400 for blank belegtext`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val json =
                    """
                    {
                        "entryType": "CASH_SALE",
                        "lines": [{"vatRate": "19", "amount": 100.00}],
                        "belegtext": ""
                    }
                    """.trimIndent()
                val response =
                    client.post("/api/v1/kassenbuch") {
                        bearerAuth(token)
                        setBody(TextContent(json, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 400 for invalid vatRate`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val json =
                    """
                    {
                        "entryType": "CASH_SALE",
                        "lines": [{"vatRate": "abc", "amount": 100.00}],
                        "belegtext": "Test"
                    }
                    """.trimIndent()
                val response =
                    client.post("/api/v1/kassenbuch") {
                        bearerAuth(token)
                        setBody(TextContent(json, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 400 for invalid amount`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val json =
                    """
                    {
                        "entryType": "CASH_SALE",
                        "lines": [{"vatRate": "19", "amount": -50.0}],
                        "belegtext": "Test"
                    }
                    """.trimIndent()
                val response =
                    client.post("/api/v1/kassenbuch") {
                        bearerAuth(token)
                        setBody(TextContent(json, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 400 for invalid currency code`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val json =
                    """
                    {
                        "entryType": "CASH_SALE",
                        "lines": [{"vatRate": "19", "amount": 100.00}],
                        "belegtext": "Test",
                        "waehrung": "EURO"
                    }
                    """.trimIndent()
                val response =
                    client.post("/api/v1/kassenbuch") {
                        bearerAuth(token)
                        setBody(TextContent(json, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/v1/kassenbuch/balance")
    inner class GetBalance {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/v1/kassenbuch/balance")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 403 for cashier role`() =
            testApp(
                setupData = { TestData.createCashier() },
            ) { client ->
                val token = TestAuthHelper.loginAsCashier(client)
                val response = client.get("/api/v1/kassenbuch/balance") { bearerAuth(token) }
                assertEquals(HttpStatusCode.Forbidden, response.status)
            }

        @Test
        fun `returns 200 with valid auth`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createShopInfo()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/v1/kassenbuch/balance") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `defaults to the FISCAL book's balance, not the sum of all books`() =
            testApp(
                setupData = {
                    val shop = TestData.createShopInfo()
                    val employee = TestData.createAdmin()
                    transaction {
                        val books = CashBookRepositoryImpl()
                        val kassenbuch = KassenbuchRepositoryImpl(testKassenbuchDocuments())
                        val fiscal = books.fiscalBookFor(shop)
                        val tresor = books.create(shop, "Tresor", false, "1010")

                        fun deposit(
                            bookId: Long,
                            amount: String,
                        ) {
                            kassenbuch.createEntry(
                                shop = shop,
                                employee = employee,
                                headerData =
                                    KassenbuchDto.KassenbuchData(
                                        entryType = KassenbuchEntryType.CASH_DEPOSIT,
                                        belegNr = kassenbuch.nextBelegNrBlocking(bookId),
                                        vorzBetrag = amount,
                                        belegDatum = "01.08.2026",
                                        belegtext = "Test deposit",
                                        cashBookId = bookId,
                                    ),
                                lineItemsData = listOf(KassenbuchDto.LineItemData(vorzBetrag = amount, ustSatz = null)),
                            )
                        }

                        // Fiscal book gets the SMALLER amount deliberately: if /balance ever
                        // regressed to summing all books, this would fail (100.00 != 600.00), and
                        // if it regressed to reading the largest/most-recent book, it would also
                        // fail (100.00 != 500.00) — only the fiscal-book default produces 100,00.
                        deposit(fiscal.id.value, "100.00")
                        deposit(tresor.id.value, "500.00")
                    }
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/v1/kassenbuch/balance") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("100.00", response.bodyAsText())

                // The legacy ?raw=true switch is still accepted and answers the same canonical form.
                val raw = client.get("/api/v1/kassenbuch/balance?raw=true") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, raw.status)
                assertEquals("100.00", raw.bodyAsText())
            }

        /**
         * admin16 NOTE-5: the default answer was the German display text ("5.524,72"), the one
         * money value on the wire no money parser accepts. It is a canonical decimal now, with no
         * grouping separator even above a thousand.
         */
        @Test
        fun `answers a canonical decimal without grouping, never the German display form`() =
            testApp(
                setupData = {
                    val shop = TestData.createShopInfo()
                    val employee = TestData.createAdmin()
                    transaction {
                        val kassenbuch = KassenbuchRepositoryImpl(testKassenbuchDocuments())
                        val fiscal = CashBookRepositoryImpl().fiscalBookFor(shop)
                        kassenbuch.createEntry(
                            shop = shop,
                            employee = employee,
                            headerData =
                                KassenbuchDto.KassenbuchData(
                                    entryType = KassenbuchEntryType.CASH_DEPOSIT,
                                    belegNr = kassenbuch.nextBelegNrBlocking(fiscal.id.value),
                                    vorzBetrag = "5524.72",
                                    belegDatum = "01.08.2026",
                                    belegtext = "Test deposit",
                                    cashBookId = fiscal.id.value,
                                ),
                            lineItemsData = listOf(KassenbuchDto.LineItemData(vorzBetrag = "5524.72", ustSatz = null)),
                        )
                    }
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val body = client.get("/api/v1/kassenbuch/balance") { bearerAuth(token) }.bodyAsText()
                assertEquals("5524.72", body)
            }

        @Test
        fun `an unknown cashBookId is 404, not an empty book`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createShopInfo()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)

                // The live defect (checklist 21.2): 200 "0,00" for a book that does not exist.
                for (path in listOf("/api/v1/kassenbuch/balance?cashBookId=999999", "/api/v1/kassenbuch/balance?cashBookId=999999&raw=true")) {
                    val response = client.get(path) { bearerAuth(token) }
                    assertEquals(HttpStatusCode.NotFound, response.status, path)
                    assertTrue(response.bodyAsText().contains("RESOURCE_NOT_FOUND"), path)
                }
            }
    }

    @Nested
    @DisplayName("GET /api/v1/kassenbuch/entries")
    inner class GetEntries {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/v1/kassenbuch/entries?startDate=2026-01-01&endDate=2026-12-31")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 403 for cashier role`() =
            testApp(
                setupData = { TestData.createCashier() },
            ) { client ->
                val token = TestAuthHelper.loginAsCashier(client)
                val response = client.get("/api/v1/kassenbuch/entries?startDate=2026-01-01&endDate=2026-12-31") { bearerAuth(token) }
                assertEquals(HttpStatusCode.Forbidden, response.status)
            }

        @Test
        fun `returns 400 when startDate missing`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/v1/kassenbuch/entries?endDate=2026-12-31") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 400 when endDate missing`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/v1/kassenbuch/entries?startDate=2026-01-01") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 200 with valid date params`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createShopInfo()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/v1/kassenbuch/entries?startDate=2026-01-01&endDate=2026-12-31") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/v1/kassenbuch/entries/recent")
    inner class GetRecentEntries {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/v1/kassenbuch/entries/recent")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 403 for cashier role`() =
            testApp(
                setupData = { TestData.createCashier() },
            ) { client ->
                val token = TestAuthHelper.loginAsCashier(client)
                val response = client.get("/api/v1/kassenbuch/entries/recent") { bearerAuth(token) }
                assertEquals(HttpStatusCode.Forbidden, response.status)
            }

        @Test
        fun `returns 200 with valid auth`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createShopInfo()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/v1/kassenbuch/entries/recent?limit=5") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 200 with default limit`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createShopInfo()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/v1/kassenbuch/entries/recent") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/v1/kassenbuch/entries/{entryId}")
    inner class GetEntryById {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/v1/kassenbuch/entries/1")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 for invalid entry ID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/v1/kassenbuch/entries/invalid") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/v1/kassenbuch/entries/grouped/{headerId}")
    inner class GetGroupedEntryDetail {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/v1/kassenbuch/entries/grouped/1")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 for invalid header ID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/v1/kassenbuch/entries/grouped/invalid") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/v1/kassenbuch/export/datev-xml")
    inner class ExportDatevXml {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/v1/kassenbuch/export/datev-xml?startDate=2026-01-01&endDate=2026-12-31")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 when startDate missing`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/v1/kassenbuch/export/datev-xml?endDate=2026-12-31") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 400 when endDate missing`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/v1/kassenbuch/export/datev-xml?startDate=2026-01-01") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/v1/kassenbuch/export/buchungsstapel")
    inner class ExportBuchungsstapel {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/v1/kassenbuch/export/buchungsstapel?startDate=2026-01-01&endDate=2026-12-31")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 when startDate missing`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/v1/kassenbuch/export/buchungsstapel?endDate=2026-12-31") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 400 when endDate missing`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/v1/kassenbuch/export/buchungsstapel?startDate=2026-01-01") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/v1/kassenbuch/export/datev-xml/statistics")
    inner class GetDatevXmlStatistics {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/v1/kassenbuch/export/datev-xml/statistics?startDate=2026-01-01&endDate=2026-12-31")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 when startDate missing`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/v1/kassenbuch/export/datev-xml/statistics?endDate=2026-12-31") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 400 when endDate missing`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/v1/kassenbuch/export/datev-xml/statistics?startDate=2026-01-01") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }

    @Nested
    @DisplayName("PUT /api/v1/kassenbuch/entries/{headerId}/link-document")
    inner class LinkInvoice {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.put("/api/v1/kassenbuch/entries/1/link-document") {
                        setBody(TextContent("""{"documentId": 1}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 for invalid header ID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.put("/api/v1/kassenbuch/entries/invalid/link-document") {
                        bearerAuth(token)
                        setBody(TextContent("""{"documentId": 1}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 400 for invalid request body`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.put("/api/v1/kassenbuch/entries/1/link-document") {
                        bearerAuth(token)
                        setBody(TextContent("not json", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }

    @Nested
    @DisplayName("DELETE /api/v1/kassenbuch/entries/{headerId}/link-document")
    inner class UnlinkInvoice {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.delete("/api/v1/kassenbuch/entries/1/link-document")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 for invalid header ID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.delete("/api/v1/kassenbuch/entries/invalid/link-document") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }
}
