package org.example.project.routes

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the transfer routes are mounted (not 404) AND permission-registered in
 * [org.example.project.infrastructure.security.RoutePermissions] (not a blanket 403) — the two
 * failure modes Task 18 warns about. Happy-path 201s are covered by the direct service tests in
 * [org.example.project.features.kassenbuch.CashBookTransferTest].
 */
@DisplayName("Cash Book Transfer Routes")
class CashBookRoutesTest {
    @Nested
    @DisplayName("POST /api/v1/kassenbuch/transfers")
    inner class CreateTransfer {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.post("/api/v1/kassenbuch/transfers") {
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
                    client.post("/api/v1/kassenbuch/transfers") {
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
                    client.post("/api/v1/kassenbuch/transfers") {
                        bearerAuth(token)
                        setBody(TextContent("not json", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 400 when fromBookId equals toBookId`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val json =
                    """
                    {
                        "fromBookId": 1,
                        "toBookId": 1,
                        "amount": "10.00",
                        "belegDatum": "01.01.2026"
                    }
                    """.trimIndent()
                val response =
                    client.post("/api/v1/kassenbuch/transfers") {
                        bearerAuth(token)
                        setBody(TextContent(json, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }

    @Nested
    @DisplayName("POST /api/v1/kassenbuch/books")
    inner class CreateBook {
        @Test
        fun `a second book with an existing name answers 409 CASH_BOOK_NAME_ALREADY_TAKEN`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createShopInfo()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val body = """{"name":"Tresor","datevAccount":"1010"}"""

                val first =
                    client.post("/api/v1/kassenbuch/books") {
                        bearerAuth(token)
                        setBody(TextContent(body, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Created, first.status)

                // Checklist 21.1 step 4: the repeat answered 201 and created a second "Tresor".
                val second =
                    client.post("/api/v1/kassenbuch/books") {
                        bearerAuth(token)
                        setBody(TextContent(body, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Conflict, second.status)
                assertTrue(second.bodyAsText().contains("CASH_BOOK_NAME_ALREADY_TAKEN"))
            }
    }

    @Nested
    @DisplayName("POST /api/v1/kassenbuch/transfers/{transferId}/reverse")
    inner class ReverseTransfer {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.post("/api/v1/kassenbuch/transfers/some-id/reverse") {
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
                    client.post("/api/v1/kassenbuch/transfers/some-id/reverse") {
                        bearerAuth(token)
                        setBody(TextContent("""{"reason": "test"}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Forbidden, response.status)
            }

        @Test
        fun `returns 400 for blank reason`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.post("/api/v1/kassenbuch/transfers/some-id/reverse") {
                        bearerAuth(token)
                        setBody(TextContent("""{"reason": ""}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }

    @Nested
    @DisplayName("/api/v1/kassenbuch/books/{bookId}/signing-terminal")
    inner class SigningTerminal {
        @Test
        fun `candidates answer 401 without auth`() =
            testApp { client ->
                assertEquals(
                    HttpStatusCode.Unauthorized,
                    client.get("/api/v1/kassenbuch/books/1/signing-terminal/candidates").status,
                )
            }

        @Test
        fun `a cashier is refused on both routes`() =
            testApp(
                setupData = { TestData.createCashier() },
            ) { client ->
                val token = TestAuthHelper.loginAsCashier(client)
                val candidates =
                    client.get("/api/v1/kassenbuch/books/1/signing-terminal/candidates") { bearerAuth(token) }
                assertEquals(HttpStatusCode.Forbidden, candidates.status)
                val put =
                    client.put("/api/v1/kassenbuch/books/1/signing-terminal") {
                        bearerAuth(token)
                        setBody(TextContent("""{"terminalId":null}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Forbidden, put.status)
            }

        @Test
        fun `a manager lists candidates and clears the designation`() =
            testApp(
                setupData = {
                    val shop = TestData.createShopInfo()
                    TestData.createManager()
                    transaction { CashBookRepositoryImpl().fiscalBookFor(shop) }
                },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val bookId = fiscalBookId(client, token)
                val candidates =
                    client.get("/api/v1/kassenbuch/books/$bookId/signing-terminal/candidates") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, candidates.status)
                val put =
                    client.put("/api/v1/kassenbuch/books/$bookId/signing-terminal") {
                        bearerAuth(token)
                        setBody(TextContent("""{"terminalId":null}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.OK, put.status)
            }

        private suspend fun fiscalBookId(
            client: io.ktor.client.HttpClient,
            token: String,
        ): Long {
            val body = client.get("/api/v1/kassenbuch/books") { bearerAuth(token) }.bodyAsText()
            return kotlinx.serialization.json.Json
                .parseToJsonElement(body)
                .jsonArray
                .first {
                    it.jsonObject
                        .getValue("isFiscal")
                        .jsonPrimitive.boolean
                }.jsonObject
                .getValue("id")
                .jsonPrimitive.long
        }
    }
}
