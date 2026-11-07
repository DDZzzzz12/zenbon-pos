package org.example.project.features.maintenance

import at.favre.lib.crypto.bcrypt.BCrypt
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.project.core.security.JwtConfig
import org.example.project.testutils.testApp
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MaintenanceLoginRoutesTest {
    private val password = "route-test-password"
    private val enabled = MaintenanceConfig("maint", BCrypt.withDefaults().hashToString(4, password.toCharArray()), 30)

    @BeforeEach
    fun enableFeature() {
        MaintenancePolicy.useForTesting(enabled)
    }

    @AfterEach
    fun restore() {
        MaintenancePolicy.useForTesting(null)
    }

    private suspend fun HttpClient.login(
        username: String = "maint",
        pw: String = password,
    ): HttpResponse =
        post("/api/maintenance/login") {
            setBody(TextContent("""{"username":"$username","password":"$pw"}""", ContentType.Application.Json))
        }

    @Test
    fun `the right credentials return a token that validates as a maintainer token`() =
        testApp { client ->
            val response = client.login()
            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val token = body.getValue("accessToken").jsonPrimitive.content
            assertNotNull(JwtConfig.validateMaintainerToken(token))
            assertEquals("1800", body.getValue("expiresInSeconds").jsonPrimitive.content)
        }

    @Test
    fun `the token response must not be cached`() =
        testApp { client ->
            val response = client.login()
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        }

    @Test
    fun `a wrong password and a wrong username are indistinguishable`() =
        testApp { client ->
            val wrongPassword = client.login(pw = "nope")
            val wrongUser = client.login(username = "someone-else")
            assertEquals(HttpStatusCode.Unauthorized, wrongPassword.status)
            assertEquals(HttpStatusCode.Unauthorized, wrongUser.status)
            val a = Json.parseToJsonElement(wrongPassword.bodyAsText()).jsonObject
            val b = Json.parseToJsonElement(wrongUser.bodyAsText()).jsonObject
            assertEquals(a.getValue("code"), b.getValue("code"))
            assertEquals(a.getValue("detail"), b.getValue("detail"))
            assertEquals("AUTH_INVALID_CREDENTIALS", a.getValue("code").jsonPrimitive.content)
        }

    @Test
    fun `the fifth failure answers 429 with Retry-After, even for the right password`() =
        testApp { client ->
            repeat(5) { client.login(pw = "nope") }
            val response = client.login()
            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            assertEquals("30", response.headers[HttpHeaders.RetryAfter])
            assertTrue("MAINTENANCE_LOGIN_THROTTLED" in response.bodyAsText())
        }

    @Test
    fun `while the feature is off login answers 404`() =
        testApp { client ->
            MaintenancePolicy.useForTesting(MaintenanceConfig.DISABLED)
            assertEquals(HttpStatusCode.NotFound, client.login().status)
        }
}
