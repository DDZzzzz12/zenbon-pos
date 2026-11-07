package org.example.project.core.network

import java.io.ByteArrayInputStream
import java.security.cert.CertPathValidator
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * [base] first; if it rejects the chain, a chain that validates to the app's [PinnedRootTrust] root
 * is accepted as well. Reads the pin on every handshake, so pinning needs no client rebuild.
 *
 * The server sends leaf + intermediate (never the root); Caddy renews those on its own every few
 * hours or days, which is why the pin is the root. Hostnames are checked by the HTTP stack as usual.
 */
class PinnedRootTrustManager(
    private val base: () -> X509TrustManager,
) : X509TrustManager {
    @Volatile
    private var parsed: Pair<String, X509Certificate>? = null

    override fun checkClientTrusted(
        chain: Array<X509Certificate>,
        authType: String,
    ) = base().checkClientTrusted(chain, authType)

    override fun checkServerTrusted(
        chain: Array<X509Certificate>,
        authType: String,
    ) {
        try {
            base().checkServerTrusted(chain, authType)
        } catch (e: CertificateException) {
            val root = pinnedRoot() ?: throw e
            validateToRoot(chain, root, e)
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = base().acceptedIssuers + listOfNotNull(pinnedRoot())

    private fun validateToRoot(
        chain: Array<X509Certificate>,
        root: X509Certificate,
        original: CertificateException,
    ) {
        try {
            val path = CertificateFactory.getInstance("X.509").generateCertPath(chain.filter { it != root })
            val params = PKIXParameters(setOf(TrustAnchor(root, null))).apply { isRevocationEnabled = false }
            CertPathValidator.getInstance("PKIX").validate(path, params)
        } catch (e: Exception) {
            original.addSuppressed(e)
            throw original
        }
    }

    private fun pinnedRoot(): X509Certificate? {
        val pem = PinnedRootTrust.pem ?: return null
        parsed?.takeIf { it.first == pem }?.let { return it.second }
        val cert =
            runCatching {
                CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(pem.toByteArray())) as X509Certificate
            }.getOrNull()
        parsed = cert?.let { pem to it }
        return cert
    }
}
