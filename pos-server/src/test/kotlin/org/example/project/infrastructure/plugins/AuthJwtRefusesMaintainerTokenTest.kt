package org.example.project.infrastructure.plugins

import com.auth0.jwt.JWT
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import org.example.project.core.security.JwtConfig
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import java.time.Instant
import java.util.Date
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Layer 2 of the containment: the `auth-jwt` provider itself refuses a maintainer token. The probe
 * route lives outside `/api`, so RouteAuthorizationPlugin does not look at it — only the provider
 * stands between the token and the handler.
 */
class AuthJwtRefusesMaintainerTokenTest {
    private val probe: io.ktor.server.routing.Route.() -> Unit = {
        authenticate("auth-jwt") { get("/__probe/protected") { call.respondText("reached") } }
    }

    @Test
    fun `an employee token reaches the probe - so the 401 below is meaningful`() =
        testApp(setupData = { TestData.createAdmin() }, extraRoutes = probe) { client ->
            val response = client.get("/__probe/protected") { bearerAuth(TestAuthHelper.loginAsAdmin(client)) }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("reached", response.bodyAsText())
        }

    @Test
    fun `a maintainer token does not become a principal on an auth-jwt route`() =
        testApp(extraRoutes = probe) { client ->
            val response = client.get("/__probe/protected") { bearerAuth(JwtConfig.generateMaintainerToken(30)) }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `a maintainer-typed token is refused even when it also carries an employeeNumber`() =
        testApp(extraRoutes = probe) { client ->
            // The real maintainer token has no employeeNumber, so the test above passes on the provider's
            // employeeNumber check alone. This one passes only if the provider refuses the tokenType itself.
            val control = client.get("/__probe/protected") { bearerAuth(signedWithEmployeeNumber(tokenType = "access")) }
            assertEquals(HttpStatusCode.OK, control.status, "the same token typed 'access' reaches the probe, so only tokenType differs")

            val response =
                client.get("/__probe/protected") { bearerAuth(signedWithEmployeeNumber(tokenType = JwtConfig.MAINTAINER_TOKEN_TYPE)) }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    /** A genuinely signed token with subject `maintainer` and an `employeeNumber`, differing only in [tokenType]. */
    private fun signedWithEmployeeNumber(tokenType: String): String {
        val now = Instant.now()
        return JWT
            .create()
            .withJWTId(UUID.randomUUID().toString())
            .withIssuer(JwtConfig.getJwtIssuer())
            .withAudience(JwtConfig.getJwtAudience())
            .withSubject(JwtConfig.MAINTAINER_SUBJECT)
            .withClaim("tokenType", tokenType)
            .withClaim("employeeNumber", "1001")
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(now.plusSeconds(600)))
            .sign(JwtConfig.getAlgorithm())
    }
}
