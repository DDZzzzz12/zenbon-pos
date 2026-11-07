package org.example.project.core.security

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [JwtConfig.extractBearerToken] is the one parser the maintainer containment guard relies on. Ktor's
 * own header parser reads the scheme case-insensitively (RFC 7235), so a case-sensitive parser here
 * would let `bearer <token>` past a guard that `Bearer <token>` cannot pass.
 */
class JwtExtractBearerTokenTest {
    @Test
    fun `the scheme is matched case-insensitively and the token trimmed, an empty token is none`() {
        val cases =
            listOf(
                "Bearer x" to "x",
                "bearer x" to "x",
                "BEARER x" to "x",
                "Bearer   x " to "x",
                "Bearer " to null,
                "Bearer" to null,
                "Basic x" to null,
                null to null,
            )
        cases.forEach { (header, expected) ->
            assertEquals(expected, JwtConfig.extractBearerToken(header), "for header '$header'")
        }
    }
}
