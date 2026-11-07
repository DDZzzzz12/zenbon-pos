/**
 * Exception thrown when the TSE certificate has expired.
 *
 * Used in contexts that require exception-based error propagation
 * (e.g., maintenance task loops on the TSE thread).
 *
 * @param expirationDate Unix timestamp (seconds) when the certificate expired
 * @since 2.0.0
 */
package org.example.project.core.domain.error

class TseCertificateExpiredException(
    val expirationDate: Long = 0L,
) : RuntimeException("TSE certificate has expired (expiration: $expirationDate)")
