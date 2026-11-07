package org.example.project.core.network

import io.ktor.util.logging.KtorSimpleLogger
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Certificate trust for the desktop till: the JVM's bundled roots PLUS the operating system's.
 *
 * The bundled runtime reads only its own `cacerts`. A shop without a domain runs Caddy's local CA
 * and installs the root on each PC the way the manual describes (Windows certificate store, or the
 * distro CA bundle via `update-ca-certificates`), so trusting only `cacerts` would reject a server
 * every browser on the same PC accepts. macOS is not covered: desktop tills ship as .deb and .msi.
 */
internal object OsTrustStore {
    private val log = KtorSimpleLogger("OsTrustStore")

    private val LINUX_BUNDLES =
        listOf(
            "/etc/ssl/certs/ca-certificates.crt", // Debian, Ubuntu
            "/etc/pki/tls/certs/ca-bundle.crt", // Fedora, RHEL
        )
    private val PEM_BLOCK = Regex("-----BEGIN CERTIFICATE-----[\\s\\S]*?-----END CERTIFICATE-----")

    /**
     * Built once per process. A failure to read the OS store must not take the till offline: `lazy`
     * does not cache an exception, so a rethrow would fail every new HTTP client. Instead the till
     * logs it and falls back to the JVM's bundled roots.
     */
    private val osTrustManager: X509TrustManager by lazy { buildWithOsRoots() }

    /** The OS-aware manager, plus the root the app was told to pin at pairing ([PinnedRootTrust]). */
    val trustManager: X509TrustManager = PinnedRootTrustManager { osTrustManager }

    val socketFactory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }.socketFactory
    }

    fun build(extra: List<X509Certificate>): X509TrustManager {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        jvmDefaultTrustManager().acceptedIssuers.forEachIndexed { index, cert -> store.setCertificateEntry("jvm-$index", cert) }
        extra.forEachIndexed { index, cert -> store.setCertificateEntry("os-$index", cert) }
        return trustManagerFor(store)
    }

    /**
     * Reads every PEM certificate in [file], skipping comments and unparseable blocks. A missing file
     * reads as empty; a file that exists but cannot be read throws.
     */
    fun readPemBundle(file: File): List<X509Certificate> {
        if (!file.isFile) return emptyList()
        val factory = CertificateFactory.getInstance("X.509")
        val blocks = PEM_BLOCK.findAll(file.readText()).toList()
        val certificates =
            blocks.mapNotNull { block ->
                runCatching { factory.generateCertificate(block.value.byteInputStream()) as X509Certificate }.getOrNull()
            }
        val skipped = blocks.size - certificates.size
        if (skipped > 0) log.warn("Skipped $skipped unparseable certificate blocks in ${file.path}")
        return certificates
    }

    private fun buildWithOsRoots(): X509TrustManager =
        try {
            val (source, roots) = osRoots()
            build(roots).also { log.info("Trusting ${roots.size} additional roots $source") }
        } catch (e: Exception) {
            log.warn("Could not load the OS certificate store; trusting only the JVM's bundled roots", e)
            build(emptyList())
        }

    /** The OS roots and a description of where they came from. Throws if a store exists but cannot be read. */
    private fun osRoots(): Pair<String, List<X509Certificate>> {
        val osName = System.getProperty("os.name").orEmpty()
        val os = osName.lowercase()
        return when {
            os.contains("win") -> "from Windows-ROOT" to windowsRoots()
            os.contains("linux") -> {
                val bundles = LINUX_BUNDLES.map(::File).filter { it.isFile }
                val source =
                    if (bundles.isEmpty()) {
                        "(no CA bundle at ${LINUX_BUNDLES.joinToString()})"
                    } else {
                        "from ${bundles.joinToString { it.path }}"
                    }
                source to bundles.flatMap(::readPemBundle)
            }
            else -> "(no OS certificate store on $osName)" to emptyList()
        }
    }

    /**
     * Every certificate in the Windows trusted-root store. Needs the `jdk.crypto.mscapi` module in
     * the packaged runtime (ui/till/build.gradle.kts).
     *
     * Trust scope: the JDK's `Windows-ROOT` keystore ignores the Windows "Disallowed" (Untrusted
     * Certificates) store and per-certificate purpose restrictions, so a root that is disabled only
     * through those settings is still trusted by the till. Distrusting a root means removing it from
     * the trusted-root store.
     */
    private fun windowsRoots(): List<X509Certificate> {
        val store = KeyStore.getInstance("Windows-ROOT").apply { load(null, null) }
        return store.aliases().toList().mapNotNull { store.getCertificate(it) as? X509Certificate }
    }

    private fun jvmDefaultTrustManager(): X509TrustManager = trustManagerFor(null)

    private fun trustManagerFor(store: KeyStore?): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }
}
