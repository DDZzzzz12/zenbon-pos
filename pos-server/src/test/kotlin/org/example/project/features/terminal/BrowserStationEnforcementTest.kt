package org.example.project.features.terminal

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import org.example.project.auth.data.LoginResponse
import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.terminal.TerminalRole
import org.example.project.core.error.ProblemDetail
import org.example.project.features.terminal.data.service.PairAttemptLimiter
import org.example.project.features.tse.domain.session.TseAdminSession
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The browser-station refusal matrix: pair, station-session exchange, employee login and refresh.
 * `ShopConfigurationManager` is a process-wide cache, so every test sets the roles explicitly.
 */
class BrowserStationEnforcementTest {
    @BeforeEach
    fun resetState() {
        PairAttemptLimiter.resetAllForTests()
        TseAdminSession.clear()
    }

    private suspend fun provision(
        client: HttpClient,
        adminToken: String,
        role: String,
    ): Pair<Long, String> {
        val created =
            client
                .post("/api/v1/terminals") {
                    bearerAuth(adminToken)
                    setBody(TextContent("""{"name":"Browser $role","role":"$role"}""", ContentType.Application.Json))
                }.let { testJson.decodeFromString<TerminalDto.TerminalResponseDto>(it.bodyAsText()) }
        val code =
            client
                .post("/api/v1/terminals/${created.id}/pairing-code") { bearerAuth(adminToken) }
                .let { testJson.decodeFromString<TerminalDto.PairingCodeResponseDto>(it.bodyAsText()) }
                .code
        return created.id to code
    }

    private suspend fun pair(
        client: HttpClient,
        code: String,
        deviceId: String,
        kind: String,
    ): HttpResponse =
        client.post("/api/v1/terminals/pair") {
            setBody(
                TextContent(
                    """{"code":"$code","deviceId":"$deviceId","clientKind":"$kind"}""",
                    ContentType.Application.Json,
                ),
            )
        }

    private suspend fun errorCode(response: HttpResponse): String? = testJson.decodeFromString(ProblemDetail.serializer(), response.bodyAsText()).code

    private suspend fun deviceToken(response: HttpResponse): String {
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return testJson.decodeFromString<TerminalDto.PairDeviceResponseDto>(response.bodyAsText()).deviceToken
    }

    private suspend fun login(
        client: HttpClient,
        deviceId: String,
    ): HttpResponse =
        client.post("/api/auth/login") {
            header("X-Device-ID", deviceId)
            setBody(TextContent("""{"identifier":"admin","secret":"${TestData.DEFAULT_PASSWORD}"}""", ContentType.Application.Json))
        }

    private fun setup() {
        TestData.createAdmin()
        TestData.createTerminalDeviceEmployee()
        TestData.setTseMode(TseMode.SIMULATION)
    }

    @Test
    fun `pair from BROWSER is refused 403 when the role is not enabled`() =
        testApp(setupData = { setup() }) { client ->
            TestData.setBrowserStationRoles(emptySet())
            val admin = TestAuthHelper.loginAsAdmin(client)
            val (_, code) = provision(client, admin, "CASHIER")

            val refused = pair(client, code, "browser-dev-1", "BROWSER")
            assertEquals(HttpStatusCode.Forbidden, refused.status)
            assertEquals("BROWSER_STATION_DISABLED", errorCode(refused))

            // The code was not consumed: once the role is enabled the same code pairs.
            TestData.setBrowserStationRoles(setOf(TerminalRole.CASHIER))
            deviceToken(pair(client, code, "browser-dev-1", "BROWSER"))
        }

    @Test
    fun `pair from BROWSER succeeds when the role is enabled`() =
        testApp(setupData = { setup() }) { client ->
            TestData.setBrowserStationRoles(setOf(TerminalRole.CASHIER))
            val admin = TestAuthHelper.loginAsAdmin(client)
            val (_, code) = provision(client, admin, "CASHIER")

            deviceToken(pair(client, code, "browser-dev-2", "BROWSER"))
        }

    @Test
    fun `NATIVE pairing is unaffected by the setting`() =
        testApp(setupData = { setup() }) { client ->
            TestData.setBrowserStationRoles(emptySet())
            val admin = TestAuthHelper.loginAsAdmin(client)
            val (_, code) = provision(client, admin, "CASHIER")

            deviceToken(pair(client, code, "android-1", "NATIVE"))
        }

    @Test
    fun `a pos-preview device is BROWSER even when it claims NATIVE`() =
        testApp(setupData = { setup() }) { client ->
            TestData.setBrowserStationRoles(emptySet())
            val admin = TestAuthHelper.loginAsAdmin(client)
            val (_, code) = provision(client, admin, "CASHIER")

            val refused = pair(client, code, "pos-preview-x", "NATIVE")
            assertEquals(HttpStatusCode.Forbidden, refused.status)
            assertEquals("BROWSER_STATION_DISABLED", errorCode(refused))
        }

    @Test
    fun `a refused pairing does not count against the rate limiter`() =
        testApp(setupData = { setup() }) { client ->
            TestData.setBrowserStationRoles(emptySet())
            val admin = TestAuthHelper.loginAsAdmin(client)
            val (_, code) = provision(client, admin, "CASHIER")

            repeat(6) {
                assertEquals(HttpStatusCode.Forbidden, pair(client, code, "browser-dev-3", "BROWSER").status)
            }

            TestData.setBrowserStationRoles(setOf(TerminalRole.CASHIER))
            deviceToken(pair(client, code, "browser-dev-3", "BROWSER"))
        }

    @Test
    fun `ORDER_STATION session exchange is refused when browser stations of that role are off`() =
        testApp(setupData = { setup() }) { client ->
            TestData.setBrowserStationRoles(setOf(TerminalRole.ORDER_STATION))
            val admin = TestAuthHelper.loginAsAdmin(client)
            val (_, code) = provision(client, admin, "ORDER_STATION")
            val token = deviceToken(pair(client, code, "browser-station-1", "BROWSER"))

            val open =
                client.post("/api/v1/terminals/session") { header("X-Device-Token", token) }
            assertEquals(HttpStatusCode.OK, open.status, open.bodyAsText())

            TestData.setBrowserStationRoles(emptySet())
            val refused =
                client.post("/api/v1/terminals/session") { header("X-Device-Token", token) }
            assertEquals(HttpStatusCode.Forbidden, refused.status)
            assertEquals("BROWSER_STATION_DISABLED", errorCode(refused))
        }

    @Test
    fun `employee login from a disabled browser cashier is refused`() =
        testApp(setupData = { setup() }) { client ->
            TestData.setBrowserStationRoles(setOf(TerminalRole.CASHIER))
            val admin = TestAuthHelper.loginAsAdmin(client)
            val (_, code) = provision(client, admin, "CASHIER")
            deviceToken(pair(client, code, "browser-dev-4", "BROWSER"))

            assertEquals(HttpStatusCode.OK, login(client, "browser-dev-4").status)

            TestData.setBrowserStationRoles(emptySet())
            val refused = login(client, "browser-dev-4")
            assertEquals(HttpStatusCode.Forbidden, refused.status)
            assertEquals("BROWSER_STATION_DISABLED", errorCode(refused))
        }

    @Test
    fun `refresh from a disabled browser cashier is refused`() =
        testApp(setupData = { setup() }) { client ->
            TestData.setBrowserStationRoles(setOf(TerminalRole.CASHIER))
            val admin = TestAuthHelper.loginAsAdmin(client)
            val (_, code) = provision(client, admin, "CASHIER")
            deviceToken(pair(client, code, "browser-dev-5", "BROWSER"))

            val loggedIn = login(client, "browser-dev-5")
            assertEquals(HttpStatusCode.OK, loggedIn.status)
            val refreshToken = testJson.decodeFromString<LoginResponse>(loggedIn.bodyAsText()).token.refreshToken

            TestData.setBrowserStationRoles(emptySet())
            val refused =
                client.post("/api/auth/refresh") {
                    header("X-Device-ID", "browser-dev-5")
                    setBody(TextContent("""{"refreshToken":"$refreshToken"}""", ContentType.Application.Json))
                }
            assertEquals(HttpStatusCode.Forbidden, refused.status)
            assertEquals("BROWSER_STATION_DISABLED", errorCode(refused))
        }

    @Test
    fun `admin-web and unknown devices are unaffected`() =
        testApp(setupData = { setup() }) { client ->
            TestData.setBrowserStationRoles(emptySet())

            assertEquals(HttpStatusCode.OK, login(client, "admin-web-1").status)
            assertEquals(HttpStatusCode.OK, login(client, "unknown").status)
        }

    private suspend fun postOrderWithDeviceToken(
        client: HttpClient,
        token: String,
    ): HttpResponse =
        client.post("/api/orders") {
            header("X-Device-Token", token)
            setBody(TextContent("{}", ContentType.Application.Json))
        }

    @Test
    fun `device token alone is refused on a transactional route once the role is disabled`() =
        testApp(setupData = { setup() }) { client ->
            TestData.setBrowserStationRoles(setOf(TerminalRole.CASHIER))
            val admin = TestAuthHelper.loginAsAdmin(client)
            val (_, code) = provision(client, admin, "CASHIER")
            val token = deviceToken(pair(client, code, "browser-dev-6", "BROWSER"))

            // Enabled: the request gets past the ceiling (it then fails on the empty body, not on the policy).
            val before = postOrderWithDeviceToken(client, token)
            assertEquals(false, errorCode(before) == "BROWSER_STATION_DISABLED", before.bodyAsText())

            TestData.setBrowserStationRoles(emptySet())
            val refused = postOrderWithDeviceToken(client, token)
            assertEquals(HttpStatusCode.Forbidden, refused.status)
            assertEquals("BROWSER_STATION_DISABLED", errorCode(refused))

            // A non-transactional capability route on the same device token is refused too...
            val read = client.get("/api/v1/terminals/me") { header("X-Device-Token", token) }
            assertEquals("BROWSER_STATION_DISABLED", errorCode(read))
            // ...except the one the station uses to learn its own state.
            val me = client.get("/api/v1/terminals/me-by-device") { header("X-Device-Token", token) }
            assertEquals(false, errorCode(me) == "BROWSER_STATION_DISABLED", me.bodyAsText())

            // Re-enabling restores it.
            TestData.setBrowserStationRoles(setOf(TerminalRole.CASHIER))
            val restored = postOrderWithDeviceToken(client, token)
            assertEquals(false, errorCode(restored) == "BROWSER_STATION_DISABLED", restored.bodyAsText())
        }

    @Test
    fun `a station JWT minted before disabling is refused on a transactional route afterwards`() =
        testApp(setupData = { setup() }) { client ->
            TestData.setBrowserStationRoles(setOf(TerminalRole.ORDER_STATION))
            val admin = TestAuthHelper.loginAsAdmin(client)
            val (_, code) = provision(client, admin, "ORDER_STATION")
            val token = deviceToken(pair(client, code, "browser-station-2", "BROWSER"))
            val session = client.post("/api/v1/terminals/session") { header("X-Device-Token", token) }
            assertEquals(HttpStatusCode.OK, session.status, session.bodyAsText())
            val jwt = testJson.decodeFromString<TerminalDto.StationSessionResponseDto>(session.bodyAsText()).accessToken

            TestData.setBrowserStationRoles(emptySet())
            val refused =
                client.post("/api/orders") {
                    bearerAuth(jwt)
                    setBody(TextContent("{}", ContentType.Application.Json))
                }
            assertEquals(HttpStatusCode.Forbidden, refused.status)
            assertEquals("BROWSER_STATION_DISABLED", errorCode(refused))
        }

    @Test
    fun `a NATIVE device token is unaffected on a transactional route`() =
        testApp(setupData = { setup() }) { client ->
            TestData.setBrowserStationRoles(emptySet())
            val admin = TestAuthHelper.loginAsAdmin(client)
            val (_, code) = provision(client, admin, "CASHIER")
            val token = deviceToken(pair(client, code, "android-2", "NATIVE"))

            val response = postOrderWithDeviceToken(client, token)
            assertEquals(false, errorCode(response) == "BROWSER_STATION_DISABLED", response.bodyAsText())
        }

    private suspend fun stationJwt(
        client: HttpClient,
        adminToken: String,
        deviceId: String,
    ): Pair<String, String> {
        val (_, code) = provision(client, adminToken, "ORDER_STATION")
        val token = deviceToken(pair(client, code, deviceId, "BROWSER"))
        val session = client.post("/api/v1/terminals/session") { header("X-Device-Token", token) }
        assertEquals(HttpStatusCode.OK, session.status, session.bodyAsText())
        return token to testJson.decodeFromString<TerminalDto.StationSessionResponseDto>(session.bodyAsText()).accessToken
    }

    @Test
    fun `a station JWT minted before disabling is refused on a plain read route and restored on re-enable`() =
        testApp(setupData = { setup() }) { client ->
            TestData.setBrowserStationRoles(setOf(TerminalRole.ORDER_STATION))
            val admin = TestAuthHelper.loginAsAdmin(client)
            val (_, jwt) = stationJwt(client, admin, "browser-station-3")

            assertEquals(HttpStatusCode.OK, client.get("/api/auth/me") { bearerAuth(jwt) }.status)

            TestData.setBrowserStationRoles(emptySet())
            val refused = client.get("/api/auth/me") { bearerAuth(jwt) }
            assertEquals(HttpStatusCode.Forbidden, refused.status)
            assertEquals("BROWSER_STATION_DISABLED", errorCode(refused))

            TestData.setBrowserStationRoles(setOf(TerminalRole.ORDER_STATION))
            assertEquals(HttpStatusCode.OK, client.get("/api/auth/me") { bearerAuth(jwt) }.status)
        }

    @Test
    fun `a browser cashier person JWT minted before disabling is refused on a plain read route`() =
        testApp(setupData = { setup() }) { client ->
            TestData.setBrowserStationRoles(setOf(TerminalRole.CASHIER))
            val admin = TestAuthHelper.loginAsAdmin(client)
            val (_, code) = provision(client, admin, "CASHIER")
            deviceToken(pair(client, code, "browser-dev-7", "BROWSER"))
            val jwt = testJson.decodeFromString<LoginResponse>(login(client, "browser-dev-7").bodyAsText()).token.accessToken

            assertEquals(HttpStatusCode.OK, client.get("/api/auth/me") { bearerAuth(jwt) }.status)

            TestData.setBrowserStationRoles(emptySet())
            val refused = client.get("/api/auth/me") { bearerAuth(jwt) }
            assertEquals(HttpStatusCode.Forbidden, refused.status)
            assertEquals("BROWSER_STATION_DISABLED", errorCode(refused))
        }

    @Test
    fun `NATIVE admin-web and unknown device JWTs stay usable on a plain read route`() =
        testApp(setupData = { setup() }) { client ->
            TestData.setBrowserStationRoles(emptySet())
            val admin = TestAuthHelper.loginAsAdmin(client)
            val (_, code) = provision(client, admin, "CASHIER")
            deviceToken(pair(client, code, "android-3", "NATIVE"))

            for (device in listOf("android-3", "admin-web-9", "unknown")) {
                val jwt = testJson.decodeFromString<LoginResponse>(login(client, device).bodyAsText()).token.accessToken
                val response = client.get("/api/auth/me") { bearerAuth(jwt) }
                assertEquals(HttpStatusCode.OK, response.status, "$device: ${response.bodyAsText()}")
            }
        }

    @Test
    fun `a disabled station can still reach logout, the notification stream gate and me-by-device`() =
        testApp(setupData = { setup() }) { client ->
            TestData.setBrowserStationRoles(setOf(TerminalRole.CASHIER))
            val admin = TestAuthHelper.loginAsAdmin(client)
            val (_, code) = provision(client, admin, "CASHIER")
            val deviceToken = deviceToken(pair(client, code, "browser-dev-8", "BROWSER"))
            val jwt = testJson.decodeFromString<LoginResponse>(login(client, "browser-dev-8").bodyAsText()).token.accessToken

            TestData.setBrowserStationRoles(emptySet())

            val me = client.get("/api/v1/terminals/me-by-device") { header("X-Device-Token", deviceToken) }
            assertEquals(false, errorCode(me) == "BROWSER_STATION_DISABLED", me.bodyAsText())

            val logout = client.post("/api/auth/logout") { bearerAuth(jwt) }
            assertEquals(false, runCatching { errorCode(logout) }.getOrNull() == "BROWSER_STATION_DISABLED", logout.bodyAsText())
        }
}
