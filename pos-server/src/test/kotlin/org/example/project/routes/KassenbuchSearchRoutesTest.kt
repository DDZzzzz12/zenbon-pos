package org.example.project.routes

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testKassenbuchDocuments
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves `GET /api/v1/kassenbuch/entries/search` is mounted, wins over `/entries/{entryId}`, and is
 * permission-registered in [org.example.project.infrastructure.security.RoutePermissions] — the
 * same two failure modes [CashBookCloseRoutesTest] guards. A missing `RoutePermissions` entry
 * denies before routing runs, turning even the 401 case into a 403.
 *
 * The 200 case carries extra weight here. `search` is a constant segment competing with the
 * `{entryId}` parameter declared in the same `route("/api/v1/kassenbuch")` block; if that one won,
 * the request would reach `getEntryById`, fail `"search".toLongOrNull()` and answer **400** — which
 * the blank-`q` test below would have accepted as correct. Only a real 200 with a real hit
 * separates "search route works" from "id route rejected a non-numeric id".
 *
 * The query itself (case-insensitivity, shop scoping, the limit cap) is covered directly by
 * [org.example.project.features.kassenbuch.KassenbuchSearchTest].
 */
@DisplayName("Kassenbuch Search Route")
class KassenbuchSearchRoutesTest {
    private fun seedMetroEntry(shop: ShopInfo) {
        val employee = TestData.createAdmin()
        transaction {
            val books = CashBookRepositoryImpl()
            val kassenbuch = KassenbuchRepositoryImpl(testKassenbuchDocuments())
            val fiscal = books.fiscalBookFor(shop)
            kassenbuch.createEntry(
                shop = shop,
                employee = employee,
                headerData =
                    KassenbuchDto.KassenbuchData(
                        entryType = KassenbuchEntryType.CASH_EXPENSE,
                        belegNr = "2026-03-0042",
                        vorzBetrag = "-50.00",
                        belegDatum = "15.03.2026",
                        belegtext = "Metro Wareneinkauf",
                        cashBookId = fiscal.id.value,
                    ),
                lineItemsData = listOf(KassenbuchDto.LineItemData(vorzBetrag = "-50.00", ustSatz = "19")),
            )
        }
    }

    @Test
    fun `returns 401 without auth`() =
        testApp { client ->
            val response = client.get("/api/v1/kassenbuch/entries/search?q=metro")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `returns 403 for cashier role`() =
        testApp(
            setupData = { TestData.createCashier() },
        ) { client ->
            val token = TestAuthHelper.loginAsCashier(client)
            val response = client.get("/api/v1/kassenbuch/entries/search?q=metro") { bearerAuth(token) }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `returns 400 when q is missing`() =
        testApp(
            setupData = { TestData.createAdmin() },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response = client.get("/api/v1/kassenbuch/entries/search") { bearerAuth(token) }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `returns 400 when q is blank`() =
        testApp(
            setupData = { TestData.createAdmin() },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response = client.get("/api/v1/kassenbuch/entries/search?q=%20") { bearerAuth(token) }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `finds an entry outside any date window, proving search beats the entryId route`() =
        testApp(
            setupData = { seedMetroEntry(TestData.createShopInfo()) },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response = client.get("/api/v1/kassenbuch/entries/search?q=metro") { bearerAuth(token) }

            // 400 here would mean "/entries/{entryId}" swallowed the request.
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("2026-03-0042"), "expected the seeded belegNr in the response, got: $body")
        }

    @Test
    fun `cashBookIds narrows the search to the given books`() =
        testApp(
            setupData = { seedMetroEntry(TestData.createShopInfo()) },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            // 999999 is no book of this shop, so a correctly applied filter yields nothing. If the
            // parameter were ignored the seeded entry would come back.
            val response = client.get("/api/v1/kassenbuch/entries/search?q=metro&cashBookIds=999999") { bearerAuth(token) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(!body.contains("2026-03-0042"), "cashBookIds filter was ignored, got: $body")
        }
}
