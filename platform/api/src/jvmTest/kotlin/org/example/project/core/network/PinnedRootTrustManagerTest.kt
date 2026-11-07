package org.example.project.core.network

import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PinnedRootTrustManagerTest {
    private fun pem(name: String): String = checkNotNull(javaClass.getResource("/pairing-pin/$name")) { "missing fixture $name" }.readText()

    private fun cert(name: String): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(pem(name).toByteArray())) as X509Certificate

    /** Stands in for the OS / platform store: it knows one unrelated root, nothing of the shop's CA. */
    private val emptyBase: X509TrustManager =
        TrustManagerFactory
            .getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply {
                init(
                    KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                        load(null, null)
                        setCertificateEntry("unrelated", cert("otherroot.pem"))
                    },
                )
            }.trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()

    private val manager = PinnedRootTrustManager { emptyBase }

    @AfterTest
    fun clearPin() = PinnedRootTrust.set(null)

    @Test
    fun a_chain_under_an_unknown_root_is_refused_without_a_pin() {
        assertFailsWith<CertificateException> { manager.checkServerTrusted(arrayOf(cert("leaf.pem"), cert("int.pem")), "UNKNOWN") }
    }

    @Test
    fun leaf_and_intermediate_are_accepted_once_their_root_is_pinned() {
        PinnedRootTrust.set(pem("root.pem"))
        manager.checkServerTrusted(arrayOf(cert("leaf.pem"), cert("int.pem")), "UNKNOWN")
    }

    @Test
    fun a_chain_that_also_carries_the_root_is_accepted() {
        PinnedRootTrust.set(pem("root.pem"))
        manager.checkServerTrusted(arrayOf(cert("leaf.pem"), cert("int.pem"), cert("root.pem")), "UNKNOWN")
    }

    @Test
    fun a_different_pinned_root_does_not_vouch_for_the_chain() {
        PinnedRootTrust.set(pem("otherroot.pem"))
        assertFailsWith<CertificateException> { manager.checkServerTrusted(arrayOf(cert("leaf.pem"), cert("int.pem")), "UNKNOWN") }
    }

    @Test
    fun repinning_replaces_the_earlier_root() {
        PinnedRootTrust.set(pem("root.pem"))
        manager.checkServerTrusted(arrayOf(cert("leaf.pem"), cert("int.pem")), "UNKNOWN")
        PinnedRootTrust.set(pem("otherroot.pem"))
        assertFailsWith<CertificateException> { manager.checkServerTrusted(arrayOf(cert("leaf.pem"), cert("int.pem")), "UNKNOWN") }
    }

    @Test
    fun the_leaf_alone_is_not_enough_because_the_intermediate_is_missing() {
        PinnedRootTrust.set(pem("root.pem"))
        assertFailsWith<CertificateException> { manager.checkServerTrusted(arrayOf(cert("leaf.pem")), "UNKNOWN") }
    }

    @Test
    fun the_refusal_keeps_the_original_reason() {
        PinnedRootTrust.set(pem("otherroot.pem"))
        val e = assertFailsWith<CertificateException> { manager.checkServerTrusted(arrayOf(cert("leaf.pem"), cert("int.pem")), "UNKNOWN") }
        assertTrue(e.suppressed.isNotEmpty(), "the pin failure is attached to the original refusal")
    }

    @Test
    fun the_pinned_root_is_listed_among_the_accepted_issuers() {
        PinnedRootTrust.set(pem("root.pem"))
        assertTrue(manager.acceptedIssuers.contains(cert("root.pem")))
    }

    @Test
    fun a_garbage_pin_is_ignored() {
        PinnedRootTrust.set("not a certificate")
        assertFailsWith<CertificateException> { manager.checkServerTrusted(arrayOf(cert("leaf.pem"), cert("int.pem")), "UNKNOWN") }
    }
}
