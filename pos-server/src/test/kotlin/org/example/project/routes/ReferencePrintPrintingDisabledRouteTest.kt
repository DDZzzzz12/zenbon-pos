package org.example.project.routes

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import org.example.project.core.error.PosErrorCode
import org.example.project.core.error.ProblemDetail
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * SET-04: with the shop's printing disabled, `POST /api/orders/reference-print` is refused with
 * 409 `PRINTING_DISABLED` — the server half of the till/station panel withholding „Referenzdruck“
 * (rule 8: guards at both levels). The refusal comes before the table or the items are read.
 */
@DisplayName("Reference print is refused while printing is disabled")
class ReferencePrintPrintingDisabledRouteTest {
    private suspend fun HttpClient.referencePrint(token: String): HttpResponse =
        post("/api/orders/reference-print") {
            bearerAuth(token)
            setBody(TextContent("""{"tableId": 987654, "orderItemIds": []}""", ContentType.Application.Json))
        }

    private suspend fun HttpClient.setPrinting(
        adminToken: String,
        enabled: Boolean,
    ) {
        val response =
            put("/api/v1/config/1/print-settings") {
                bearerAuth(adminToken)
                setBody(TextContent("""{"enabled": $enabled}""", ContentType.Application.Json))
            }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    }

    private fun assertPrintingDisabled(
        response: HttpResponse,
        body: String,
    ) {
        assertEquals(HttpStatusCode.Conflict, response.status, body)
        assertEquals(PosErrorCode.PRINTING_DISABLED.name, testJson.decodeFromString<ProblemDetail>(body).code, body)
    }

    @Test
    fun `a cashier's reference print is refused with 409 PRINTING_DISABLED while printing is off`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createCashier()
                TestData.createShopInfo()
            },
        ) { client ->
            val admin = TestAuthHelper.loginAsAdmin(client)
            client.setPrinting(admin, enabled = false)

            val response = client.referencePrint(TestAuthHelper.loginAsCashier(client))

            assertPrintingDisabled(response, response.bodyAsText())
        }

    @Test
    fun `the refusal follows the setting - with printing on the request reaches the table lookup`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createShopInfo()
            },
        ) { client ->
            val admin = TestAuthHelper.loginAsAdmin(client)
            client.setPrinting(admin, enabled = false)
            val off = client.referencePrint(admin)
            assertPrintingDisabled(off, off.bodyAsText())

            client.setPrinting(admin, enabled = true)
            try {
                val on = client.referencePrint(admin)
                val body = on.bodyAsText()
                assertNotEquals(HttpStatusCode.Conflict, on.status, body)
                assertEquals(HttpStatusCode.NotFound, on.status, "the unknown table, not the print setting: $body")
            } finally {
                // The configuration cache is process-wide; leave printing off as the default has it.
                client.setPrinting(admin, enabled = false)
            }
        }
}
