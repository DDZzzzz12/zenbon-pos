package org.example.project.features.terminal.data.service

import org.example.project.core.utils.RootFingerprint
import org.example.project.core.utils.sha256
import org.example.project.utils.TestOnlyApi
import org.slf4j.LoggerFactory
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * The root certificate of the local CA that signs this server's HTTPS certificates (Caddy's).
 *
 * The installer exports it to a world-readable file and names it in `POS_TLS_ROOT_CA_FILE`. The file
 * does not exist until Caddy has started once, and a development or domain-certificate server has
 * none at all, so absence is normal and means "no certificate pin is offered" — pairing then works as
 * it always did. Read lazily and re-read when the file changes, so a server that was running before
 * the file appeared picks it up without a restart.
 *
 * The root is public. Serving it unauthenticated gives an attacker nothing; its fingerprint, handed to
 * the till out of band by the admin screen, is what makes the till trust it.
 */
object TlsRootCa {
    private val logger = LoggerFactory.getLogger(TlsRootCa::class.java)

    /** A parsed root: its PEM as served, and its [RootFingerprint] Prüfcode. */
    data class Root(
        val pem: String,
        val fingerprint: String,
    )

    private data class Cached(
        val path: String,
        val modifiedAt: Long,
        val root: Root?,
    )

    /** Where the file is read from; tests replace it. */
    @TestOnlyApi
    @Volatile
    var fileProvider: () -> File? = { System.getenv(ENV_VAR)?.takeIf { it.isNotBlank() }?.let(::File) }

    @Volatile
    private var cached: Cached? = null

    /** The root, or null when none is configured, the file is missing, or it is not a single CA certificate. */
    @OptIn(TestOnlyApi::class)
    fun current(): Root? {
        val file = fileProvider() ?: return null
        if (!file.isFile) return null
        val modifiedAt = file.lastModified()
        cached?.takeIf { it.path == file.path && it.modifiedAt == modifiedAt }?.let { return it.root }
        val root = readRoot(file)
        cached = Cached(file.path, modifiedAt, root)
        return root
    }

    /** Test-only: forgets the cache. */
    @TestOnlyApi
    fun resetForTests() {
        cached = null
    }

    private fun readRoot(file: File): Root? {
        val cert =
            runCatching {
                if (file.length() > MAX_FILE_BYTES) return null
                val parsed = CertificateFactory.getInstance("X.509").generateCertificates(file.inputStream()).toList()
                parsed.singleOrNull() as? X509Certificate
            }.getOrNull()
        if (cert == null) {
            logger.warn("TLS root file {} is not a single X.509 certificate; no certificate pin is offered", file.path)
            return null
        }
        if (cert.basicConstraints < 0) {
            logger.warn("TLS root file {} holds a certificate that is not a CA; no certificate pin is offered", file.path)
            return null
        }
        val pem =
            "-----BEGIN CERTIFICATE-----\n" +
                java.util.Base64
                    .getMimeEncoder(64, "\n".toByteArray())
                    .encodeToString(cert.encoded) +
                "\n-----END CERTIFICATE-----\n"
        return Root(pem, RootFingerprint.fromDigest(sha256(cert.encoded)))
    }

    const val ENV_VAR = "POS_TLS_ROOT_CA_FILE"
    private const val MAX_FILE_BYTES = 16 * 1024L
}
