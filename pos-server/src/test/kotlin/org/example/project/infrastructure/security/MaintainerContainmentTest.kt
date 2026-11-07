package org.example.project.infrastructure.security

import at.favre.lib.crypto.bcrypt.BCrypt
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.example.project.core.security.JwtConfig
import org.example.project.features.maintenance.MaintenanceConfig
import org.example.project.features.maintenance.MaintenancePolicy
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.time.Instant
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The maintainer token opens ONE kind of route (spec §5.4). These tests are the evidence.
 */
class MaintainerContainmentTest {
    private val enabled =
        MaintenanceConfig("maint", BCrypt.withDefaults().hashToString(4, "pw-for-tests".toCharArray()), 30)

    @BeforeEach
    fun enableFeature() {
        MaintenancePolicy.useForTesting(enabled)
    }

    @AfterEach
    fun restore() {
        MaintenancePolicy.useForTesting(null)
    }

    @Test
    fun `a maintainer token is refused with 403 on every rule that is neither maintainer nor public`() =
        testApp { client ->
            val token = JwtConfig.generateMaintainerToken(30)
            val leaked = mutableListOf<String>()
            var checked = 0
            RoutePermissions
                .registeredRules()
                .filter { (_, _, access) -> access !is AccessLevel.Maintainer && access !is AccessLevel.Public }
                .forEach { (path, httpMethod, _) ->
                    val concrete = path.replace(Regex("\\{[^}]*}"), "1")
                    val status =
                        client
                            .request(concrete) {
                                method = httpMethod
                                bearerAuth(token)
                            }.status
                    checked++
                    if (status != HttpStatusCode.Forbidden) leaked += "${httpMethod.value} $concrete -> $status"
                }
            assertTrue(checked > 100, "the walk covered only $checked rules — the filter is broken, not the registry")
            assertTrue(leaked.isEmpty(), "maintainer token was not refused with 403 on:\n${leaked.joinToString("\n")}")
        }

    @Test
    fun `the 403 does not depend on how the Authorization scheme is spelled`() =
        testApp { client ->
            // bearerAuth() always sends "Bearer", so the walk above cannot see a case-sensitive parser.
            val token = JwtConfig.generateMaintainerToken(30)
            listOf("Bearer", "bearer", "BEARER").forEach { scheme ->
                val status =
                    client
                        .get("/api/admin/permissions") {
                            header(HttpHeaders.Authorization, "$scheme $token")
                        }.status
                assertEquals(HttpStatusCode.Forbidden, status, "scheme '$scheme'")
            }
        }

    @Test
    fun `a maintainer-typed token with a foreign signature is refused with 403 too`() =
        testApp { client ->
            // Fail closed: the guard reads the type without verifying, so a forgery is refused like the real thing.
            val forged =
                JWT
                    .create()
                    .withIssuer(JwtConfig.getJwtIssuer())
                    .withAudience(JwtConfig.getJwtAudience())
                    .withSubject(JwtConfig.MAINTAINER_SUBJECT)
                    .withClaim("tokenType", JwtConfig.MAINTAINER_TOKEN_TYPE)
                    .withIssuedAt(Date.from(Instant.now()))
                    .withExpiresAt(Date.from(Instant.now().plusSeconds(600)))
                    .sign(Algorithm.HMAC256("some-other-secret"))
            val status = client.get("/api/admin/permissions") { bearerAuth(forged) }.status
            assertEquals(HttpStatusCode.Forbidden, status)
        }

    @Test
    fun `the maintainer token cannot write to the client-error log through the public route`() =
        testApp { client ->
            // /api/v1/client-errors is Public and accepts "a valid JWT with a deviceId". A maintainer
            // token must not count: validateToken answers null for it.
            val response =
                client.post("/api/v1/client-errors") {
                    bearerAuth(JwtConfig.generateMaintainerToken(30))
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `the log search answers 404 while the feature is off, whatever is presented`() =
        testApp { client ->
            MaintenancePolicy.useForTesting(MaintenanceConfig.DISABLED)
            assertEquals(HttpStatusCode.NotFound, client.get("/api/maintenance/logs").status)
            assertEquals(
                HttpStatusCode.NotFound,
                client.get("/api/maintenance/logs") { bearerAuth(JwtConfig.generateMaintainerToken(30)) }.status,
            )
        }

    @Test
    fun `the log search needs a maintainer token - nothing, or an employee token, is a 401`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/maintenance/logs").status)
            val adminToken = TestAuthHelper.loginAsAdmin(client)
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get("/api/maintenance/logs") { bearerAuth(adminToken) }.status,
                "even an ADMIN is not the maintainer",
            )
        }
}
