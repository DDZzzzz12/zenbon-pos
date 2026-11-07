package org.example.project.features.maintenance

import at.favre.lib.crypto.bcrypt.BCrypt
import com.auth0.jwt.JWT
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.security.JwtConfig
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.database.tables.ConfigKey
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.LogFixtures
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.dbQuery
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MaintenanceLogsRoutesTest {
    @TempDir
    lateinit var logDir: File

    private val password = "route-test-password"
    private var previousLogDir: String? = null

    @BeforeEach
    fun prepare() {
        MaintenancePolicy.useForTesting(MaintenanceConfig("maint", BCrypt.withDefaults().hashToString(4, password.toCharArray()), 30))
        previousLogDir = System.getProperty("LOG_DIR")
        System.setProperty("LOG_DIR", logDir.absolutePath)
        val recent = OffsetDateTime.now().minusMinutes(5).toString()
        File(logDir, "rejected.log").writeText(
            LogFixtures.jsonLine(
                recent,
                "AUTH_FORBIDDEN 403 — Employee 1042 does not have permission",
                traceId = "trace-xyz",
                code = "AUTH_FORBIDDEN",
                status = 403,
                method = "GET",
                path = "/api/employees/42",
                employeeId = "emp-secret-123",
                deviceId = "dev-secret-9",
            ) + "\n",
        )
    }

    @AfterEach
    fun restore() {
        MaintenancePolicy.useForTesting(null)
        if (previousLogDir == null) System.clearProperty("LOG_DIR") else System.setProperty("LOG_DIR", previousLogDir!!)
    }

    private suspend fun HttpClient.maintainerToken(): String {
        val body =
            post("/api/maintenance/login") {
                setBody(TextContent("""{"username":"maint","password":"$password"}""", ContentType.Application.Json))
            }.bodyAsText()
        return Json
            .parseToJsonElement(body)
            .jsonObject
            .getValue("accessToken")
            .jsonPrimitive.content
    }

    /** A maintainer token signed by hand so its issue time can be placed in the past. */
    private fun maintainerTokenIssuedSecondsAgo(secondsAgo: Long): String {
        val issuedAt = Instant.now().minusSeconds(secondsAgo)
        return JWT
            .create()
            .withIssuer(JwtConfig.getJwtIssuer())
            .withAudience(JwtConfig.getJwtAudience())
            .withSubject(JwtConfig.MAINTAINER_SUBJECT)
            .withClaim("tokenType", JwtConfig.MAINTAINER_TOKEN_TYPE)
            .withIssuedAt(Date.from(issuedAt))
            .withExpiresAt(Date.from(Instant.now().plusSeconds(600)))
            .sign(JwtConfig.getAlgorithm())
    }

    private suspend fun firstShopId(): Long =
        dbQuery {
            ShopInfo
                .all()
                .first()
                .id.value
        }

    /** ShopConfigurationManager caches process-wide: put the default session policy back, in the DB and the cache. */
    private suspend fun restoreDefaultSessionPolicy() {
        val shopId = firstShopId()
        dbQuery {
            ConfigurationRepository().setSetting(
                shopId,
                ConfigKey.TOKEN_SESSION_POLICY,
                ConfigKey.TOKEN_SESSION_POLICY.defaultValue,
            )
        }
        ShopConfigurationManager.refreshConfiguration()
    }

    private fun seedShopWithFreshConfig() {
        TestData.createShopInfo()
        runBlocking { restoreDefaultSessionPolicy() }
    }

    @Test
    fun `a rejected request is found by its trace id and shows its scrubbed reason`() =
        testApp { client ->
            val response = client.get("/api/maintenance/logs?traceId=trace-xyz") { bearerAuth(client.maintainerToken()) }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            val entries =
                Json
                    .parseToJsonElement(body)
                    .jsonObject
                    .getValue("entries")
                    .jsonArray
            assertEquals(1, entries.size, body)
            assertTrue("AUTH_FORBIDDEN" in body)
            assertTrue("does not have permission" in body, "the reason must be readable: $body")
            assertTrue("/api/employees/{id}" in body, body)
            listOf("1042", "emp-secret-123", "dev-secret-9", "employeeId", "deviceId").forEach {
                assertFalse(it in body, "'$it' leaked: $body")
            }
        }

    @Test
    fun `free text cannot be used to confirm a hidden number`() =
        testApp { client ->
            val token = client.maintainerToken()
            val hit = client.get("/api/maintenance/logs?traceId=trace-xyz&q=1042") { bearerAuth(token) }
            val entries: JsonArray =
                Json
                    .parseToJsonElement(hit.bodyAsText())
                    .jsonObject
                    .getValue("entries")
                    .jsonArray
            assertEquals(0, entries.size)
        }

    @Test
    fun `a bad filter is a 400`() =
        testApp { client ->
            val response = client.get("/api/maintenance/logs?level=LOUD") { bearerAuth(client.maintainerToken()) }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue("VALIDATION_FAILED" in response.bodyAsText())
        }

    @Test
    fun `a missing log directory is a 503`() =
        testApp { client ->
            System.setProperty("LOG_DIR", File(logDir, "gone").absolutePath)
            val response = client.get("/api/maintenance/logs") { bearerAuth(client.maintainerToken()) }
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertTrue("MAINTENANCE_LOG_DIR_UNAVAILABLE" in response.bodyAsText())
        }

    @Test
    fun `no token is a 401`() =
        testApp { client ->
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/maintenance/logs?traceId=trace-xyz").status)
        }

    @Test
    fun `a lowercase bearer scheme is accepted`() =
        testApp { client ->
            val token = client.maintainerToken()
            val response = client.get("/api/maintenance/logs?traceId=trace-xyz") { header(HttpHeaders.Authorization, "bearer $token") }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        }

    @Test
    fun `a token issued before a session cutoff is refused with 401`() =
        testApp(setupData = { seedShopWithFreshConfig() }) { client ->
            try {
                val stale = maintainerTokenIssuedSecondsAgo(30)
                assertEquals(
                    HttpStatusCode.OK,
                    client.get("/api/maintenance/logs?traceId=trace-xyz") { bearerAuth(stale) }.status,
                    "before the cutoff the token must work",
                )

                ShopConfigurationManager.triggerManualSessionCutoff()

                assertEquals(
                    HttpStatusCode.Unauthorized,
                    client.get("/api/maintenance/logs?traceId=trace-xyz") { bearerAuth(stale) }.status,
                )

                // The cutoff compares whole epoch seconds (iat < cutoff), so step past the cutoff's second.
                Thread.sleep(1_100)
                assertEquals(
                    HttpStatusCode.OK,
                    client.get("/api/maintenance/logs?traceId=trace-xyz") { bearerAuth(client.maintainerToken()) }.status,
                    "a token minted after the cutoff must still work",
                )
            } finally {
                restoreDefaultSessionPolicy()
            }
        }
}
