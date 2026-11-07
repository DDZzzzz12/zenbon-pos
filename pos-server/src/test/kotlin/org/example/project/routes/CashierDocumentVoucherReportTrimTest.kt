package org.example.project.routes

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * V130 / decision D1: a cashier no longer holds `DOCUMENT_READ` or `VOUCHER_REPORT`. The guard is the
 * capability gate on the route, so this asserts the 403 end to end — and that the manager, who got
 * both back through `MANAGER`, and the till's own voucher route are untouched.
 */
@DisplayName("Cashier is refused document reading and shop-wide voucher reporting")
class CashierDocumentVoucherReportTrimTest {
    @Test
    fun `cashier gets 403 on document search and detail reads`() =
        testApp(setupData = { TestData.createCashier() }) { client ->
            val token = TestAuthHelper.loginAsCashier(client)
            assertEquals(HttpStatusCode.Forbidden, client.get("/api/documents") { bearerAuth(token) }.status)
            assertEquals(HttpStatusCode.Forbidden, client.get("/api/documents/1") { bearerAuth(token) }.status)
            assertEquals(HttpStatusCode.Forbidden, client.get("/api/documents/document-types") { bearerAuth(token) }.status)
            assertEquals(HttpStatusCode.Forbidden, client.get("/api/documents/export/datev-ap") { bearerAuth(token) }.status)
        }

    @Test
    fun `cashier gets 403 on the voucher reporting routes`() =
        testApp(setupData = { TestData.createCashier() }) { client ->
            val token = TestAuthHelper.loginAsCashier(client)
            listOf(
                "/api/vouchers/statistics",
                "/api/vouchers/active",
                "/api/vouchers/search?q=V",
                "/api/vouchers/created-by-date?startDate=2026-01-01&endDate=2026-12-31",
                "/api/vouchers/redeemed-by-date?startDate=2026-01-01&endDate=2026-12-31",
            ).forEach { path ->
                assertEquals(HttpStatusCode.Forbidden, client.get(path) { bearerAuth(token) }.status, path)
            }
        }

    @Test
    fun `cashier keeps the till voucher routes, and the manager keeps the reads`() =
        testApp(
            setupData = {
                TestData.createCashier()
                TestData.createManager()
            },
        ) { client ->
            val cashier = TestAuthHelper.loginAsCashier(client)
            val history = client.get("/api/vouchers/number/NOPE/redemption-history") { bearerAuth(cashier) }
            assertNotEquals(HttpStatusCode.Forbidden, history.status, "VOUCHER_READ must survive for the till")
            val validate = client.post("/api/vouchers/validate") { bearerAuth(cashier) }
            assertNotEquals(HttpStatusCode.Forbidden, validate.status, "VOUCHER_READ (validate) must survive for the till")

            val manager = TestAuthHelper.loginAsManager(client)
            assertNotEquals(HttpStatusCode.Forbidden, client.get("/api/documents") { bearerAuth(manager) }.status)
            assertEquals(HttpStatusCode.OK, client.get("/api/vouchers/statistics") { bearerAuth(manager) }.status)
        }
}
