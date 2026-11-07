package org.example.project.core.network.error

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException

/**
 * Executes a Ktor request, decodes a successful body to [T], or throws
 * [PosApiException] for non-2xx responses, [NetworkApiException] for transport
 * failures. [CancellationException] is rethrown.
 *
 * Usage:
 * ```
 * val foo: Foo = httpClient.apiCall { get("/api/foo") }
 * ```
 *
 * For VM-friendly Result-style consumption, wrap the call in [runCatchingApi]
 * and chain `.onError { _error.value = it }` (see `RunCatchingApi.kt`).
 */
suspend inline fun <reified T> HttpClient.apiCall(crossinline block: suspend HttpClient.() -> HttpResponse): T = apiCallRaw(block).body()

/**
 * Like [apiCall] but returns the successful [HttpResponse] itself, for callers
 * that need response headers (e.g. Content-Disposition filenames) in addition
 * to the body. Same error mapping as [apiCall].
 */
suspend inline fun HttpClient.apiCallRaw(crossinline block: suspend HttpClient.() -> HttpResponse): HttpResponse {
    val resp: HttpResponse =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw NetworkApiException(e)
        }
    if (resp.status.isSuccess()) return resp
    throw PosApiExceptionDecoder.decode(resp)
}
