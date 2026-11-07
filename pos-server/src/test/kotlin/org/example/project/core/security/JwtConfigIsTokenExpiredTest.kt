package org.example.project.core.security

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Date
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `isTokenExpired` decides between AUTH_SESSION_EXPIRED ("refresh and retry") and
 * AUTH_INVALID_CREDENTIALS. It used to answer true for any token that failed verification, so a
 * garbage or tampered bearer was told to refresh.
 */
@DisplayName("JwtConfig.isTokenExpired")
class JwtConfigIsTokenExpiredTest {
    private fun token(
        expiresInSeconds: Long,
        algorithm: Algorithm = JwtConfig.getAlgorithm(),
    ): String {
        val now = Instant.now()
        return JWT
            .create()
            .withIssuer(JwtConfig.getJwtIssuer())
            .withAudience(JwtConfig.getJwtAudience())
            .withSubject("00000000-0000-0000-0000-000000000001")
            .withClaim("employeeNumber", "E1")
            .withClaim("tokenType", "access")
            .withIssuedAt(Date.from(now.minusSeconds(7200)))
            .withExpiresAt(Date.from(now.plusSeconds(expiresInSeconds)))
            .sign(algorithm)
    }

    @Test
    fun `a genuine token past its expiry is expired`() {
        assertTrue(JwtConfig.isTokenExpired(token(expiresInSeconds = -3600)))
    }

    @Test
    fun `a genuine live token is not expired`() {
        assertFalse(JwtConfig.isTokenExpired(token(expiresInSeconds = 3600)))
    }

    @Test
    fun `garbage is not an expired session`() {
        assertFalse(JwtConfig.isTokenExpired("not-a-jwt"))
    }

    @Test
    fun `an expired token signed with a foreign key is not an expired session`() {
        val forged = token(expiresInSeconds = -3600, algorithm = Algorithm.HMAC256("someone-elses-secret"))
        assertFalse(JwtConfig.isTokenExpired(forged))
    }
}
