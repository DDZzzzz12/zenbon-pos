package org.example.project.admin.core.util.pdf

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import org.example.project.core.auth.TokenStore
import org.example.project.core.data.remote.AuthApiClient
import org.example.project.ui.components.pdf.PdfTokenProvider

/**
 * The bearer token for the shared `PdfViewer`'s `PdfSource.Url` fetch, which runs outside the app's
 * Ktor client (PDF.js streams the file through the browser's own `fetch`).
 *
 * `token(false)` is the stored access token. `token(true)` — asked once after a 401 — renews it
 * through the **same** refresh the app's HTTP client performs, by sending the cheap authenticated
 * [AuthApiClient.validateToken] through that client: its `Auth` plugin answers the 401 with its one
 * `/api/auth/refresh`, which rotates the stored tokens, coalesces with any refresh already in flight
 * (a second, hand-written refresh would race it and burn the single-use refresh token), and signs the
 * operator out when the server rejects the refresh — exactly as on every other request. The token is
 * then re-read from [TokenStore]. Null means there is no session or no fresh token was obtained.
 *
 * Never logs a token.
 */
class AdminPdfTokenProvider(
    private val tokenStore: TokenStore,
    private val authApi: AuthApiClient,
) : PdfTokenProvider {
    private val log = Logger.withTag("pdfToken")

    override suspend fun token(forceRefresh: Boolean): String? {
        if (!forceRefresh) return tokenStore.accessToken
        if (tokenStore.accessToken == null) {
            log.i { "no session: nothing to refresh for the PDF fetch" }
            return null
        }
        return try {
            authApi.validateToken()
            log.d { "session renewed for the PDF fetch" }
            tokenStore.accessToken
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "no fresh token for the PDF fetch" }
            null
        }
    }
}
