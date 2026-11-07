package org.example.project.routes

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.channels.Channel
import org.example.project.BuildConfig
import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.core.domain.enums.websocket.DataChangeOperation
import org.example.project.core.utils.DeviceIdentifiers
import org.example.project.core.websocket.NotificationHub
import org.example.project.core.websocket.OutgoingFrame
import org.example.project.features.terminal.data.service.PairAttemptLimiter
import org.example.project.features.terminal.domain.TerminalEmployee
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.tse.domain.session.TseAdminSession
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NotificationStreamRoutesTest {
    // Pair attempts are rate-limited per source IP and the TSE admin sitting is process-wide; both
    // outlive a single test class in this JVM (see TerminalRoutesTest).
    @BeforeEach
    fun resetPairingState() {
        PairAttemptLimiter.resetAllForTests()
        TseAdminSession.clear()
    }

    @Test
    fun `stream rejects unauthenticated requests`() =
        testApp { client ->
            val response = client.get("/api/notifications/stream")
            assertTrue(
                response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden,
                "expected 401/403 but was ${response.status}",
            )
        }

    @Test
    @Timeout(60)
    fun `an order station opening the stream records its app version on its terminal`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createTerminalDeviceEmployee()
                TestData.setTseMode(TseMode.SIMULATION)
            },
        ) { client ->
            // An ORDER_STATION has no login, so the version it sent at pairing was never refreshed.
            val admin = TestAuthHelper.loginAsAdmin(client)
            val created =
                client
                    .post("/api/v1/terminals") {
                        bearerAuth(admin)
                        setBody(TextContent("""{"name":"Station","role":"ORDER_STATION"}""", ContentType.Application.Json))
                    }.let { testJson.decodeFromString<TerminalDto.TerminalResponseDto>(it.bodyAsText()) }
            val code =
                client
                    .post("/api/v1/terminals/${created.id}/pairing-code") { bearerAuth(admin) }
                    .let { testJson.decodeFromString<TerminalDto.PairingCodeResponseDto>(it.bodyAsText()) }
                    .code
            val paired =
                client
                    .post("/api/v1/terminals/pair") {
                        setBody(
                            TextContent(
                                """{"code":"$code","deviceId":"station-sse","swVersion":"1.0.0"}""",
                                ContentType.Application.Json,
                            ),
                        )
                    }.let { testJson.decodeFromString<TerminalDto.PairDeviceResponseDto>(it.bodyAsText()) }
            val station =
                client
                    .post("/api/v1/terminals/session") { header(DeviceIdentifiers.DEVICE_TOKEN_HEADER, paired.deviceToken) }
                    .let { testJson.decodeFromString<TerminalDto.StationSessionResponseDto>(it.bodyAsText()) }

            client
                .prepareGet("/api/notifications/stream") {
                    bearerAuth(station.accessToken)
                    header(DeviceIdentifiers.APP_VERSION_HEADER, "1.1.0")
                }.execute { response -> assertEquals(HttpStatusCode.OK, response.status) }

            assertEquals("1.1.0", transaction { Terminal.findById(created.id)!!.terminalSwVersion })
        }

    /**
     * Creates an ORDER_STATION terminal, pairs [deviceId] to it and exchanges the device token for a
     * station session — the three calls a login-less station makes. Returns the station JWT.
     */
    private suspend fun openStationSession(
        client: HttpClient,
        adminToken: String,
        name: String,
        deviceId: String,
    ): String {
        val created =
            client
                .post("/api/v1/terminals") {
                    bearerAuth(adminToken)
                    setBody(TextContent("""{"name":"$name","role":"ORDER_STATION"}""", ContentType.Application.Json))
                }.let { testJson.decodeFromString<TerminalDto.TerminalResponseDto>(it.bodyAsText()) }
        val code =
            client
                .post("/api/v1/terminals/${created.id}/pairing-code") { bearerAuth(adminToken) }
                .let { testJson.decodeFromString<TerminalDto.PairingCodeResponseDto>(it.bodyAsText()) }
                .code
        val paired =
            client
                .post("/api/v1/terminals/pair") {
                    setBody(
                        TextContent(
                            """{"code":"$code","deviceId":"$deviceId","swVersion":"1.0.0"}""",
                            ContentType.Application.Json,
                        ),
                    )
                }.let { testJson.decodeFromString<TerminalDto.PairDeviceResponseDto>(it.bodyAsText()) }
        return client
            .post("/api/v1/terminals/session") { header(DeviceIdentifiers.DEVICE_TOKEN_HEADER, paired.deviceToken) }
            .let { testJson.decodeFromString<TerminalDto.StationSessionResponseDto>(it.bodyAsText()) }
            .accessToken
    }

    /**
     * Registers a directly-connected sibling station stream for the shared terminal employee, runs
     * [block] with it, and removes the registration afterwards regardless of outcome.
     *
     * The test-host client cannot keep two SSE responses open at once, so a sibling station is stood
     * in for by a directly-registered connection rather than a second real stream through the route.
     */
    private suspend fun withSiblingStation(
        deviceId: String,
        block: suspend (sibling: Channel<OutgoingFrame>) -> Unit,
    ) {
        val terminalEmployeeId =
            transaction {
                TerminalEmployee
                    .requireRow()
                    .id
                    .value
                    .toString()
            }
        val sibling = Channel<OutgoingFrame>(Channel.UNLIMITED)
        val siblingId =
            NotificationHub.addSseConnection(
                connectionId = "sibling-$deviceId",
                employeeId = terminalEmployeeId,
                deviceId = deviceId,
                outgoing = sibling,
                employeeRole = EmployeeRole.TERMINAL_DEVICE,
                isStation = true,
            )
        try {
            block(sibling)
        } finally {
            NotificationHub.removeConnection(siblingId)
        }
    }

    @Test
    @Timeout(60)
    fun `a station opening its stream does not evict a sibling station sharing the terminal employee`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createTerminalDeviceEmployee()
                TestData.setTseMode(TseMode.SIMULATION)
            },
        ) { client ->
            val admin = TestAuthHelper.loginAsAdmin(client)
            val stationA = openStationSession(client, admin, name = "Station A", deviceId = "station-a")
            withSiblingStation(deviceId = "station-b") { sibling ->
                client.prepareGet("/api/notifications/stream") { bearerAuth(stationA) }.execute { response ->
                    assertEquals(HttpStatusCode.OK, response.status)
                }
                assertFalse(
                    sibling.isClosedForSend,
                    "station A's stream evicted sibling station B's, sharing the terminal employee",
                )
            }
        }

    @Test
    fun `a station cannot log out the shared terminal employee and close other stations' streams`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createTerminalDeviceEmployee()
                TestData.setTseMode(TseMode.SIMULATION)
            },
        ) { client ->
            val admin = TestAuthHelper.loginAsAdmin(client)
            val station = openStationSession(client, admin, name = "Station A", deviceId = "station-a")
            withSiblingStation(deviceId = "station-b") { otherStation ->
                val response = client.post("/api/auth/logout") { bearerAuth(station) }
                // ORDER_STATION_MASK carries no SELF_WRITE, so the refusal happens before the handler
                // could reach revokeAllForEmployee(terminal-device) — the premise of keeping
                // disconnectEmployee employee-wide.
                assertEquals(HttpStatusCode.Forbidden, response.status)
                assertFalse(otherStation.isClosedForSend, "another station's stream must survive")
            }
        }

    /** Reads the first `id:`/`data:` pair off a freshly opened stream. */
    private suspend fun HttpClient.firstFrame(
        token: String,
        lastEventId: Long? = null,
    ): Pair<Long, String> {
        var frame: Pair<Long, String>? = null
        prepareGet("/api/notifications/stream") {
            bearerAuth(token)
            lastEventId?.let { header("Last-Event-ID", it.toString()) }
        }.execute { response ->
            assertEquals(HttpStatusCode.OK, response.status)
            val channel = response.bodyAsChannel()
            val id = channel.readUTF8Line()!!.removePrefix("id: ").toLong()
            val data = channel.readUTF8Line()!!.removePrefix("data: ")
            frame = id to data
        }
        return frame!!
    }

    @Test
    @Timeout(60)
    fun `the stream opens with a server_hello carrying the server version and it is not replayed on reconnect`() =
        testApp(
            setupData = {
                TestData.createAdmin()
            },
        ) { client ->
            val admin = TestAuthHelper.loginAsAdmin(client)

            val (helloId, hello) = client.firstFrame(admin)
            val decoded = testJson.decodeFromString<NotificationDto.NotificationMessage>(hello)
            val serverHello = assertIs<NotificationDto.ServerHelloDto>(decoded)
            assertEquals(BuildConfig.APP_VERSION, serverHello.serverVersion)

            // Reconnecting with the id the client saw: replay must not re-announce the old hello, so
            // the first frame is a NEW hello (higher id), not the old one.
            val (secondId, second) = client.firstFrame(admin, lastEventId = helloId)
            assertTrue(secondId > helloId, "the hello is announced live per connection, not replayed")
            assertIs<NotificationDto.ServerHelloDto>(testJson.decodeFromString<NotificationDto.NotificationMessage>(second))

            val replayed = NotificationHub.replayFor("any-employee", 0L).toList()
            assertFalse(replayed.any { it.message is NotificationDto.ServerHelloDto }, "hello must never enter the replay log")
        }

    /**
     * The hello must precede the replay. Before 2026-10-08 it was enqueued on the connection's queue,
     * which the route drains only AFTER writing the replay — so any buffered event, which is every
     * stream opened after the server's first data change (a fresh stream replays from id 0), went
     * out before the hello. The test above passed only when the process-wide event log happened to be
     * empty, which is why it failed in the full suite and not alone. Seeded here, so it cannot depend
     * on what another test left behind.
     */
    @Test
    @Timeout(60)
    fun `the server_hello precedes replayed events on a fresh stream and on a reconnect`() =
        testApp(
            setupData = {
                TestData.createAdmin()
            },
        ) { client ->
            val admin = TestAuthHelper.loginAsAdmin(client)
            NotificationHub.broadcastToAll(productChanged("before-first-connect"))

            val (helloId, first) = client.firstFrame(admin)
            assertIs<NotificationDto.ServerHelloDto>(testJson.decodeFromString<NotificationDto.NotificationMessage>(first))

            // A change missed while disconnected is replayed on reconnect — after the hello, not before.
            NotificationHub.broadcastToAll(productChanged("while-disconnected"))
            assertTrue(NotificationHub.replayFor("any-employee", helloId).isNotEmpty(), "precondition: a missed event to replay")
            val (_, second) = client.firstFrame(admin, lastEventId = helloId)
            assertIs<NotificationDto.ServerHelloDto>(testJson.decodeFromString<NotificationDto.NotificationMessage>(second))
        }

    private fun productChanged(entityId: String) =
        NotificationDto.DataChangeNotificationDto(
            timestamp = "2026-10-08T00:00:00Z",
            entityType = ChangeEntityType.PRODUCT,
            operation = DataChangeOperation.UPDATED,
            entityId = entityId,
        )
}
