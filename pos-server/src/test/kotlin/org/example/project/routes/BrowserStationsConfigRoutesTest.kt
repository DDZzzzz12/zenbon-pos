package org.example.project.routes

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.content.TextContent
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.setup.ShopConfigurationDto
import org.example.project.core.domain.enums.terminal.TerminalRole
import org.example.project.core.error.ProblemDetail
import org.example.project.features.audit.domain.models.TransactionAuditLog
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The `browser_stations` shop setting: which terminal roles may run as a browser station.
 * Off by default; only CASHIER and ORDER_STATION can be enabled.
 */
class BrowserStationsConfigRoutesTest {
    private fun seed() {
        TestData.createAdmin()
        TestData.createCashier()
        TestData.createShopInfo()
    }

    private fun shopId(): Long =
        transaction {
            ShopInfo
                .all()
                .limit(1)
                .first()
                .id.value
        }

    private suspend fun HttpClient.putRoles(
        token: String,
        body: String,
    ): HttpResponse =
        put("/api/v1/config/${shopId()}/browser-stations") {
            bearerAuth(token)
            setBody(TextContent(body, ContentType.Application.Json))
        }

    @Test
    fun `default is no roles`() =
        testApp(setupData = { seed() }) { client ->
            TestData.setBrowserStationRoles(emptySet())
            val token = TestAuthHelper.loginAsAdmin(client)

            val response = client.get("/api/v1/config/${shopId()}/browser-stations") { bearerAuth(token) }

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val dto = testJson.decodeFromString(ShopConfigurationDto.BrowserStationsResponseDto.serializer(), response.bodyAsText())
            assertEquals(emptyList(), dto.roles)
        }

    @Test
    fun `PUT stores roles and the manager sees them`() =
        testApp(setupData = { seed() }) { client ->
            TestData.setBrowserStationRoles(emptySet())
            val token = TestAuthHelper.loginAsAdmin(client)

            val response = client.putRoles(token, """{"roles":["CASHIER"]}""")

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertEquals(setOf(TerminalRole.CASHIER), ShopConfigurationManager.getBrowserStationRoles())
            val read = client.get("/api/v1/config/${shopId()}/browser-stations") { bearerAuth(token) }
            val dto = testJson.decodeFromString(ShopConfigurationDto.BrowserStationsResponseDto.serializer(), read.bodyAsText())
            assertEquals(listOf("CASHIER"), dto.roles)
        }

    @Test
    fun `KITCHEN is rejected with 400 VALIDATION_FAILED and stores nothing`() =
        testApp(setupData = { seed() }) { client ->
            TestData.setBrowserStationRoles(emptySet())
            val token = TestAuthHelper.loginAsAdmin(client)

            val response = client.putRoles(token, """{"roles":["KITCHEN"]}""")

            assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
            val problem = testJson.decodeFromString(ProblemDetail.serializer(), response.bodyAsText())
            assertEquals("VALIDATION_FAILED", problem.code)
            assertEquals(emptySet(), ShopConfigurationManager.getBrowserStationRoles())
        }

    @Test
    fun `a cashier cannot write`() =
        testApp(setupData = { seed() }) { client ->
            TestData.setBrowserStationRoles(emptySet())
            val token = TestAuthHelper.loginAsCashier(client)

            val response = client.putRoles(token, """{"roles":["CASHIER"]}""")

            assertEquals(HttpStatusCode.Forbidden, response.status, response.bodyAsText())
            assertEquals(emptySet(), ShopConfigurationManager.getBrowserStationRoles())
        }

    @Test
    fun `a write is audited`() =
        testApp(setupData = { seed() }) { client ->
            TestData.setBrowserStationRoles(emptySet())
            val token = TestAuthHelper.loginAsAdmin(client)

            val response = client.putRoles(token, """{"roles":["CASHIER","ORDER_STATION"]}""")

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val rows = transaction { TransactionAuditLog.all().filter { it.operation == "UPDATE_BROWSER_STATIONS" }.toList() }
            assertEquals(1, rows.size)
            assertEquals(setOf(TerminalRole.CASHIER, TerminalRole.ORDER_STATION), ShopConfigurationManager.getBrowserStationRoles())
        }
}
