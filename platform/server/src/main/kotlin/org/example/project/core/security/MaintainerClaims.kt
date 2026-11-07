package org.example.project.core.security

import java.time.Instant

/**
 * The verified claims of a maintainer token. Deliberately NOT [TokenClaims]: that type is an
 * employee/device identity (employee id, role, device id), and a maintainer is none of those.
 */
data class MaintainerClaims(
    val jti: String?,
    val issuedAt: Instant?,
    val expiresAt: Instant?,
)
