package org.example.project.features.terminal

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.channels.Channel
import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.terminal.TerminalRole
import org.example.project.core.websocket.NotificationHub
import org.example.project.core.websocket.OutgoingFrame
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.terminal.data.service.PairAttemptLimiter
import org.example.project.features.tse.domain.session.TseAdminSession
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Switching a role off in the `browser_stations` setting drops the live browser stations of that
 * role (and only those) with a `terminal_session_terminated(BROWSER_DISABLED)` push.
 */
class BrowserStationDisabledNotificationTest {
    @BeforeEach
    fun resetState() {
        NotificationHub.resetForTest()
        PairAttemptLimiter.resetAllForTests()
        TseAdminSession.clear()
    }

    private fun setup() {
        TestData.createAdmin()
        TestData.createTerminalDeviceEmployee()
        TestData.setTseMode(TseMode.SIMULATION)
    }

    private fun shopId(): Long =
        transaction {
            ShopInfo
                .all()
                .limit(1)
                .first()
                .id.value
        }

    /** Provisions a [role] terminal and pairs [deviceId] to it as [kind]; returns its database id. */
    private suspend fun HttpClient.provisionAndPair(
        adminToken: String,
        role: String,
        deviceId: String,
        kind: String,
    ): Long {
        val created =
            post("/api/v1/terminals") {
                bearerAuth(adminToken)
                setBody(TextContent("""{"name":"T $deviceId","role":"$role"}""", ContentType.Application.Json))
            }.let { testJson.decodeFromString<TerminalDto.TerminalResponseDto>(it.bodyAsText()) }
        val code =
            post("/api/v1/terminals/${created.id}/pairing-code") { bearerAuth(adminToken) }
                .let { testJson.decodeFromString<TerminalDto.PairingCodeResponseDto>(it.bodyAsText()) }
                .code
        val paired =
            post("/api/v1/terminals/pair") {
                setBody(
                    TextContent(
                        """{"code":"$code","deviceId":"$deviceId","clientKind":"$kind"}""",
                        ContentType.Application.Json,
                    ),
                )
            }
        assertEquals(HttpStatusCode.OK, paired.status, paired.bodyAsText())
        return created.id
    }

    private suspend fun HttpClient.putRoles(
        token: String,
        body: String,
    ): HttpResponse =
        put("/api/v1/config/${shopId()}/browser-stations") {
            bearerAuth(token)
            setBody(TextContent(body, ContentType.Application.Json))
        }

    private suspend fun listen(deviceId: String): Pair<Channel<OutgoingFrame>, String> {
        val outgoing = Channel<OutgoingFrame>(Channel.UNLIMITED)
        val connId =
            NotificationHub.addSseConnection(
                connectionId = "conn-$deviceId",
                employeeId = "admin-1",
                deviceId = deviceId,
                outgoing = outgoing,
            )
        return outgoing to connId
    }

    /** Drains rather than waits: the push is awaited before the route responds, so empty means none. */
    private fun Channel<OutgoingFrame>.pushes(): List<NotificationDto.TerminalSessionTerminatedDto> =
        generateSequence { tryReceive().getOrNull() }
            .mapNotNull { (it as? OutgoingFrame.Event)?.event?.message as? NotificationDto.TerminalSessionTerminatedDto }
            .toList()

    @Test
    fun `disabling CASHIER pushes BROWSER_DISABLED to a bound browser cashier`() =
        testApp(setupData = { setup() }) { client ->
            TestData.setBrowserStationRoles(setOf(TerminalRole.CASHIER))
            val admin = TestAuthHelper.loginAsAdmin(client)
            client.provisionAndPair(admin, "CASHIER", "browser-cashier", "BROWSER")
            val (outgoing, connId) = listen("browser-cashier")
            try {
                val response = client.putRoles(admin, """{"roles":[]}""")
                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())

                val pushed = outgoing.pushes()
                assertEquals(1, pushed.size, "expected exactly one terminal_session_terminated push")
                assertEquals("BROWSER_DISABLED", pushed.single().reason)
                assertFalse(pushed.single().requiresRepairing, "the pairing survives; re-enabling must not need a new code")
            } finally {
                NotificationHub.removeConnection(connId)
            }
        }

    @Test
    fun `a NATIVE terminal is not notified`() =
        testApp(setupData = { setup() }) { client ->
            TestData.setBrowserStationRoles(setOf(TerminalRole.CASHIER))
            val admin = TestAuthHelper.loginAsAdmin(client)
            client.provisionAndPair(admin, "CASHIER", "android-cashier", "NATIVE")
            val (outgoing, connId) = listen("android-cashier")
            try {
                client.putRoles(admin, """{"roles":[]}""")

                assertEquals(emptyList(), outgoing.pushes().map { it.reason })
            } finally {
                NotificationHub.removeConnection(connId)
            }
        }

    @Test
    fun `a browser ORDER_STATION is not notified when only CASHIER is disabled`() =
        testApp(setupData = { setup() }) { client ->
            TestData.setBrowserStationRoles(setOf(TerminalRole.CASHIER, TerminalRole.ORDER_STATION))
            val admin = TestAuthHelper.loginAsAdmin(client)
            client.provisionAndPair(admin, "CASHIER", "browser-cashier-2", "BROWSER")
            client.provisionAndPair(admin, "ORDER_STATION", "browser-station", "BROWSER")
            val (cashierOut, cashierConn) = listen("browser-cashier-2")
            val (stationOut, stationConn) = listen("browser-station")
            try {
                val response = client.putRoles(admin, """{"roles":["ORDER_STATION"]}""")
                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())

                assertEquals(listOf("BROWSER_DISABLED"), cashierOut.pushes().map { it.reason })
                assertEquals(emptyList(), stationOut.pushes().map { it.reason })
            } finally {
                NotificationHub.removeConnection(cashierConn)
                NotificationHub.removeConnection(stationConn)
            }
        }

    @Test
    fun `re-enabling pushes nothing and the terminal keeps its binding and TSE flags`() =
        testApp(setupData = { setup() }) { client ->
            TestData.setBrowserStationRoles(setOf(TerminalRole.CASHIER))
            val admin = TestAuthHelper.loginAsAdmin(client)
            val id = client.provisionAndPair(admin, "CASHIER", "browser-cashier-3", "BROWSER")
            val before = client.terminal(admin, id)
            client.putRoles(admin, """{"roles":[]}""")

            val (outgoing, connId) = listen("browser-cashier-3")
            try {
                val response = client.putRoles(admin, """{"roles":["CASHIER"]}""")
                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())

                assertEquals(emptyList(), outgoing.pushes().map { it.reason })
                val after = client.terminal(admin, id)
                assertEquals(true, after.bound)
                assertEquals(before.bound, after.bound)
                assertEquals(before.tseClientRegistered, after.tseClientRegistered)
            } finally {
                NotificationHub.removeConnection(connId)
            }
        }

    private suspend fun HttpClient.terminal(
        token: String,
        id: Long,
    ): TerminalDto.TerminalSummaryDto {
        val response = get("/api/v1/terminals") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return testJson.decodeFromString<List<TerminalDto.TerminalSummaryDto>>(response.bodyAsText()).single { it.id == id }
    }
}
