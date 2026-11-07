package org.example.project.routes

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import org.example.project.core.domain.dto.setup.ShopConfigurationDto
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * `GET/PUT /api/v1/config/{shopId}/kassenbuch-beleg-age` — how far back a new cash-book entry's
 * Beleg date may lie. The value must survive the JSON config store (a bare number is stored as a
 * JSON number, and the getter reads it back), fall back to 31 when unset, and be refused outside
 * 1..366 before it reaches the store.
 *
 * `ShopConfigurationManager` caches process-wide, so each test that changes the limit puts the
 * default back — a leaked 14 would silently refuse other tests' backdated fixtures.
 */
@DisplayName("Config — Kassenbuch Beleg-age limit")
class KassenbuchBelegAgeConfigRoutesTest {
    private val path = "/api/v1/config/1/kassenbuch-beleg-age"

    @Test
    fun `an unset limit reads as the 31-day default`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createShopInfo()
            },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response = client.get(path) { bearerAuth(token) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(31, testJson.decodeFromString<ShopConfigurationDto.KassenbuchBelegAgeResponseDto>(response.bodyAsText()).maxAgeDays)
        }

    @Test
    fun `a saved limit is read back`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createShopInfo()
            },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            try {
                val put =
                    client.put(path) {
                        bearerAuth(token)
                        setBody(TextContent("""{"maxAgeDays":14}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.OK, put.status)

                val read = client.get(path) { bearerAuth(token) }
                assertEquals(14, testJson.decodeFromString<ShopConfigurationDto.KassenbuchBelegAgeResponseDto>(read.bodyAsText()).maxAgeDays)
            } finally {
                client.put(path) {
                    bearerAuth(token)
                    setBody(TextContent("""{"maxAgeDays":31}""", ContentType.Application.Json))
                }
            }
        }

    @Test
    fun `a limit outside 1 to 366 is refused`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createShopInfo()
            },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            for (bad in listOf(0, 367, -1)) {
                val response =
                    client.put(path) {
                        bearerAuth(token)
                        setBody(TextContent("""{"maxAgeDays":$bad}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.UnprocessableEntity, response.status, "maxAgeDays=$bad")
            }
            val read = client.get(path) { bearerAuth(token) }
            assertEquals(31, testJson.decodeFromString<ShopConfigurationDto.KassenbuchBelegAgeResponseDto>(read.bodyAsText()).maxAgeDays)
        }
}
