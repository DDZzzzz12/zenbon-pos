@file:OptIn(ExperimentalWasmJsInterop::class)

package org.example.project.ui.components.pdf

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import org.khronos.webgl.ArrayBuffer
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise

private fun globalFetchJs(): JsAny = js("globalThis.fetch.bind(globalThis)")

// THE ONLY place a credential is attached to a PDF request. Bearer header only: nothing is cookie-based.
private fun authorizedFetchJs(
    fetchFn: JsAny,
    url: String,
    token: String?,
    signal: JsAny,
): Promise<JsAny?> = js("fetchFn(url, { headers: token ? { 'Authorization': 'Bearer ' + token } : {}, signal: signal })")

private fun pageOriginJs(): String? = js("(globalThis.location && globalThis.location.origin) || null")

// The origin [url] resolves to against [base]; null for a URL the browser cannot parse.
private fun originOfJs(
    url: String,
    base: String,
): String? = js("(function () { try { return new URL(url, base).origin; } catch (e) { return null; } })()")

private fun newAbortControllerJs(): JsAny = js("new AbortController()")

private fun signalOfJs(controller: JsAny): JsAny = js("controller.signal")

private fun abortJs(controller: JsAny): Unit = js("{ controller.abort(); }")

private fun statusJs(response: JsAny): Int = js("response.status")

private fun arrayBufferJs(response: JsAny): Promise<JsAny?> = js("response.arrayBuffer()")

/**
 * Fetches a [PdfSource.Url] in JS straight into an `ArrayBuffer`, so the document never becomes a Kotlin
 * `ByteArray` (a per-byte copy on wasm).
 *
 * Auth: the current token goes out as `Authorization: Bearer`. On a 401 the provider is asked once for a
 * refreshed token (`forceRefresh = true`) and the request is retried once; a second 401 — or no refreshed
 * token to retry with — is [PdfLoadError.Unauthorized]. A 403 is [PdfLoadError.Forbidden] straight away and is not
 * retryable: the server recognised the token and refused this user the document, so a refreshed one would be
 * refused too. A 404 is
 * [PdfLoadError.NotFound]. Any other non-2xx status or a transport failure is [PdfLoadError.Network].
 *
 * Same origin only: a URL that resolves to another origin than the page's (scheme, host and port), or that
 * cannot be parsed, fails with [PdfLoadError.Unsupported] before the token is read and before any request is
 * made, so the bearer can never travel to a host a document URL happens to name. Relative URLs resolve against
 * the page and are always same-origin.
 *
 * @param fetchFn the JS `fetch` to call; a test seam, the browser's own by default.
 * @param pageOrigin the origin the token belongs to; the page's own (`location.origin`) by default.
 */
internal class PdfJsFetch(
    private val fetchFn: JsAny = globalFetchJs(),
    private val pageOrigin: String? = pageOriginJs(),
) {
    /** A cancelled caller aborts the request (and body download) in flight instead of letting it run on. */
    suspend fun fetch(source: PdfSource.Url): ArrayBuffer {
        val controller = newAbortControllerJs()
        try {
            return fetch(source, signalOfJs(controller))
        } catch (e: CancellationException) {
            abortJs(controller)
            throw e
        }
    }

    private suspend fun fetch(
        source: PdfSource.Url,
        signal: JsAny,
    ): ArrayBuffer {
        if (!isSameOrigin(source.url)) throw PdfLoadException(PdfLoadError.Unsupported)
        val first = request(source.url, source.tokenProvider.token(forceRefresh = false), signal)
        val response =
            if (statusJs(first) == HTTP_UNAUTHORIZED) {
                val refreshed = source.tokenProvider.token(forceRefresh = true) ?: throw PdfLoadException(PdfLoadError.Unauthorized)
                request(source.url, refreshed, signal)
            } else {
                first
            }
        failureFor(statusJs(response))?.let { throw PdfLoadException(it) }
        return networkOrThrow { arrayBufferJs(response).await<JsAny?>() } as ArrayBuffer
    }

    private fun isSameOrigin(url: String): Boolean {
        val origin = pageOrigin ?: return false
        return originOfJs(url, origin) == origin
    }

    /** The error a final response [status] means, or null for a success. */
    private fun failureFor(status: Int): PdfLoadError? =
        when (status) {
            in HTTP_OK_RANGE -> null
            HTTP_UNAUTHORIZED -> PdfLoadError.Unauthorized
            HTTP_FORBIDDEN -> PdfLoadError.Forbidden
            HTTP_NOT_FOUND -> PdfLoadError.NotFound
            else -> PdfLoadError.Network
        }

    private suspend fun request(
        url: String,
        token: String?,
        signal: JsAny,
    ): JsAny = networkOrThrow { authorizedFetchJs(fetchFn, url, token, signal).await<JsAny?>() }!!

    private inline fun <T> networkOrThrow(block: () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw PdfLoadException(PdfLoadError.Network, e)
        }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
        val HTTP_OK_RANGE = 200..299
    }
}
