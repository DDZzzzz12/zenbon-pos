package org.example.project.service

import org.example.project.core.utils.RootFingerprint
import org.example.project.core.utils.sha256
import org.example.project.features.terminal.data.service.TlsRootCa
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.cert.CertificateFactory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TlsRootCaTest {
    @TempDir
    lateinit var dir: File

    @BeforeEach
    fun reset() = TlsRootCa.resetForTests()

    @AfterEach
    fun restore() {
        TlsRootCa.fileProvider = { null }
        TlsRootCa.resetForTests()
    }

    private fun fixture(name: String): String = checkNotNull(javaClass.getResource("/pairing-pin/$name")) { "missing fixture $name" }.readText()

    private fun serve(pem: String?): File {
        val file = File(dir, "root.crt")
        if (pem != null) file.writeText(pem)
        TlsRootCa.fileProvider = { file }
        return file
    }

    @Test
    fun `no configured file means no root`() {
        TlsRootCa.fileProvider = { null }
        assertNull(TlsRootCa.current())
    }

    @Test
    fun `a file that does not exist yet means no root`() {
        serve(null)
        assertNull(TlsRootCa.current())
    }

    @Test
    fun `a CA certificate is served with the fingerprint of its DER bytes`() {
        serve(fixture("root.pem"))
        val root = assertNotNull(TlsRootCa.current())
        val der = CertificateFactory.getInstance("X.509").generateCertificate(fixture("root.pem").byteInputStream()).encoded
        assertEquals(RootFingerprint.fromDigest(sha256(der)), root.fingerprint)
        assertTrue(root.pem.startsWith("-----BEGIN CERTIFICATE-----\n"))
        assertTrue(root.pem.endsWith("-----END CERTIFICATE-----\n"))
    }

    @Test
    fun `a leaf certificate is refused because it is not a CA`() {
        serve(fixture("leaf.pem"))
        assertNull(TlsRootCa.current())
    }

    @Test
    fun `garbage and a bundle of two certificates are refused`() {
        serve("not a certificate")
        assertNull(TlsRootCa.current())
        serve(fixture("root.pem") + fixture("leaf.pem"))
        TlsRootCa.resetForTests()
        assertNull(TlsRootCa.current())
    }

    @Test
    fun `a replaced file is picked up without a restart`() {
        val file = serve(null)
        assertNull(TlsRootCa.current())
        file.writeText(fixture("root.pem"))
        file.setLastModified(System.currentTimeMillis() + 5_000)
        assertNotNull(TlsRootCa.current())
    }
}
