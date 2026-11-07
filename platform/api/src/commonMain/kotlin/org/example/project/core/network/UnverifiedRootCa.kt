package org.example.project.core.network

/** Whether this platform can pin a server's root certificate (false in the browser, which owns TLS trust). */
expect val isPinningSupported: Boolean

/**
 * Downloads the server's root certificate (`GET {baseUrl}api/v1/terminals/root-ca`) over a TLS
 * connection whose certificate is NOT validated, and returns its PEM.
 *
 * The caller must check the result against a check code obtained out of band before trusting it —
 * that comparison is what authenticates the download. This is the only place the app skips
 * certificate validation: https only, no redirects, short timeouts, at most 4 KiB of body, and the
 * connection is never reused or shared with an authenticated client.
 *
 * @throws RootCaFetchException when the server cannot be reached or does not answer with a certificate.
 */
expect suspend fun fetchUnverifiedRootCa(baseUrl: String): String

class RootCaFetchException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
