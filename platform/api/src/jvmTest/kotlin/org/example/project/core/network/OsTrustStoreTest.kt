package org.example.project.core.network

import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OsTrustStoreTest {
    @Test
    fun a_pem_bundle_is_read_past_comment_lines_and_unparseable_blocks() {
        withShopCa { dir, root, _ ->
            val bundle = File(dir, "bundle.crt")
            bundle.writeText(
                "# Fedora-style comment line\n" +
                    File(dir, "root.pem").readText() +
                    "\n# damaged entry\n-----BEGIN CERTIFICATE-----\nnot a certificate\n-----END CERTIFICATE-----\n",
            )

            val certs = OsTrustStore.readPemBundle(bundle)
            assertEquals(listOf(root), certs)
            assertTrue(OsTrustStore.build(certs).acceptedIssuers.contains(root))
        }
    }

    @Test
    fun a_leaf_issued_by_a_trusted_os_root_is_accepted() {
        withShopCa { _, root, leaf ->
            assertEquals("EC", root.publicKey.algorithm)
            assertEquals("EC", leaf.publicKey.algorithm)

            val trustManager = OsTrustStore.build(listOf(root))
            // JSSE passes "UNKNOWN" as the auth type for TLS 1.3; both calls throw if the chain does not validate.
            trustManager.checkServerTrusted(arrayOf(leaf, root), "UNKNOWN")
            trustManager.checkServerTrusted(arrayOf(leaf), "UNKNOWN")
        }
    }

    @Test
    fun a_leaf_issued_by_a_root_the_os_does_not_trust_is_rejected() {
        withShopCa { _, root, leaf ->
            val trustManager = OsTrustStore.build(emptyList())
            assertFailsWith<CertificateException> { trustManager.checkServerTrusted(arrayOf(leaf, root), "UNKNOWN") }
            assertFailsWith<CertificateException> { trustManager.checkServerTrusted(arrayOf(leaf), "UNKNOWN") }
        }
    }

    @Test
    fun the_jvm_default_roots_are_kept() {
        val jvmRoots =
            TrustManagerFactory
                .getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(null as KeyStore?) }
                .trustManagers
                .filterIsInstance<X509TrustManager>()
                .first()
                .acceptedIssuers
                .toSet()

        val builtRoots = OsTrustStore.build(emptyList()).acceptedIssuers.toSet()
        assertTrue(jvmRoots.isNotEmpty())
        assertTrue(builtRoots.containsAll(jvmRoots))
    }

    @Test
    fun a_missing_bundle_reads_as_empty() {
        assertTrue(OsTrustStore.readPemBundle(File("/nonexistent/ca-bundle.crt")).isEmpty())
    }

    /**
     * Runs [block] with a throwaway chain shaped like Caddy's local CA, made with the test JVM's own
     * keytool: an EC P-256 root (`root.pem`) and an EC P-256 server leaf for an IP address signed by
     * it (`leaf.pem`). The directory is deleted afterwards.
     */
    private fun withShopCa(block: (dir: File, root: X509Certificate, leaf: X509Certificate) -> Unit) {
        val dir = createTempDirectory("os-trust").toFile()
        try {
            val csr = File(dir, "leaf.csr").path
            val leafPem = File(dir, "leaf.pem")
            val rootPem = File(dir, "root.pem")
            keytool(
                dir,
                "-genkeypair",
                "-alias",
                "root",
                "-keyalg",
                "EC",
                "-groupname",
                "secp256r1",
                "-dname",
                "CN=Test Shop Local CA",
                "-validity",
                "2",
                "-ext",
                "bc:c",
            )
            keytool(dir, "-genkeypair", "-alias", "leaf", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=192.168.1.10", "-validity", "2")
            keytool(dir, "-certreq", "-alias", "leaf", "-file", csr)
            keytool(
                dir,
                "-gencert",
                "-alias",
                "root",
                "-infile",
                csr,
                "-outfile",
                leafPem.path,
                "-rfc",
                "-validity",
                "2",
                "-ext",
                "san=ip:192.168.1.10",
                "-ext",
                "ku=digitalSignature",
                "-ext",
                "eku=serverAuth",
            )
            keytool(dir, "-exportcert", "-rfc", "-alias", "root", "-file", rootPem.path)

            block(dir, OsTrustStore.readPemBundle(rootPem).single(), OsTrustStore.readPemBundle(leafPem).single())
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Runs the test JVM's keytool against the shared `ca.p12` keystore in [dir]. */
    private fun keytool(
        dir: File,
        vararg args: String,
    ) {
        val osName = System.getProperty("os.name").orEmpty()
        val windows = osName.lowercase().contains("win")
        val keytool = File(System.getProperty("java.home"), if (windows) "bin/keytool.exe" else "bin/keytool").path
        val keystore = File(dir, "ca.p12").path
        val command = listOf(keytool) + args + listOf("-keystore", keystore, "-storetype", "PKCS12", "-storepass", "changeit")
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { output }
    }
}
