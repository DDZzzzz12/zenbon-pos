package org.example.project.routes

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChangelogRoutesTest {
    @Test
    fun `GET changelog returns 200 with the 1_0_10 backfill row when authenticated`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createShopInfo()
                TestData.createDefaultVatRates()
                TestData.createChangelogRelease()
            },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val res = client.get("/api/v1/changelog?page=0&size=20") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, res.status, "body: ${res.bodyAsText()}")
            assertTrue(res.bodyAsText().contains("\"releaseVersion\":\"1.0.10\""), "backfill row missing")
            assertTrue(res.bodyAsText().contains("\"highlightsEn\""))
        }

    @Test
    fun `GET changelog without bearer returns 401`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createShopInfo()
                TestData.createDefaultVatRates()
            },
        ) { client ->
            val res = client.get("/api/v1/changelog")
            assertEquals(HttpStatusCode.Unauthorized, res.status)
        }

    @Test
    fun `GET changelog with size over 100 returns 400`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createShopInfo()
                TestData.createDefaultVatRates()
            },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val res = client.get("/api/v1/changelog?size=999") { bearerAuth(token) }
            assertEquals(HttpStatusCode.BadRequest, res.status)
        }

    @Test
    fun `GET changelog with negative page returns 400`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createShopInfo()
                TestData.createDefaultVatRates()
            },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val res = client.get("/api/v1/changelog?page=-1") { bearerAuth(token) }
            assertEquals(HttpStatusCode.BadRequest, res.status)
        }
}
