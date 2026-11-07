package org.example.project.features.terminal.data

import org.example.project.core.data.database.LocalDatabaseManager
import org.example.project.core.network.RootCaFetchException
import org.example.project.core.network.fetchUnverifiedRootCa
import org.example.project.core.utils.RootFingerprint
import org.example.project.core.utils.sha256
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Why a root could not be pinned. */
enum class PinFailure { CODE_INVALID, FETCH_FAILED, MISMATCH }

/**
 * Pins a server's local-CA root certificate from a check code handed over out of band.
 *
 * The root is downloaded over a connection whose certificate is not validated, so the download is
 * authenticated only by [RootFingerprint]: it is accepted when it hashes to the given code, which a
 * man-in-the-middle cannot satisfy. Only then is it stored ([LocalDatabaseManager.setPinnedRootPem])
 * and published for every TLS handshake. Nothing is stored on any failure.
 */
@OptIn(ExperimentalEncodingApi::class)
class RootCaPinner(
    private val localDb: LocalDatabaseManager,
    private val fetch: suspend (baseUrl: String) -> String = ::fetchUnverifiedRootCa,
) {
    /** @return null when [rootCode] verified and the root is now pinned, else why not. */
    suspend fun pin(
        baseUrl: String,
        rootCode: String,
    ): PinFailure? {
        val code = RootFingerprint.normalize(rootCode) ?: return PinFailure.CODE_INVALID
        val pem =
            try {
                fetch(baseUrl)
            } catch (e: RootCaFetchException) {
                return PinFailure.FETCH_FAILED
            }
        val der = singleCertificateDer(pem) ?: return PinFailure.MISMATCH
        if (!RootFingerprint.matches(code, sha256(der))) return PinFailure.MISMATCH
        localDb.setPinnedRootPem(toPem(der))
        return null
    }

    private fun singleCertificateDer(pem: String): ByteArray? {
        val blocks = PEM_BLOCK.findAll(pem).toList()
        if (blocks.size != 1) return null
        return runCatching { Base64.Mime.decode(blocks.single().groupValues[1]) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun toPem(der: ByteArray): String = "-----BEGIN CERTIFICATE-----\n" + Base64.Mime.encode(der) + "\n-----END CERTIFICATE-----\n"

    private companion object {
        val PEM_BLOCK = Regex("-----BEGIN CERTIFICATE-----([A-Za-z0-9+/=\\s]+)-----END CERTIFICATE-----")
    }
}
