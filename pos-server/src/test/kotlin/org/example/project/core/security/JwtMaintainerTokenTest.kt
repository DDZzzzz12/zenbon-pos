package org.example.project.core.security

import com.auth0.jwt.JWT
import java.time.Instant
import java.util.Date
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JwtMaintainerTokenTest {
    private fun expiredMaintainerToken(): String {
        val now = Instant.now()
        return JWT
            .create()
            .withJWTId(UUID.randomUUID().toString())
            .withIssuer(JwtConfig.getJwtIssuer())
            .withAudience(JwtConfig.getJwtAudience())
            .withSubject(JwtConfig.MAINTAINER_SUBJECT)
            .withClaim("tokenType", JwtConfig.MAINTAINER_TOKEN_TYPE)
            .withIssuedAt(Date.from(now.minusSeconds(7200)))
            .withExpiresAt(Date.from(now.minusSeconds(3600)))
            .sign(JwtConfig.getAlgorithm())
    }

    @Test
    fun `a generated maintainer token validates and expires after the requested lifetime`() {
        val token = JwtConfig.generateMaintainerToken(lifetimeMinutes = 30)
        val claims = assertNotNull(JwtConfig.validateMaintainerToken(token))
        val lifetime = claims.expiresAt!!.epochSecond - claims.issuedAt!!.epochSecond
        assertEquals(30 * 60L, lifetime)
        assertNotNull(claims.jti)
    }

    @Test
    fun `validateToken refuses a maintainer token so no employee or device path can see it`() {
        // Every existing caller of validateToken (the authorization plugin, ClientErrorRoutes,
        // device-id resolution) is thereby closed to the maintainer without being edited.
        val token = JwtConfig.generateMaintainerToken(30)
        assertNull(JwtConfig.validateToken(token))
    }

    @Test
    fun `an employee access token is not a maintainer token`() {
        val employeeToken = JwtConfig.generateAccessToken("11111111-1111-1111-1111-111111111111", "1001", "ADMIN", "till-1")
        assertNotNull(JwtConfig.validateToken(employeeToken))
        assertNull(JwtConfig.validateMaintainerToken(employeeToken))
    }

    @Test
    fun `an expired, garbled or foreign-signed maintainer token is refused`() {
        assertNull(JwtConfig.validateMaintainerToken(expiredMaintainerToken()))
        assertNull(JwtConfig.validateMaintainerToken("not.a.jwt"))
        assertNull(JwtConfig.validateMaintainerToken(""))
        val foreign =
            JWT
                .create()
                .withIssuer(JwtConfig.getJwtIssuer())
                .withAudience(JwtConfig.getJwtAudience())
                .withSubject(JwtConfig.MAINTAINER_SUBJECT)
                .withClaim("tokenType", JwtConfig.MAINTAINER_TOKEN_TYPE)
                .withExpiresAt(Date.from(Instant.now().plusSeconds(600)))
                .sign(
                    com.auth0.jwt.algorithms.Algorithm
                        .HMAC256("some-other-secret"),
                )
        assertNull(JwtConfig.validateMaintainerToken(foreign))
    }

    @Test
    fun `the maintainer token carries no employee, role or device claim`() {
        val decoded = JWT.decode(JwtConfig.generateMaintainerToken(30))
        assertEquals(JwtConfig.MAINTAINER_SUBJECT, decoded.subject)
        assertTrue(decoded.getClaim("employeeNumber").isMissing)
        assertTrue(decoded.getClaim("role").isMissing)
        assertTrue(decoded.getClaim("deviceId").isMissing)
    }
}
