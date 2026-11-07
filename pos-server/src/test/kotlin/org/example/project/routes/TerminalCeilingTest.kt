package org.example.project.routes

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * A4 Task 3: the terminal-capability ceiling in [org.example.project.infrastructure.plugins.RouteAuthorizationPlugin]
 * gates the exact transactional routes named in [org.example.project.infrastructure.security.TransactionalRoutes].
 * `POST /api/orders` is used as the representative transactional route throughout.
 */
@DisplayName("Terminal-Capability Ceiling (A4 Task 3)")
class TerminalCeilingTest {
    /** Seeds a terminal bound to [deviceId] directly (bypassing the pairing flow). */
    private fun seedTerminal(
        deviceId: String,
        clientId: String,
        fiscal: Boolean,
        tseClientRegistered: Boolean,
        isActive: Boolean = true,
    ) {
        transaction {
            Terminal.new {
                this.deviceId = deviceId
                this.clientId = clientId
                this.role = "ORDER_STATION"
                this.fiscal = fiscal
                this.tseClientRegistered = tseClientRegistered
                this.isActive = isActive
                this.registeredAt = currentTimeStamp()
                this.lastSeenAt = currentTimeStamp()
            }
        }
    }

    @Test
    fun `non-fiscal terminal is blocked with 403`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                seedTerminal(deviceId = "X", clientId = "TER-CEIL-NONFISCAL", fiscal = false, tseClientRegistered = false)
            },
        ) { client ->
            val token = TestAuthHelper.loginAs(client, "admin", deviceId = "X")
            val res =
                client.post("/api/orders") {
                    bearerAuth(token)
                    setBody(TextContent("{}", ContentType.Application.Json))
                }
            assertEquals(HttpStatusCode.Forbidden, res.status)
        }

    @Test
    fun `fiscal but unregistered terminal in NORMAL TSE mode is refused with 424`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.setTseMode(TseMode.NORMAL)
                seedTerminal(deviceId = "X", clientId = "TER-CEIL-UNREG", fiscal = true, tseClientRegistered = false)
            },
        ) { client ->
            val token = TestAuthHelper.loginAs(client, "admin", deviceId = "X")
            val res =
                client.post("/api/orders") {
                    bearerAuth(token)
                    setBody(TextContent("{}", ContentType.Application.Json))
                }
            assertEquals(HttpStatusCode.FailedDependency, res.status)
        }

    @Test
    fun `fiscal and registered terminal is not blocked by the ceiling`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.setTseMode(TseMode.NORMAL)
                seedTerminal(deviceId = "X", clientId = "TER-CEIL-OK", fiscal = true, tseClientRegistered = true)
            },
        ) { client ->
            val token = TestAuthHelper.loginAs(client, "admin", deviceId = "X")
            val res =
                client.post("/api/orders") {
                    bearerAuth(token)
                    setBody(TextContent("{}", ContentType.Application.Json))
                }
            // The ceiling must not be the reason for failure — it may still fail later for unrelated
            // order-validation reasons (e.g. malformed body), which is fine.
            assertNotEquals(HttpStatusCode.Forbidden, res.status)
            assertNotEquals(HttpStatusCode.FailedDependency, res.status)
            assertNotEquals(HttpStatusCode.Conflict, res.status)
        }

    @Test
    fun `unknown device gets 409 needs-pairing`() =
        testApp(
            setupData = { TestData.createAdmin() },
        ) { client ->
            val token = TestAuthHelper.loginAs(client, "admin", deviceId = "totally-unknown-device")
            val res =
                client.post("/api/orders") {
                    bearerAuth(token)
                    setBody(TextContent("{}", ContentType.Application.Json))
                }
            assertEquals(HttpStatusCode.Conflict, res.status)
        }

    // Spoof test: the JWT's TRUSTED deviceId claim resolves to a non-fiscal terminal, but the
    // request also carries a raw X-Device-ID naming a different, fiscal, registered terminal.
    // The trusted JWT identity must win — the ceiling must still block as non-fiscal (403), never
    // upgrading via the spoofable header.
    @Test
    fun `spoofed X-Device-ID naming a fiscal terminal does not override the trusted JWT identity`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                seedTerminal(deviceId = "X", clientId = "TER-CEIL-NONFISCAL-2", fiscal = false, tseClientRegistered = false)
                seedTerminal(deviceId = "SPOOF-FISCAL", clientId = "TER-CEIL-SPOOF-TARGET", fiscal = true, tseClientRegistered = true)
            },
        ) { client ->
            val token = TestAuthHelper.loginAs(client, "admin", deviceId = "X")
            val res =
                client.post("/api/orders") {
                    bearerAuth(token)
                    header("X-Device-ID", "SPOOF-FISCAL")
                    setBody(TextContent("{}", ContentType.Application.Json))
                }
            assertEquals(HttpStatusCode.Forbidden, res.status)
        }

    // Regression guard: if the ceiling were ever accidentally applied to non-transactional routes,
    // this non-fiscal-bound device would incorrectly get 403 on a plain authenticated GET.
    @Test
    fun `non-transactional route is unaffected by the ceiling`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                seedTerminal(deviceId = "X", clientId = "TER-CEIL-NONTXN", fiscal = false, tseClientRegistered = false)
            },
        ) { client ->
            val token = TestAuthHelper.loginAs(client, "admin", deviceId = "X")
            val res =
                client.get("/api/v1/terminals") {
                    bearerAuth(token)
                }
            assertEquals(HttpStatusCode.OK, res.status)
        }
}
