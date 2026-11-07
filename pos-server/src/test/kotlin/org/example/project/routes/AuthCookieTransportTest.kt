package org.example.project.routes

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.http.setCookie
import org.example.project.auth.data.LoginRequest
import org.example.project.auth.data.LoginResponse
import org.example.project.core.utils.AuthTransport
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("Auth cookie transport")
class AuthCookieTransportTest {
    private suspend fun HttpClient.login(cookieTransport: Boolean): HttpResponse =
        post("/api/auth/login") {
            if (cookieTransport) header(AuthTransport.HEADER, AuthTransport.COOKIE)
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(identifier = "admin", secret = TestData.DEFAULT_PASSWORD))
        }

    private fun HttpResponse.refreshCookie(): Cookie? = setCookie().singleOrNull { it.name == "__Host-pos_refresh" }

    private suspend fun HttpResponse.loginBody(): LoginResponse = testJson.decodeFromString(bodyAsText())

    @Test
    fun `login with cookie transport sets a hardened cookie and keeps the token out of the body`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            val response = client.login(cookieTransport = true)

            assertEquals(HttpStatusCode.OK, response.status)
            val cookie = assertNotNull(response.refreshCookie())
            assertTrue(cookie.value.isNotBlank())
            assertTrue(cookie.httpOnly, "HttpOnly")
            assertTrue(cookie.secure, "Secure")
            assertEquals("/", cookie.path)
            assertNull(cookie.domain, "__Host- cookies must be host-only")
            assertEquals("Strict", cookie.extensions["SameSite"])
            assertTrue((cookie.maxAge ?: 0) > 0)
            assertNull(response.loginBody().token.refreshToken)
        }

    @Test
    fun `login without the header is unchanged for native clients`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            val response = client.login(cookieTransport = false)

            assertNull(response.refreshCookie())
            assertNotNull(response.loginBody().token.refreshToken)
        }

    @Test
    fun `refresh reads the cookie and rotates it`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            val first = assertNotNull(client.login(cookieTransport = true).refreshCookie()).value

            val response =
                client.post("/api/auth/refresh") {
                    header(AuthTransport.HEADER, AuthTransport.COOKIE)
                    header(HttpHeaders.Cookie, "__Host-pos_refresh=$first")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val second = assertNotNull(response.refreshCookie()).value
            assertNotEquals(first, second)
            assertNull(response.loginBody().token.refreshToken)

            // The freshly rotated cookie is itself usable for a further refresh.
            val secondResponse =
                client.post("/api/auth/refresh") {
                    header(AuthTransport.HEADER, AuthTransport.COOKIE)
                    header(HttpHeaders.Cookie, "__Host-pos_refresh=$second")
                }

            assertEquals(HttpStatusCode.OK, secondResponse.status)
        }

    @Test
    fun `refresh with the header but no cookie is 401`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            // A valid BODY refresh token must not rescue a cookie-transport request that has no
            // cookie — cookie transport reads only the cookie and never falls back to the body.
            val bodyToken =
                assertNotNull(
                    client
                        .login(cookieTransport = false)
                        .loginBody()
                        .token.refreshToken,
                )

            val response =
                client.post("/api/auth/refresh") {
                    header(AuthTransport.HEADER, AuthTransport.COOKIE)
                    setBody(TextContent("""{"refreshToken":"$bodyToken"}""", ContentType.Application.Json))
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `a cookie without the header is ignored`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            // What a cross-site form post would look like: the browser attaches the cookie, but it
            // cannot add the header, so the server must not read the cookie at all.
            val first = assertNotNull(client.login(cookieTransport = true).refreshCookie()).value

            val response =
                client.post("/api/auth/refresh") {
                    header(HttpHeaders.Cookie, "__Host-pos_refresh=$first")
                    setBody(TextContent("{}", ContentType.Application.Json))
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertNull(response.refreshCookie())
        }

    @Test
    fun `a rejected body-transport refresh sets no cookie`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            val response =
                client.post("/api/auth/refresh") {
                    setBody(TextContent("""{"refreshToken":"not-a-jwt"}""", ContentType.Application.Json))
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertNull(response.refreshCookie())
        }

    @Test
    fun `two tabs refreshing with the same cookie both succeed and the session survives`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            val shared = assertNotNull(client.login(cookieTransport = true).refreshCookie()).value

            val firstTab =
                client.post("/api/auth/refresh") {
                    header(AuthTransport.HEADER, AuthTransport.COOKIE)
                    header(HttpHeaders.Cookie, "__Host-pos_refresh=$shared")
                }
            val secondTab =
                client.post("/api/auth/refresh") {
                    header(AuthTransport.HEADER, AuthTransport.COOKIE)
                    header(HttpHeaders.Cookie, "__Host-pos_refresh=$shared")
                }

            assertEquals(HttpStatusCode.OK, firstTab.status)
            assertEquals(HttpStatusCode.OK, secondTab.status, "the second tab must not be treated as token theft")
            val latest = assertNotNull(secondTab.refreshCookie()).value
            val next =
                client.post("/api/auth/refresh") {
                    header(AuthTransport.HEADER, AuthTransport.COOKIE)
                    header(HttpHeaders.Cookie, "__Host-pos_refresh=$latest")
                }
            assertEquals(HttpStatusCode.OK, next.status, "the family must not have been revoked")
        }

    @Test
    fun `a valid body token with a cookie but no header uses only the body`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            val cookieToken = assertNotNull(client.login(cookieTransport = true).refreshCookie()).value
            val bodyToken =
                assertNotNull(
                    client
                        .login(cookieTransport = false)
                        .loginBody()
                        .token.refreshToken,
                )

            val response =
                client.post("/api/auth/refresh") {
                    header(HttpHeaders.Cookie, "__Host-pos_refresh=$cookieToken")
                    setBody(TextContent("""{"refreshToken":"$bodyToken"}""", ContentType.Application.Json))
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertNotNull(response.loginBody().token.refreshToken)
            assertNull(response.refreshCookie())

            // The cookie token was never consumed by the body-transport refresh above.
            val cookieResponse =
                client.post("/api/auth/refresh") {
                    header(AuthTransport.HEADER, AuthTransport.COOKIE)
                    header(HttpHeaders.Cookie, "__Host-pos_refresh=$cookieToken")
                }

            assertEquals(HttpStatusCode.OK, cookieResponse.status)
        }

    @Test
    fun `logout expires the cookie`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            val access =
                client
                    .login(cookieTransport = true)
                    .loginBody()
                    .token.accessToken

            val response =
                client.post("/api/auth/logout") {
                    bearerAuth(access)
                    header(AuthTransport.HEADER, AuthTransport.COOKIE)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val expired = assertNotNull(response.refreshCookie())
            assertEquals(0, expired.maxAge)
            assertTrue(expired.secure, "Secure")
            assertEquals("/", expired.path, "browsers ignore a __Host- deletion cookie on any other path")
        }

    @Test
    fun `a rejected refresh cookie is expired`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            val loginResponse = client.login(cookieTransport = true)
            val oldCookie = assertNotNull(loginResponse.refreshCookie()).value
            val access = loginResponse.loginBody().token.accessToken

            // Logout revokes without a successor, so this does not trigger the family-wide
            // revocation that a rotated-out replay would.
            val logoutResponse =
                client.post("/api/auth/logout") {
                    bearerAuth(access)
                    header(AuthTransport.HEADER, AuthTransport.COOKIE)
                }
            assertEquals(HttpStatusCode.OK, logoutResponse.status)

            val response =
                client.post("/api/auth/refresh") {
                    header(AuthTransport.HEADER, AuthTransport.COOKIE)
                    header(HttpHeaders.Cookie, "__Host-pos_refresh=$oldCookie")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            val expired = assertNotNull(response.refreshCookie())
            assertEquals(0, expired.maxAge)
            assertTrue(expired.secure, "Secure")
            assertEquals("/", expired.path)
        }
}
