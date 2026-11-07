package org.example.project.core.network

import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** The Android TLS setup shared by every client: platform trust plus the root pinned at pairing. */
internal object PinnedTls {
    val trustManager: X509TrustManager =
        PinnedRootTrustManager {
            platformTrustManager
        }

    val socketFactory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }.socketFactory
    }

    private val platformTrustManager: X509TrustManager by lazy {
        TrustManagerFactory
            .getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as java.security.KeyStore?) }
            .trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()
    }
}
