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
import kotlinx.serialization.json.Json
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.setup.ShopConfigurationDto
import org.example.project.core.domain.enums.setup.StartPageTarget
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The server-wide web-access settings: the API-documentation switch and the default page for `/`,
 * both saved by one audited route.
 */
class WebAccessConfigRoutesTest {
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

    private suspend fun HttpClient.getWebAccess(token: String): ShopConfigurationDto.WebAccessResponseDto {
        val response = get("/api/v1/config/${shopId()}/web-access") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return testJson.decodeFromString(ShopConfigurationDto.WebAccessResponseDto.serializer(), response.bodyAsText())
    }

    private suspend fun HttpClient.putWebAccess(
        token: String,
        docs: Boolean,
        start: String,
    ): HttpResponse {
        val body =
            Json.encodeToString(
                ShopConfigurationDto.UpdateWebAccessRequestDto.serializer(),
                ShopConfigurationDto.UpdateWebAccessRequestDto(apiDocsEnabled = docs, startPageDefault = start),
            )
        return put("/api/v1/config/${shopId()}/web-access") {
            bearerAuth(token)
            setBody(TextContent(body, ContentType.Application.Json))
        }
    }

    @Test
    fun `GET reports defaults`() =
        testApp(setupData = { seed() }) { client ->
            try {
                TestData.clearWebAccessSettings()
                TestData.setBrowserStationRoles(emptySet())
                val token = TestAuthHelper.loginAsAdmin(client)

                val dto = client.getWebAccess(token)

                assertFalse(dto.apiDocsSetting)
                assertFalse(dto.apiDocsLockedByServerConfig)
                assertEquals("none", dto.startPageDefault)
                assertEquals(listOf("none", "admin"), dto.availableStartPageTargets)
            } finally {
                TestData.clearWebAccessSettings()
                TestData.setBrowserStationRoles(emptySet())
            }
        }

    @Test
    fun `GET offers pos while a browser-station role is enabled`() =
        testApp(setupData = { seed() }) { client ->
            try {
                TestData.clearWebAccessSettings()
                TestData.setBrowserStationRoles(setOf(TerminalRole.CASHIER))
                val token = TestAuthHelper.loginAsAdmin(client)

                assertEquals(listOf("none", "admin", "pos"), client.getWebAccess(token).availableStartPageTargets)
            } finally {
                TestData.clearWebAccessSettings()
                TestData.setBrowserStationRoles(emptySet())
            }
        }

    @Test
    fun `PUT saves both settings and writes one audit row`() =
        testApp(setupData = { seed() }) { client ->
            try {
                TestData.clearWebAccessSettings()
                val token = TestAuthHelper.loginAsAdmin(client)

                val response = client.putWebAccess(token, docs = true, start = "admin")

                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                assertTrue(ShopConfigurationManager.isApiDocsSettingEnabled())
                assertEquals(StartPageTarget.ADMIN, ShopConfigurationManager.getStartPageDefault())
                val dto = client.getWebAccess(token)
                assertTrue(dto.apiDocsSetting)
                assertEquals("admin", dto.startPageDefault)
                val rows = transaction { TransactionAuditLog.all().filter { it.operation == "UPDATE_WEB_ACCESS" }.toList() }
                assertEquals(1, rows.size)
            } finally {
                TestData.clearWebAccessSettings()
            }
        }

    @Test
    fun `pos is refused as default while browser stations are off`() =
        testApp(setupData = { seed() }) { client ->
            try {
                TestData.clearWebAccessSettings()
                TestData.setBrowserStationRoles(emptySet())
                val token = TestAuthHelper.loginAsAdmin(client)

                val refused = client.putWebAccess(token, docs = false, start = "pos")
                assertEquals(HttpStatusCode.BadRequest, refused.status, refused.bodyAsText())
                assertEquals("VALIDATION_FAILED", testJson.decodeFromString(ProblemDetail.serializer(), refused.bodyAsText()).code)
                assertEquals(StartPageTarget.NONE, ShopConfigurationManager.getStartPageDefault())

                TestData.setBrowserStationRoles(setOf(TerminalRole.CASHIER))
                val accepted = client.putWebAccess(token, docs = false, start = "pos")
                assertEquals(HttpStatusCode.OK, accepted.status, accepted.bodyAsText())
                assertEquals(StartPageTarget.POS, ShopConfigurationManager.getStartPageDefault())
            } finally {
                TestData.setBrowserStationRoles(emptySet())
                TestData.clearWebAccessSettings()
            }
        }

    @Test
    fun `an unknown start target is a 400 and stores nothing`() =
        testApp(setupData = { seed() }) { client ->
            try {
                TestData.clearWebAccessSettings()
                val token = TestAuthHelper.loginAsAdmin(client)

                val response = client.putWebAccess(token, docs = true, start = "https://evil.example")

                assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
                assertFalse(ShopConfigurationManager.isApiDocsSettingEnabled())
            } finally {
                TestData.clearWebAccessSettings()
            }
        }

    @Test
    fun `a cashier may not change it`() =
        testApp(setupData = { seed() }) { client ->
            try {
                TestData.clearWebAccessSettings()
                val token = TestAuthHelper.loginAsCashier(client)

                val response = client.putWebAccess(token, docs = true, start = "none")

                assertEquals(HttpStatusCode.Forbidden, response.status, response.bodyAsText())
                assertFalse(ShopConfigurationManager.isApiDocsSettingEnabled())
            } finally {
                TestData.clearWebAccessSettings()
            }
        }
}
