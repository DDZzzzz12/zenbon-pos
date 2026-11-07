package org.example.project.core.network.error

/**
 * Whether this failure — or anything it wraps — is the TLS stack refusing the server's certificate
 * (an unknown CA, a bad chain, an unverifiable peer), as opposed to a network that is down.
 *
 * Matched by class name because the exceptions are JVM types (`javax.net.ssl`, `java.security.cert`)
 * that common code cannot name. The wording of the name is stable across the JDK and Android.
 */
fun Throwable.isCertificateTrustFailure(): Boolean {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        val name = current::class.simpleName.orEmpty()
        if (TRUST_FAILURE_NAMES.any { it in name }) return true
        if (current.message?.contains(TRUST_ANCHOR_MESSAGE, ignoreCase = true) == true) return true
        current = current.cause
        depth++
    }
    return false
}

private const val MAX_CAUSE_DEPTH = 8
private const val TRUST_ANCHOR_MESSAGE = "trust anchor for certification path not found"
private val TRUST_FAILURE_NAMES =
    listOf("SSLHandshake", "SSLPeerUnverified", "CertPathValidator", "CertPathBuilder", "CertificateException", "ValidatorException")
