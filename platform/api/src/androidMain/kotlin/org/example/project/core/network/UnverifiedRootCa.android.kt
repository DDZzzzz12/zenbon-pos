package org.example.project.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

actual val isPinningSupported: Boolean = true

private const val ROOT_CA_PATH = "api/v1/terminals/root-ca"
private const val MAX_BODY_BYTES = 4096L
private const val TIMEOUT_SECONDS = 5L

actual suspend fun fetchUnverifiedRootCa(baseUrl: String): String =
    withContext(Dispatchers.IO) {
        if (!baseUrl.startsWith("https://", ignoreCase = true)) throw RootCaFetchException("The server address must be https://")
        try {
            unverifiedClient()
                .newCall(
                    Request
                        .Builder()
                        .url(baseUrl.trimEnd('/') + "/" + ROOT_CA_PATH)
                        .get()
                        .build(),
                ).execute()
                .use { response ->
                    if (response.code != 200) throw RootCaFetchException("The server answered ${response.code}")
                    val body = response.body ?: throw RootCaFetchException("Empty answer")
                    val bytes =
                        body
                            .source()
                            .apply { request(MAX_BODY_BYTES + 1) }
                            .buffer
                            .readByteArray()
                    if (bytes.size > MAX_BODY_BYTES) throw RootCaFetchException("Answer too large for a certificate")
                    bytes.decodeToString()
                }
        } catch (e: IOException) {
            throw RootCaFetchException("Could not reach the server", e)
        }
    }

/** A client that accepts any certificate. Private to this file; its answer is verified by the caller. */
@Suppress("CustomX509TrustManager", "TrustAllX509TrustManager")
private fun unverifiedClient(): OkHttpClient {
    val acceptAll =
        object : X509TrustManager {
            override fun checkClientTrusted(
                chain: Array<X509Certificate>,
                authType: String,
            ) = Unit

            override fun checkServerTrusted(
                chain: Array<X509Certificate>,
                authType: String,
            ) = Unit

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(acceptAll), null) }
    return OkHttpClient
        .Builder()
        .sslSocketFactory(context.socketFactory, acceptAll)
        .hostnameVerifier { _, _ -> true }
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
}
