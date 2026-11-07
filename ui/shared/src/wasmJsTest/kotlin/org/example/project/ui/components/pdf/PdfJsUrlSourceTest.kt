@file:OptIn(ExperimentalWasmJsInterop::class)

package org.example.project.ui.components.pdf

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.khronos.webgl.Int8Array
import org.khronos.webgl.toInt8Array
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// A fetch stand-in: answers call i with statuses[i] (the last entry repeats), the body only on a 2xx, a
// rejected promise (a transport failure) for status 0, and for -1 a request that hangs until its signal aborts.
// It records each call's URL, Authorization header and whether it was aborted.
private fun stubFetchJs(
    statuses: String,
    body: Int8Array,
): JsAny =
    js(
        """(function () {
            var codes = statuses.split(',').map(Number);
            var calls = [];
            var stub = function (url, init) {
                var status = codes[Math.min(calls.length, codes.length - 1)];
                var call = { url: url, auth: (init && init.headers && init.headers['Authorization']) || null, aborted: false };
                calls.push(call);
                if (status === -1) {
                    return new Promise(function (resolve, reject) {
                        if (!init || !init.signal) return;
                        init.signal.addEventListener('abort', function () {
                            call.aborted = true;
                            reject(new DOMException('aborted', 'AbortError'));
                        });
                    });
                }
                if (status === 0) return Promise.reject(new TypeError('Failed to fetch'));
                var ok = status >= 200 && status < 300;
                return Promise.resolve(new Response(ok ? body.slice() : null, { status: status }));
            };
            stub.calls = calls;
            return stub;
        })()""",
    )

private fun callCountJs(stub: JsAny): Int = js("stub.calls.length")

private fun authOfCallJs(
    stub: JsAny,
    index: Int,
): String? = js("stub.calls[index].auth")

private fun abortedJs(
    stub: JsAny,
    index: Int,
): Boolean = js("stub.calls[index].aborted")

private fun pageOriginJs(): String = js("globalThis.location.origin")

private fun urlOfCallJs(
    stub: JsAny,
    index: Int,
): String = js("stub.calls[index].url")

/** [PdfSource.Url] on wasm: the JS fetch, its single auth point, the same-origin guard, the 401 retry, and error mapping. */
class PdfJsUrlSourceTest {
    private class RecordingTokenProvider : PdfTokenProvider {
        val requests = mutableListOf<Boolean>()

        override suspend fun token(forceRefresh: Boolean): String? {
            requests += forceRefresh
            return if (forceRefresh) "fresh" else "stale"
        }
    }

    private suspend fun openError(
        stub: JsAny,
        url: String = URL,
        tokens: PdfTokenProvider = RecordingTokenProvider(),
    ): PdfLoadError {
        val renderer = PdfJsPageRenderer(PdfJsFetch(stub))
        try {
            renderer.open(PdfSource.Url(url, tokens))
        } catch (e: PdfLoadException) {
            return e.error
        } finally {
            renderer.close()
        }
        error("open() should have thrown PdfLoadException")
    }

    @Test
    fun unauthorizedThenOkRetriesOnceWithTheRefreshedToken() =
        runTest {
            val stub = stubFetchJs("401,200", PdfFixtures.twoPage.toInt8Array())
            val tokens = RecordingTokenProvider()
            val renderer = PdfJsPageRenderer(PdfJsFetch(stub))
            try {
                val info = renderer.open(PdfSource.Url(URL, tokens))
                assertEquals(2, info.pages.size)
                assertEquals(600, renderer.render(0, 600).width)
            } finally {
                renderer.close()
            }
            assertEquals(2, callCountJs(stub), "exactly one retry")
            assertEquals("Bearer stale", authOfCallJs(stub, 0))
            assertEquals("Bearer fresh", authOfCallJs(stub, 1))
            assertEquals(URL, urlOfCallJs(stub, 1))
            assertEquals(listOf(false, true), tokens.requests)
        }

    @Test
    fun okOnFirstTryDoesNotRefresh() =
        runTest {
            val stub = stubFetchJs("200", PdfFixtures.twoPage.toInt8Array())
            val tokens = RecordingTokenProvider()
            val renderer = PdfJsPageRenderer(PdfJsFetch(stub))
            try {
                assertEquals(2, renderer.open(PdfSource.Url(URL, tokens)).pages.size)
            } finally {
                renderer.close()
            }
            assertEquals(1, callCountJs(stub))
            assertEquals(listOf(false), tokens.requests)
        }

    @Test
    fun unauthorizedTwiceIsUnauthorizedAfterExactlyOneRetry() =
        runTest {
            val stub = stubFetchJs("401,401,200", PdfFixtures.twoPage.toInt8Array())
            assertEquals(PdfLoadError.Unauthorized, openError(stub))
            assertEquals(2, callCountJs(stub))
        }

    @Test
    fun noRefreshedTokenIsUnauthorizedWithoutARetry() =
        runTest {
            val stub = stubFetchJs("401,200", PdfFixtures.twoPage.toInt8Array())
            assertEquals(PdfLoadError.Unauthorized, openError(stub) { forceRefresh -> if (forceRefresh) null else "stale" })
            assertEquals(1, callCountJs(stub))
        }

    @Test
    fun missingTokenSendsNoAuthorizationHeader() =
        runTest {
            val stub = stubFetchJs("200", PdfFixtures.twoPage.toInt8Array())
            val renderer = PdfJsPageRenderer(PdfJsFetch(stub))
            try {
                renderer.open(PdfSource.Url(URL) { null })
            } finally {
                renderer.close()
            }
            assertEquals(null, authOfCallJs(stub, 0))
        }

    @Test
    fun serverErrorIsNetwork() =
        runTest {
            val stub = stubFetchJs("500", PdfFixtures.twoPage.toInt8Array())
            assertEquals(PdfLoadError.Network, openError(stub))
            assertEquals(1, callCountJs(stub))
        }

    @Test
    fun forbiddenIsForbiddenWithoutARefresh() =
        runTest {
            val stub = stubFetchJs("403,200", PdfFixtures.twoPage.toInt8Array())
            val tokens = RecordingTokenProvider()
            assertEquals(PdfLoadError.Forbidden, openError(stub, tokens = tokens))
            assertEquals(1, callCountJs(stub), "a 403 is a decision, not an expired token: no retry")
            assertEquals(listOf(false), tokens.requests, "a 403 must not ask for a refreshed token")
        }

    @Test
    fun forbiddenAfterARefreshIsForbidden() =
        runTest {
            val stub = stubFetchJs("401,403,200", PdfFixtures.twoPage.toInt8Array())
            assertEquals(PdfLoadError.Forbidden, openError(stub))
            assertEquals(2, callCountJs(stub), "the 401 is retried once; the 403 that answers the retry is final")
        }

    @Test
    fun notFoundIsNotFound() =
        runTest {
            val stub = stubFetchJs("404", PdfFixtures.twoPage.toInt8Array())
            assertEquals(PdfLoadError.NotFound, openError(stub))
            assertEquals(1, callCountJs(stub))
        }

    @Test
    fun otherClientErrorIsNetwork() = runTest { assertEquals(PdfLoadError.Network, openError(stubFetchJs("400", PdfFixtures.twoPage.toInt8Array()))) }

    @Test
    fun crossOriginUrlIsRefusedWithoutFetchingOrReadingTheToken() =
        runTest {
            val stub = stubFetchJs("200", PdfFixtures.twoPage.toInt8Array())
            val tokens = RecordingTokenProvider()
            assertEquals(PdfLoadError.Unsupported, openError(stub, url = "https://elsewhere.example/api/documents/7/file", tokens = tokens))
            assertEquals(0, callCountJs(stub), "no request may leave for another origin")
            assertEquals(emptyList(), tokens.requests, "the token must not even be read for another origin")
        }

    @Test
    fun sameHostOnAnotherPortIsCrossOrigin() =
        runTest {
            val stub = stubFetchJs("200", PdfFixtures.twoPage.toInt8Array())
            val otherPort = pageOriginJs().replace(Regex(":\\d+$"), "") + ":1"
            assertEquals(PdfLoadError.Unsupported, openError(stub, url = "$otherPort/api/documents/7/file"))
            assertEquals(0, callCountJs(stub))
        }

    @Test
    fun malformedUrlIsRefused() =
        runTest {
            val stub = stubFetchJs("200", PdfFixtures.twoPage.toInt8Array())
            assertEquals(PdfLoadError.Unsupported, openError(stub, url = "http://[not-a-host/x.pdf"))
            assertEquals(0, callCountJs(stub))
        }

    @Test
    fun sameOriginAbsoluteUrlCarriesTheToken() =
        runTest {
            val stub = stubFetchJs("200", PdfFixtures.twoPage.toInt8Array())
            val absolute = pageOriginJs() + URL
            val renderer = PdfJsPageRenderer(PdfJsFetch(stub))
            try {
                assertEquals(2, renderer.open(PdfSource.Url(absolute, RecordingTokenProvider())).pages.size)
            } finally {
                renderer.close()
            }
            assertEquals(absolute, urlOfCallJs(stub, 0))
            assertEquals("Bearer stale", authOfCallJs(stub, 0))
        }

    @Test
    fun transportFailureIsNetwork() = runTest { assertEquals(PdfLoadError.Network, openError(stubFetchJs("0", PdfFixtures.twoPage.toInt8Array()))) }

    @Test
    fun nonPdfBodyIsNotPdf() = runTest { assertEquals(PdfLoadError.NotPdf, openError(stubFetchJs("200", PdfFixtures.notAPdf.toInt8Array()))) }

    @Test
    fun emptyBodyIsEmpty() = runTest { assertEquals(PdfLoadError.Empty, openError(stubFetchJs("200", ByteArray(0).toInt8Array()))) }

    @Test
    fun encryptedBodyIsPasswordProtected() =
        runTest { assertEquals(PdfLoadError.PasswordProtected, openError(stubFetchJs("200", PdfFixtures.encrypted.toInt8Array()))) }

    @Test
    fun readBytesFetchesTheDocumentAgain() =
        runTest {
            val stub = stubFetchJs("200", PdfFixtures.twoPage.toInt8Array())
            val renderer = PdfJsPageRenderer(PdfJsFetch(stub))
            try {
                renderer.open(PdfSource.Url(URL, RecordingTokenProvider()))
                assertContentEquals(PdfFixtures.twoPage, renderer.readBytes())
            } finally {
                renderer.close()
            }
            assertEquals(2, callCountJs(stub))
            assertEquals("Bearer stale", authOfCallJs(stub, 1))
        }

    @Test
    fun cancelledOpenAbortsTheFetch() =
        runTest {
            val stub = stubFetchJs("-1", PdfFixtures.twoPage.toInt8Array())
            val renderer = PdfJsPageRenderer(PdfJsFetch(stub))
            val opening = launch { renderer.open(PdfSource.Url(URL, RecordingTokenProvider())) }
            yield()
            assertEquals(1, callCountJs(stub), "open() should be suspended on the fetch")
            opening.cancelAndJoin()
            assertTrue(abortedJs(stub, 0), "the in-flight request must be aborted")
            renderer.close()
        }

    private companion object {
        /** Relative, so it resolves against the test page's own origin, as the app's same-origin API paths do. */
        const val URL = "/api/documents/7/file"
    }
}
