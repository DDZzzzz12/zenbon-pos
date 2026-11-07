package org.example.project.infrastructure.plugins

import com.auth0.jwt.JWT
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.core.security.JwtConfig
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Date
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A till that is paired (it sends `X-Device-Token`) AND has a person logged in (it sends a bearer
 * JWT) keeps sending both after the person's access token expires. `RouteAuthorizationPlugin`
 * used to treat the unverifiable bearer as absent and resolve the request as the login-less
 * station: the answer was `403 Required capability: …` for a capability the person plainly holds.
 * A 403 never triggers the client's token refresh — only a 401 does — so the operator was locked
 * out of every capability-gated action until they reloaded and logged in again. Found on the
 * 2026-09-11 upgrade rehearsal, five times in a row on the Z-Bon confirm.
 *
 * A presented bearer that fails verification must answer 401 — `AUTH_SESSION_EXPIRED` for an
 * expired one — so the client refreshes and retries. The device token is not a fallback identity
 * for a person whose session lapsed.
 */
@DisplayName("An expired bearer token answers 401, never a downgrade to the device principal")
class ExpiredBearerIsNotDowngradedTest {
    private data class PairedStation(
        val clientId: String,
        val deviceToken: String,
    )

    private val deviceId = "expired-bearer-till"

    /** Provisions a fiscal ORDER_STATION terminal via the real HTTP pairing flow. */
    private suspend fun pairOrderStation(
        client: HttpClient,
        adminToken: String,
        deviceId: String,
    ): PairedStation {
        val id =
            client
                .post("/api/v1/terminals") {
                    bearerAuth(adminToken)
                    setBody(TextContent("""{"name":"Station","role":"ORDER_STATION"}""", ContentType.Application.Json))
                }.bodyAsText()
                .let { Regex(""""id":\s*(\d+)""").find(it)!!.groupValues[1] }

        val code =
            client
                .post("/api/v1/terminals/$id/pairing-code") {
                    bearerAuth(adminToken)
                }.bodyAsText()
                .let { Regex(""""code":\s*"(\d{6})"""").find(it)!!.groupValues[1] }

        val pairBody =
            client
                .post("/api/v1/terminals/pair") {
                    setBody(TextContent("""{"code":"$code","deviceId":"$deviceId"}""", ContentType.Application.Json))
                }.bodyAsText()

        val clientId = Regex(""""terminalId":\s*"(TER-\d+)"""").find(pairBody)!!.groupValues[1]
        val deviceToken = Regex(""""deviceToken":\s*"([^"]+)"""").find(pairBody)!!.groupValues[1]
        return PairedStation(clientId, deviceToken)
    }

    /**
     * The access token the login would have minted an hour ago: same claim set, same signing key,
     * `exp` in the past. `JwtConfig` reads its expiry from configuration, so a genuinely expired
     * token has to be built by hand.
     */
    private fun expiredAccessToken(
        employeeId: String,
        employeeNumber: String,
    ): String {
        val now = Instant.now()
        return JWT
            .create()
            .withJWTId(UUID.randomUUID().toString())
            .withIssuer(JwtConfig.getJwtIssuer())
            .withAudience(JwtConfig.getJwtAudience())
            .withSubject(employeeId)
            .withClaim("employeeNumber", employeeNumber)
            .withClaim("role", EmployeeRole.ADMIN.name)
            .withClaim("deviceId", deviceId)
            .withClaim("tokenType", "access")
            .withIssuedAt(Date.from(now.minusSeconds(7200)))
            .withExpiresAt(Date.from(now.minusSeconds(3600)))
            .sign(JwtConfig.getAlgorithm())
    }

    @Test
    fun `an expired employee token on a paired till answers 401 session expired, not 403 as the device`() {
        lateinit var adminId: String
        lateinit var adminNumber: String
        testApp(
            setupData = {
                val admin = TestData.createAdmin()
                transaction {
                    adminId = admin.id.value.toString()
                    adminNumber = admin.employeeNumber
                }
                TestData.createTerminalDeviceEmployee()
                TestData.createShopInfo()
            },
        ) { client ->
            val adminToken = TestAuthHelper.loginAsAdmin(client)
            val station = pairOrderStation(client, adminToken, deviceId)

            val response =
                client.get("/api/v1/kassenbuch/balance") {
                    bearerAuth(expiredAccessToken(adminId, adminNumber))
                    header("X-Device-Token", station.deviceToken)
                }

            val body = response.bodyAsText()
            assertEquals(
                HttpStatusCode.Unauthorized,
                response.status,
                "an expired bearer must be refused as such, not re-identified as the station: $body",
            )
            assertTrue(
                body.contains("\"code\":\"AUTH_SESSION_EXPIRED\""),
                "the problem response must say the session expired, not that credentials were invalid: $body",
            )
        }
    }
}
