package org.example.project.routes

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.runBlocking
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.domain.enums.order.OrderStatus
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.terminal.TerminalRole
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.order.domain.models.Orders
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.data.service.PairAttemptLimiter
import org.example.project.features.terminal.data.service.TlsRootCa
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.tse.domain.session.TseAdminSession
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("Terminal Routes")
class TerminalRoutesTest {
    // Both are process-wide, in-memory singletons that outlive any single nested class:
    // PairAttemptLimiter tracks pair attempts per source IP, and the Ktor test engine reports the
    // same remoteHost for every request in this JVM, so failed attempts from one nested class can
    // rate-limit a legitimate /pair call in another. TseAdminSession similarly persists lock state
    // across nested classes. Reset both before every test (JUnit5 runs an outer @BeforeEach before
    // each nested test too) so the full class is order-independent.
    @BeforeEach
    fun resetPairingState() {
        PairAttemptLimiter.resetAllForTests()
        TseAdminSession.clear()
    }

    @Nested
    @DisplayName("GET /api/v1/terminals/root-ca")
    inner class RootCa {
        @org.junit.jupiter.api.AfterEach
        fun restore() {
            TlsRootCa.fileProvider = { null }
            TlsRootCa.resetForTests()
        }

        @Test
        fun `is public and answers 404 when the server has no local CA`() =
            testApp { client ->
                TlsRootCa.fileProvider = { null }
                TlsRootCa.resetForTests()
                assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/terminals/root-ca").status)
            }

        @Test
        fun `a new pairing code carries the Pruefcode of the root when one is configured`() =
            testApp(setupData = { TestData.createAdmin() }) { client ->
                val fixture = checkNotNull(javaClass.getResource("/pairing-pin/root.pem")).readText()
                val file =
                    java.io.File.createTempFile("root", ".crt").also {
                        it.writeText(fixture)
                        it.deleteOnExit()
                    }
                TlsRootCa.fileProvider = { file }
                TlsRootCa.resetForTests()
                val token = TestAuthHelper.loginAsAdmin(client)
                val id =
                    client
                        .post("/api/v1/terminals") {
                            bearerAuth(token)
                            setBody(TextContent("""{"name":"Bar","role":"CASHIER"}""", ContentType.Application.Json))
                        }.bodyAsText()
                        .let { Regex(""""id":\s*(\d+)""").find(it)!!.groupValues[1] }
                val body = client.post("/api/v1/terminals/$id/pairing-code") { bearerAuth(token) }.bodyAsText()
                val fp = Regex(""""rootFingerprint":\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
                assertEquals(TlsRootCa.current()!!.fingerprint, fp)
            }

        @Test
        fun `a pairing code has no fingerprint when the server has no local CA`() =
            testApp(setupData = { TestData.createAdmin() }) { client ->
                TlsRootCa.fileProvider = { null }
                TlsRootCa.resetForTests()
                val token = TestAuthHelper.loginAsAdmin(client)
                val id =
                    client
                        .post("/api/v1/terminals") {
                            bearerAuth(token)
                            setBody(TextContent("""{"name":"Bar","role":"CASHIER"}""", ContentType.Application.Json))
                        }.bodyAsText()
                        .let { Regex(""""id":\s*(\d+)""").find(it)!!.groupValues[1] }
                val body = client.post("/api/v1/terminals/$id/pairing-code") { bearerAuth(token) }.bodyAsText()
                assertTrue(Regex(""""rootFingerprint":\s*null""").containsMatchIn(body) || !body.contains("rootFingerprint"), body)
            }

        @Test
        fun `serves the root as PEM without authentication`() =
            testApp { client ->
                val fixture = checkNotNull(javaClass.getResource("/pairing-pin/root.pem")).readText()
                val file =
                    java.io.File.createTempFile("root", ".crt").also {
                        it.writeText(fixture)
                        it.deleteOnExit()
                    }
                TlsRootCa.fileProvider = { file }
                TlsRootCa.resetForTests()
                val response = client.get("/api/v1/terminals/root-ca")
                assertEquals(HttpStatusCode.OK, response.status)
                assertTrue(response.contentType().toString().startsWith("application/x-pem-file"))
                assertTrue(response.bodyAsText().startsWith("-----BEGIN CERTIFICATE-----"))
            }
    }

    @Nested
    @DisplayName("POST /api/v1/terminals/register")
    inner class RegisterTerminal {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.post("/api/v1/terminals/register") {
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 when X-Device-ID header missing`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.post("/api/v1/terminals/register") {
                        bearerAuth(token)
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        // Server JSON is compact (ContentNegotiation.kt: prettyPrint = false), so field
        // separators render as `"key":value`. The `\s*` in these regexes is kept on purpose so
        // they stay correct whichever way that flag is set.
        @Test
        fun `a paired device is recognized on register`() =
            testApp(setupData = { TestData.createAdmin() }) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val id =
                    client
                        .post("/api/v1/terminals") {
                            bearerAuth(token)
                            setBody(TextContent("""{"name":"Bar","role":"CASHIER"}""", ContentType.Application.Json))
                        }.bodyAsText()
                        .let { Regex(""""id":\s*(\d+)""").find(it)!!.groupValues[1] }
                val code =
                    client
                        .post("/api/v1/terminals/$id/pairing-code") {
                            bearerAuth(token)
                        }.bodyAsText()
                        .let { Regex(""""code":\s*"(\d{6})"""").find(it)!!.groupValues[1] }
                client.post("/api/v1/terminals/pair") {
                    setBody(TextContent("""{"code":"$code","deviceId":"dev-BOUND"}""", ContentType.Application.Json))
                }
                val res =
                    client.post("/api/v1/terminals/register") {
                        bearerAuth(token)
                        header("X-Device-ID", "dev-BOUND")
                        setBody(TextContent("""{"brand":"Android"}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.OK, res.status)
                assertTrue(res.bodyAsText().contains("TER-"))
            }

        @Test
        fun `returns 403 for wasm admin device id`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.post("/api/v1/terminals/register") {
                        bearerAuth(token)
                        header("X-Device-ID", "admin-web-abc123")
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Forbidden, response.status)
            }

        @Test
        fun `pair refuses a wasm admin device id`() =
            testApp(setupData = { TestData.createAdmin() }) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val id =
                    client
                        .post("/api/v1/terminals") {
                            bearerAuth(token)
                            setBody(TextContent("""{"name":"Bar","role":"CASHIER"}""", ContentType.Application.Json))
                        }.bodyAsText()
                        .let { Regex(""""id":\s*(\d+)""").find(it)!!.groupValues[1] }
                val code =
                    client
                        .post("/api/v1/terminals/$id/pairing-code") {
                            bearerAuth(token)
                        }.bodyAsText()
                        .let { Regex(""""code":\s*"(\d{6})"""").find(it)!!.groupValues[1] }

                // Without the guard on /pair this succeeds, binding a back-office browser to a
                // terminal and issuing it a device token — routing around the /register check.
                val paired =
                    client.post("/api/v1/terminals/pair") {
                        setBody(
                            TextContent(
                                """{"code":"$code","deviceId":"admin-web-abc123"}""",
                                ContentType.Application.Json,
                            ),
                        )
                    }
                assertEquals(HttpStatusCode.Forbidden, paired.status)

                // And the device really is unbound afterwards, not merely refused a token.
                val me =
                    client.get("/api/v1/terminals/me") {
                        bearerAuth(token)
                        header("X-Device-ID", "admin-web-abc123")
                    }
                assertEquals(HttpStatusCode.NotFound, me.status)
            }

        @Test
        fun `the browser preview device pairs and registers`() =
            testApp(setupData = { TestData.createAdmin() }) { client ->
                TestData.setBrowserStationRoles(setOf(TerminalRole.CASHIER))
                val token = TestAuthHelper.loginAsAdmin(client)
                val id =
                    client
                        .post("/api/v1/terminals") {
                            bearerAuth(token)
                            setBody(TextContent("""{"name":"Bar","role":"CASHIER"}""", ContentType.Application.Json))
                        }.bodyAsText()
                        .let { Regex(""""id":\s*(\d+)""").find(it)!!.groupValues[1] }
                val code =
                    client
                        .post("/api/v1/terminals/$id/pairing-code") {
                            bearerAuth(token)
                        }.bodyAsText()
                        .let { Regex(""""code":\s*"(\d{6})"""").find(it)!!.groupValues[1] }

                // pos-preview- is a distinct prefix from admin-web-, so the guard above must not
                // catch it: the preview IS the till and registers as a terminal.
                val paired =
                    client.post("/api/v1/terminals/pair") {
                        setBody(
                            TextContent(
                                """{"code":"$code","deviceId":"pos-preview-abc123"}""",
                                ContentType.Application.Json,
                            ),
                        )
                    }
                assertEquals(HttpStatusCode.OK, paired.status)

                val registered =
                    client.post("/api/v1/terminals/register") {
                        bearerAuth(token)
                        header("X-Device-ID", "pos-preview-abc123")
                        setBody(TextContent("""{"brand":"Web"}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.OK, registered.status)
            }
    }

    @Nested
    @DisplayName("GET /api/v1/terminals/me")
    inner class GetMyTerminal {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/v1/terminals/me")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 when X-Device-ID header missing`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/v1/terminals/me") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/v1/terminals/me-by-device")
    inner class GetMyTerminalByDevice {
        @Test
        fun `returns 401 without device token`() =
            testApp { client ->
                val response = client.get("/api/v1/terminals/me-by-device")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns terminal for a paired device`() =
            testApp(setupData = { TestData.createAdmin() }) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val id =
                    client
                        .post("/api/v1/terminals") {
                            bearerAuth(token)
                            setBody(TextContent("""{"name":"Bar","role":"CASHIER"}""", ContentType.Application.Json))
                        }.bodyAsText()
                        .let { Regex(""""id":\s*(\d+)""").find(it)!!.groupValues[1] }
                val code =
                    client
                        .post("/api/v1/terminals/$id/pairing-code") {
                            bearerAuth(token)
                        }.bodyAsText()
                        .let { Regex(""""code":\s*"(\d{6})"""").find(it)!!.groupValues[1] }
                val deviceToken =
                    client
                        .post("/api/v1/terminals/pair") {
                            setBody(TextContent("""{"code":"$code","deviceId":"dev-BOUND"}""", ContentType.Application.Json))
                        }.bodyAsText()
                        .let { Regex(""""deviceToken":\s*"([^"]+)""").find(it)!!.groupValues[1] }

                val response =
                    client.get("/api/v1/terminals/me-by-device") {
                        header("X-Device-Token", deviceToken)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                assertTrue(response.bodyAsText().contains("TER-"))
            }
    }

    @Nested
    @DisplayName("POST /api/v1/terminals/sync-tse")
    inner class SyncTse {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.post("/api/v1/terminals/sync-tse")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `disabled TSE surfaces 409 TSE_DISABLED, not a 400 validation error`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createShopInfo()
                    runBlocking {
                        ShopConfigurationManager.setTseConfig(
                            TseConfigDto.TseConfig(
                                timeAdminPin = "12345",
                                mode = TseMode.DISABLED,
                            ),
                        )
                    }
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val res =
                    client.post("/api/v1/terminals/sync-tse") {
                        bearerAuth(token)
                    }
                val body = res.bodyAsText()
                assertEquals(HttpStatusCode.Conflict, res.status, body)
                assertTrue(Regex(""""code":\s*"TSE_DISABLED"""").containsMatchIn(body), body)
            }
    }

    @Nested
    @DisplayName("GET /api/v1/terminals")
    inner class GetAllTerminals {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/v1/terminals")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("DELETE /api/v1/terminals/{id}")
    inner class DeactivateTerminal {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.delete("/api/v1/terminals/1")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 for invalid id format`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.delete("/api/v1/terminals/not-a-number") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }

    @Nested
    @DisplayName("POST /api/v1/terminals/{id}/tse-register")
    inner class TseRegisterTerminal {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.post("/api/v1/terminals/1/tse-register")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 for invalid id format`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.post("/api/v1/terminals/not-a-number/tse-register") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }

    @Nested
    @DisplayName("POST /{id}/tse-register")
    inner class TseRegister {
        // TseAdminSession is a JVM-wide singleton (never persisted, per Swissbit §2.5). Force it
        // locked before the test regardless of what ran earlier in this JVM, so registerClient's
        // getAdminPin() throws TSE_ADMIN_LOCKED naturally instead of relying on ambient state.
        @BeforeEach
        fun ensureAdminSessionLocked() = TseAdminSession.clear()

        @Test
        fun `locked admin session surfaces 423 Locked, not 500`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createShopInfo()
                    // TSE mode must be non-DISABLED so registerTerminalTse reaches registerClient
                    // instead of short-circuiting with 409 TSE_DISABLED.
                    runBlocking {
                        ShopConfigurationManager.setTseConfig(
                            TseConfigDto.TseConfig(
                                timeAdminPin = "12345",
                                mode = TseMode.SIMULATION,
                            ),
                        )
                    }
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val id =
                    client
                        .post("/api/v1/terminals") {
                            bearerAuth(token)
                            setBody(TextContent("""{"name":"Till","role":"CASHIER"}""", ContentType.Application.Json))
                        }.bodyAsText()
                        .let { Regex(""""id":\s*(\d+)""").find(it)!!.groupValues[1] }

                // Admin session left locked (see ensureAdminSessionLocked) — the real
                // LocalTseAdminProvider.registerClient() calls getAdminPin(), which throws
                // PosException(TSE_ADMIN_LOCKED, 423) before touching TSE hardware.
                val res =
                    client.post("/api/v1/terminals/$id/tse-register") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.Locked, res.status) // 423 propagated, not wrapped into 500
            }

        @Test
        fun `disabled TSE surfaces 409 TSE_DISABLED, not a 400 validation error`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createShopInfo()
                    // Set DISABLED explicitly: ShopConfigurationManager's cache is process-wide, so an
                    // earlier test in this JVM may have left SIMULATION behind.
                    runBlocking {
                        ShopConfigurationManager.setTseConfig(
                            TseConfigDto.TseConfig(
                                timeAdminPin = "12345",
                                mode = TseMode.DISABLED,
                            ),
                        )
                    }
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val id =
                    client
                        .post("/api/v1/terminals") {
                            bearerAuth(token)
                            setBody(TextContent("""{"name":"Till","role":"CASHIER"}""", ContentType.Application.Json))
                        }.bodyAsText()
                        .let { Regex(""""id":\s*(\d+)""").find(it)!!.groupValues[1] }

                val res =
                    client.post("/api/v1/terminals/$id/tse-register") {
                        bearerAuth(token)
                    }
                val body = res.bodyAsText()
                assertEquals(HttpStatusCode.Conflict, res.status, body)
                assertTrue(Regex(""""code":\s*"TSE_DISABLED"""").containsMatchIn(body), body)
            }
    }

    @Nested
    @DisplayName("POST /api/v1/terminals/{id}/tse-deregister")
    inner class TseDeregister {
        @BeforeEach
        fun ensureAdminSessionLocked() = TseAdminSession.clear()

        private fun simulationTse() =
            runBlocking {
                ShopConfigurationManager.setTseConfig(TseConfigDto.TseConfig(timeAdminPin = "12345", mode = TseMode.SIMULATION))
            }

        private suspend fun createTerminal(
            client: io.ktor.client.HttpClient,
            token: String,
        ): String =
            client
                .post("/api/v1/terminals") {
                    bearerAuth(token)
                    setBody(TextContent("""{"name":"Till","role":"CASHIER"}""", ContentType.Application.Json))
                }.bodyAsText()
                .let { Regex(""""id":\s*(\d+)""").find(it)!!.groupValues[1] }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.post("/api/v1/terminals/1/tse-deregister")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 for invalid id format`() =
            testApp(setupData = { TestData.createAdmin() }) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.post("/api/v1/terminals/not-a-number/tse-deregister") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `an active terminal is refused with 409 before the TSE is asked`() =
            testApp(setupData = {
                TestData.createAdmin()
                TestData.createShopInfo()
                simulationTse()
            }) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val id = createTerminal(client, token)

                val res = client.post("/api/v1/terminals/$id/tse-deregister") { bearerAuth(token) }

                val body = res.bodyAsText()
                assertEquals(HttpStatusCode.Conflict, res.status, body)
                assertTrue(body.contains("deactivate"), body)
            }

        @Test
        fun `a retired terminal with a locked Admin sitting surfaces 423, not 500`() =
            testApp(setupData = {
                TestData.createAdmin()
                TestData.createShopInfo()
                simulationTse()
            }) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val id = createTerminal(client, token)
                assertEquals(HttpStatusCode.OK, client.delete("/api/v1/terminals/$id") { bearerAuth(token) }.status)

                val res = client.post("/api/v1/terminals/$id/tse-deregister") { bearerAuth(token) }

                assertEquals(HttpStatusCode.Locked, res.status, res.bodyAsText())
            }
    }

    @Nested
    @DisplayName("POST /api/v1/terminals/{id}/reactivate")
    inner class ReactivateTerminal {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.post("/api/v1/terminals/1/reactivate")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 for invalid id format`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.post("/api/v1/terminals/not-a-number/reactivate") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }

    @Nested
    @DisplayName("POST /api/v1/terminals (create) + /register no-auto-create")
    inner class CreateAndRegister {
        @Test
        fun `creates an unbound terminal with a TER id`() =
            testApp(setupData = { TestData.createAdmin() }) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val res =
                    client.post("/api/v1/terminals") {
                        bearerAuth(token)
                        setBody(TextContent("""{"name":"Bar","role":"ORDER_STATION"}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Created, res.status)
                assertTrue(res.bodyAsText().contains("TER-"))
            }

        @Test
        fun `unknown device gets 409 needs-pairing, no terminal created`() =
            testApp(setupData = { TestData.createAdmin() }) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val res =
                    client.post("/api/v1/terminals/register") {
                        bearerAuth(token)
                        header("X-Device-ID", "unknown-dev")
                        setBody(TextContent("""{"brand":"Android"}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Conflict, res.status)
            }
    }

    @Nested
    @DisplayName("POST /api/v1/terminals/pair")
    inner class PairDevice {
        // The rate limiter (PairAttemptLimiter) is a process-wide in-memory singleton keyed by
        // source IP; the Ktor test engine reports the same remoteHost for every request in this
        // class, so state must be reset before each test to keep them independent.
        @BeforeEach
        fun resetLimiter() = PairAttemptLimiter.resetAllForTests()

        // Server JSON is compact (ContentNegotiation.kt: prettyPrint = false), so field
        // separators render as `"key":value`. The `\s*` in these regexes is kept on purpose so
        // they stay correct whichever way that flag is set.
        private suspend fun newTerminalId(
            client: io.ktor.client.HttpClient,
            token: String,
        ): String =
            client
                .post("/api/v1/terminals") {
                    bearerAuth(token)
                    setBody(TextContent("""{"name":"Bar","role":"CASHIER"}""", ContentType.Application.Json))
                }.bodyAsText()
                .let { Regex(""""id":\s*(\d+)""").find(it)!!.groupValues[1] }

        private suspend fun codeFor(
            client: io.ktor.client.HttpClient,
            token: String,
            id: String,
        ): String =
            client
                .post("/api/v1/terminals/$id/pairing-code") { bearerAuth(token) }
                .bodyAsText()
                .let { Regex(""""code":\s*"(\d{6})"""").find(it)!!.groupValues[1] }

        /**
         * A body-less POST whose length is not declared (`curl -X POST` sends no Content-Length, a
         * chunked client sends none either). It used to reach ContentNegotiation with no
         * Content-Type and come back 500 `CannotTransformContentToTypeException`.
         */
        private fun undeclaredEmptyBody(type: ContentType?): OutgoingContent =
            object : OutgoingContent.ReadChannelContent() {
                override val contentLength: Long? = null
                override val contentType: ContentType? = type

                override fun readFrom(): io.ktor.utils.io.ByteReadChannel = io.ktor.utils.io.ByteReadChannel.Empty
            }

        @Test
        fun `pairing-code with no body and no Content-Length issues a code like an empty object`() =
            testApp(setupData = { TestData.createAdmin() }) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val id = newTerminalId(client, token)

                val res =
                    client.post("/api/v1/terminals/$id/pairing-code") {
                        bearerAuth(token)
                        setBody(undeclaredEmptyBody(null))
                    }
                val body = res.bodyAsText()
                assertEquals(HttpStatusCode.Created, res.status, body)
                assertTrue(Regex(""""code":\s*"\d{6}"""").containsMatchIn(body), body)
            }

        @Test
        fun `pairing-code with an empty JSON-typed body of undeclared length issues a code`() =
            testApp(setupData = { TestData.createAdmin() }) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val id = newTerminalId(client, token)

                val res =
                    client.post("/api/v1/terminals/$id/pairing-code") {
                        bearerAuth(token)
                        setBody(undeclaredEmptyBody(ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Created, res.status, res.bodyAsText())
            }

        @Test
        fun `pairing-code with an empty object still issues a code`() =
            testApp(setupData = { TestData.createAdmin() }) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val id = newTerminalId(client, token)

                val res =
                    client.post("/api/v1/terminals/$id/pairing-code") {
                        bearerAuth(token)
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Created, res.status, res.bodyAsText())
            }

        @Test
        fun `pairing-code refuses a malformed body with 400`() =
            testApp(setupData = { TestData.createAdmin() }) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val id = newTerminalId(client, token)

                val res =
                    client.post("/api/v1/terminals/$id/pairing-code") {
                        bearerAuth(token)
                        setBody(TextContent("""{"registerTseClient":""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, res.status, res.bodyAsText())
            }

        /** The body is a single flag; a request far past that is refused before it is read in full. */
        @Test
        fun `pairing-code refuses an oversized body with 400`() =
            testApp(setupData = { TestData.createAdmin() }) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val id = newTerminalId(client, token)
                val padded = """{"registerTseClient":false,"pad":"${"x".repeat(64 * 1024)}"}"""

                val res =
                    client.post("/api/v1/terminals/$id/pairing-code") {
                        bearerAuth(token)
                        setBody(TextContent(padded, ContentType.Application.Json))
                    }
                val body = res.bodyAsText()
                assertEquals(HttpStatusCode.BadRequest, res.status, body)
                assertTrue(body.contains("INVALID_REQUEST"), body)
            }

        @Test
        fun `pairing-code accepts a body and refuses TSE registration with the TSE disabled`() =
            testApp(setupData = { TestData.createAdmin() }) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val id = newTerminalId(client, token)

                val optedOut =
                    client.post("/api/v1/terminals/$id/pairing-code") {
                        bearerAuth(token)
                        setBody(TextContent("""{"registerTseClient":false}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Created, optedOut.status, optedOut.bodyAsText())

                val optedIn =
                    client.post("/api/v1/terminals/$id/pairing-code") {
                        bearerAuth(token)
                        setBody(TextContent("""{"registerTseClient":true}""", ContentType.Application.Json))
                    }
                val body = optedIn.bodyAsText()
                assertEquals(HttpStatusCode.Conflict, optedIn.status, body)
                assertTrue(Regex(""""code":\s*"TSE_DISABLED"""").containsMatchIn(body), body)
            }

        @Test
        fun `tse-pairing-status reports a disabled TSE as unavailable`() =
            testApp(setupData = { TestData.createAdmin() }) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val res = client.get("/api/v1/terminals/tse-pairing-status") { bearerAuth(token) }
                val body = res.bodyAsText()
                assertEquals(HttpStatusCode.OK, res.status, body)
                assertTrue(Regex(""""available":\s*false""").containsMatchIn(body), body)
                assertTrue(Regex(""""mode":\s*"DISABLED"""").containsMatchIn(body), body)
            }

        @Test
        fun `pairs without a JWT and returns a device token`() =
            testApp(setupData = { TestData.createAdmin() }) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val id = newTerminalId(client, token)
                val code = codeFor(client, token, id)
                val res =
                    client.post("/api/v1/terminals/pair") {
                        setBody(TextContent("""{"code":"$code","deviceId":"dev-A","brand":"Android"}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.OK, res.status)
                assertTrue(res.bodyAsText().contains("deviceToken"))
            }

        @Test
        fun `rebinding reuses the same TER id`() =
            testApp(setupData = { TestData.createAdmin() }) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val id = newTerminalId(client, token)
                val b1 =
                    client
                        .post("/api/v1/terminals/pair") {
                            setBody(
                                TextContent(
                                    """{"code":"${codeFor(client, token, id)}","deviceId":"dev-OLD"}""",
                                    ContentType.Application.Json,
                                ),
                            )
                        }.bodyAsText()
                val ter = Regex(""""terminalId":\s*"(TER-\d+)"""").find(b1)!!.groupValues[1]
                val b2 =
                    client
                        .post("/api/v1/terminals/pair") {
                            setBody(
                                TextContent(
                                    """{"code":"${codeFor(client, token, id)}","deviceId":"dev-NEW"}""",
                                    ContentType.Application.Json,
                                ),
                            )
                        }.bodyAsText()
                assertTrue(Regex(""""terminalId":\s*"$ter"""").containsMatchIn(b2))
            }

        @Test
        fun `rejects invalid code with 400`() =
            testApp(setupData = { TestData.createAdmin() }) { client ->
                val res =
                    client.post("/api/v1/terminals/pair") {
                        setBody(TextContent("""{"code":"000000","deviceId":"dev-X"}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, res.status)
            }

        @Test
        fun `rejects a reused code with 400`() =
            testApp(setupData = { TestData.createAdmin() }) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val id = newTerminalId(client, token)
                val code = codeFor(client, token, id)
                client.post("/api/v1/terminals/pair") {
                    setBody(TextContent("""{"code":"$code","deviceId":"dev-1"}""", ContentType.Application.Json))
                }
                val second =
                    client.post("/api/v1/terminals/pair") {
                        setBody(TextContent("""{"code":"$code","deviceId":"dev-2"}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, second.status)
            }

        @Test
        fun `6th bad-code attempt from the same source is rate-limited with 429`() =
            testApp(setupData = { TestData.createAdmin() }) { client ->
                repeat(5) {
                    val res =
                        client.post("/api/v1/terminals/pair") {
                            setBody(TextContent("""{"code":"000000","deviceId":"dev-limit"}""", ContentType.Application.Json))
                        }
                    assertEquals(HttpStatusCode.BadRequest, res.status)
                }
                val sixth =
                    client.post("/api/v1/terminals/pair") {
                        setBody(TextContent("""{"code":"000000","deviceId":"dev-limit"}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.TooManyRequests, sixth.status)
            }

        // Behind Caddy every request arrives from loopback (the test engine's peer is "localhost"
        // too), so the limiter has to tell clients apart by the X-Forwarded-For value Caddy sets.
        @Test
        fun `behind the proxy the limit is per forwarded client, not per proxy`() =
            testApp(setupData = { TestData.createAdmin() }) { client ->
                suspend fun badAttemptFrom(ip: String) =
                    client.post("/api/v1/terminals/pair") {
                        header("X-Forwarded-For", ip)
                        setBody(TextContent("""{"code":"000000","deviceId":"dev-proxy"}""", ContentType.Application.Json))
                    }

                repeat(5) { assertEquals(HttpStatusCode.BadRequest, badAttemptFrom("192.168.1.23").status) }
                assertEquals(HttpStatusCode.TooManyRequests, badAttemptFrom("192.168.1.23").status)

                // A different client behind the same proxy still has its own budget.
                assertEquals(HttpStatusCode.BadRequest, badAttemptFrom("192.168.1.24").status)
            }

        // Fix 3: deactivateTerminal now consumes outstanding codes for that terminal, so the code
        // is no longer *unconsumed* by the time /pair sees it — it surfaces as an invalid code
        // (400), one layer before the isActive check below ever runs.
        @Test
        fun `a deactivated terminal's outstanding pairing code no longer pairs`() =
            testApp(setupData = { TestData.createAdmin() }) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val id = newTerminalId(client, token)
                val code = codeFor(client, token, id)

                val deactivate = client.delete("/api/v1/terminals/$id") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, deactivate.status)

                val res =
                    client.post("/api/v1/terminals/pair") {
                        setBody(TextContent("""{"code":"$code","deviceId":"dev-DEACT"}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, res.status)
            }

        // Fix 3 defense-in-depth: pairDevice must independently reject an inactive terminal even
        // when its code is still unconsumed (e.g. isActive flipped by a path other than
        // deactivateTerminal). Flips isActive directly at the DB layer to isolate that check.
        @Test
        fun `pairing rejects a still-live code once the terminal is inactive`() =
            testApp(setupData = { TestData.createAdmin() }) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val id = newTerminalId(client, token)
                val code = codeFor(client, token, id)

                transaction { Terminal.findById(id.toLong())!!.isActive = false }

                val res =
                    client.post("/api/v1/terminals/pair") {
                        setBody(TextContent("""{"code":"$code","deviceId":"dev-INACTIVE"}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Conflict, res.status)
            }

        // Fix 4: a device moved to a different terminal must cleanly rebind, not hit the
        // device_id unique constraint as a raw 500.
        @Test
        fun `pairing a device already bound to terminal A moves it cleanly to terminal B`() =
            testApp(setupData = { TestData.createAdmin() }) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)

                val idA = newTerminalId(client, token)
                val firstPair =
                    client.post("/api/v1/terminals/pair") {
                        setBody(
                            TextContent(
                                """{"code":"${codeFor(client, token, idA)}","deviceId":"dev-MOVE"}""",
                                ContentType.Application.Json,
                            ),
                        )
                    }
                assertEquals(HttpStatusCode.OK, firstPair.status)
                val terA = Regex(""""terminalId":\s*"(TER-\d+)"""").find(firstPair.bodyAsText())!!.groupValues[1]

                val idB = newTerminalId(client, token)
                val secondPair =
                    client.post("/api/v1/terminals/pair") {
                        setBody(
                            TextContent(
                                """{"code":"${codeFor(client, token, idB)}","deviceId":"dev-MOVE"}""",
                                ContentType.Application.Json,
                            ),
                        )
                    }
                // Previously a raw 500 from the device_id unique constraint; now a clean rebind.
                assertEquals(HttpStatusCode.OK, secondPair.status)
                val terB = Regex(""""terminalId":\s*"(TER-\d+)"""").find(secondPair.bodyAsText())!!.groupValues[1]
                assertNotEquals(terA, terB)

                // Terminal A no longer owns the device: /me with X-Device-ID dev-MOVE now resolves to B.
                val me =
                    client.get("/api/v1/terminals/me") {
                        bearerAuth(token)
                        header("X-Device-ID", "dev-MOVE")
                    }
                assertEquals(HttpStatusCode.OK, me.status)
                assertTrue(me.bodyAsText().contains(terB))
            }
    }

    @Nested
    @DisplayName("POST /api/v1/terminals/{id}/revoke")
    inner class Revoke {
        /** Creates an already-bound fiscal terminal directly, bypassing the pairing flow. */
        private fun seedBoundTerminal(
            deviceId: String,
            clientId: String,
        ): Terminal =
            transaction {
                val now = currentTimeStamp()
                Terminal.new {
                    this.deviceId = deviceId
                    this.clientId = clientId
                    this.registeredAt = now
                    this.lastSeenAt = now
                    this.deviceTokenHash = "hash-$clientId"
                    this.boundAt = now
                }
            }

        private fun seedPendingBill(
            terminal: Terminal,
            session: TableSession,
        ) = transaction {
            val now = currentTimeStamp()
            Bill.new {
                this.employeeId = session.employeeId
                this.tableSession = session
                this.billNumber = "REV-BILL-${terminal.clientId}"
                this.billAmount = BigDecimal("10.00")
                this.paidAmount = BigDecimal("10.00")
                this.startTime = now
                this.endTime = now
                this.terminal = terminal
                this.tseSigningStatus = TseSigningStatus.PENDING
            }
        }

        private fun seedPendingOrder(
            terminal: Terminal,
            session: TableSession,
        ) = transaction {
            val now = currentTimeStamp()
            Orders.new {
                this.orderNumber = "REV-ORDER-${terminal.clientId}"
                this.employee = session.employeeId
                this.tableSession = session
                this.orderTime = now
                this.orderTotal = BigDecimal("10.00")
                this.orderStatus = OrderStatus.CONFIRMED
                this.terminal = terminal
                this.tseSigningStatus = TseSigningStatus.PENDING
            }
        }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.post("/api/v1/terminals/1/revoke")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `revokes a clean bound terminal and clears its binding`() =
            testApp(
                setupData = { TestData.createManager() },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val terminal = seedBoundTerminal("rev-clean-device", "REV-CLEAN")

                val res =
                    client.post("/api/v1/terminals/${terminal.id.value}/revoke") {
                        bearerAuth(token)
                    }

                assertEquals(HttpStatusCode.OK, res.status)
                val deviceIdAfter = transaction { Terminal.findById(terminal.id)!!.deviceId }
                assertNull(deviceIdAfter, "revoke must clear the device binding when there is no open TSE work")
            }

        @Test
        fun `rejects revoke with 409 while the terminal owns a PENDING bill`() =
            testApp(
                setupData = { TestData.createManager() },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val terminal = seedBoundTerminal("rev-bill-device", "REV-BILL")
                val tws = TestData.createTableWithSession(tableNumber = "REV_BILL")
                seedPendingBill(terminal, tws.session)

                val res =
                    client.post("/api/v1/terminals/${terminal.id.value}/revoke") {
                        bearerAuth(token)
                    }

                assertEquals(HttpStatusCode.Conflict, res.status)
                val deviceIdAfter = transaction { Terminal.findById(terminal.id)!!.deviceId }
                assertEquals("rev-bill-device", deviceIdAfter, "binding must remain intact when revoke is rejected")
            }

        @Test
        fun `rejects revoke with 409 while the terminal owns a PENDING order`() =
            testApp(
                setupData = { TestData.createManager() },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val terminal = seedBoundTerminal("rev-order-device", "REV-ORDER")
                val tws = TestData.createTableWithSession(tableNumber = "REV_ORDER")
                seedPendingOrder(terminal, tws.session)

                val res =
                    client.post("/api/v1/terminals/${terminal.id.value}/revoke") {
                        bearerAuth(token)
                    }

                assertEquals(HttpStatusCode.Conflict, res.status)
                val deviceIdAfter = transaction { Terminal.findById(terminal.id)!!.deviceId }
                assertEquals("rev-order-device", deviceIdAfter, "binding must remain intact when revoke is rejected")
            }
    }
}
