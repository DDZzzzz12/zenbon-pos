package org.example.project.features.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.pluginOrNull
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.writeByte
import io.ktor.utils.io.writer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.example.project.core.error.PosErrorCode
import org.example.project.features.update.data.remote.GithubReleaseClient
import org.example.project.features.update.domain.models.ByteRange
import org.example.project.infrastructure.errors.PosException
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GithubAssetDownloadTest {
    @Test
    fun `follows the redirect and does NOT forward the Authorization header`() =
        runTest {
            val seenAuthPerHost = mutableMapOf<String, String?>()
            val engine =
                MockEngine { request ->
                    seenAuthPerHost[request.url.host] = request.headers[HttpHeaders.Authorization]
                    when (request.url.host) {
                        "api.github.com" ->
                            respond(
                                "",
                                HttpStatusCode.Found,
                                headersOf(HttpHeaders.Location, "https://objects.example.com/blob"),
                            )
                        else -> respond(byteArrayOf(1, 2, 3), HttpStatusCode.OK)
                    }
                }
            val bytes =
                GithubReleaseClient(HttpClient(engine))
                    .downloadAsset(REPO, token = "github_pat_x", assetId = 42)

            assertContentEquals(byteArrayOf(1, 2, 3), bytes)
            assertEquals("Bearer github_pat_x", seenAuthPerHost["api.github.com"])
            assertNull(
                seenAuthPerHost["objects.example.com"],
                "storage rejects a request carrying both its signature and our Authorization header",
            )
        }

    @Test
    fun `resolves a relative Location against the URL that answered`() =
        runTest {
            val seen = mutableListOf<String>()
            val engine =
                MockEngine { request ->
                    seen += request.url.toString()
                    if (request.url.encodedPath.startsWith("/storage/")) {
                        respond(byteArrayOf(7), HttpStatusCode.OK)
                    } else {
                        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/storage/42"))
                    }
                }
            val bytes = GithubReleaseClient(HttpClient(engine)).downloadAsset(REPO, token = null, assetId = 42)

            assertContentEquals(byteArrayOf(7), bytes)
            assertTrue(seen.last().startsWith("https://api.github.com/storage/42"), seen.last())
        }

    @Test
    fun `returns the body directly when there is no redirect`() =
        runTest {
            val bytes =
                GithubReleaseClient(
                    HttpClient(
                        MockEngine {
                            respond(byteArrayOf(9, 9), HttpStatusCode.OK)
                        },
                    ),
                ).downloadAsset(REPO, token = null, assetId = 42)
            assertContentEquals(byteArrayOf(9, 9), bytes)
        }

    /**
     * A redirect with no `Location` leaves nowhere to go. Returning the (empty) redirect body would
     * hand the caller a zero-byte artifact that is then staged and applied.
     */
    @Test
    fun `a redirect without a Location header fails instead of returning empty bytes`() =
        runTest {
            val sut =
                GithubReleaseClient(
                    HttpClient(MockEngine { respond("", HttpStatusCode.Found) }),
                )
            assertFailsWith<PosException> { sut.downloadAsset(REPO, token = null, assetId = 42) }
            Unit
        }

    /**
     * The manual hop only happens when the client does not follow redirects itself. Ktor's own
     * `HttpRedirect` also drops `Authorization` across hosts, so the download tests above pass
     * either way -- this is the test that actually fails if the guard is removed.
     */
    @Test
    fun `strips automatic redirect following from whatever client is injected`() {
        val followsRedirects = HttpClient(MockEngine { respond("", HttpStatusCode.OK) })
        assertNotNull(followsRedirects.pluginOrNull(HttpRedirect), "precondition: the injected client follows redirects")

        val sut = GithubReleaseClient(followsRedirects)

        assertNull(sut.noRedirect.pluginOrNull(HttpRedirect))
    }

    /** The production factory must carry the same contract, not only the client a test injects. */
    @Test
    fun `the production http client does not follow redirects`() {
        GithubReleaseClient.defaultHttpClient().use { client ->
            assertNull(client.pluginOrNull(HttpRedirect))
        }
    }

    /**
     * The credential guard, asserted where it actually bites.
     *
     * [GithubReleaseClient.resolveAssetUrl] walks the redirect by hand, so a missing host check
     * would attach the bearer token to the *storage* hop. This asserts that **no** request off
     * `api.github.com` carries an `Authorization` header -- checking only the last request would
     * prove nothing, because [GithubReleaseClient.downloadRange] takes no token at all.
     */
    @Test
    fun `a range request drops the credential on the hop to storage`() =
        runTest {
            val seen = mutableListOf<SeenRequest>()
            val engine =
                MockEngine { request ->
                    seen +=
                        SeenRequest(
                            request.url.host,
                            request.headers[HttpHeaders.Authorization],
                            request.headers[HttpHeaders.Range],
                        )
                    if (request.url.host == "api.github.com") {
                        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://storage.example/blob"))
                    } else {
                        respond(
                            "0123456789".toByteArray(),
                            HttpStatusCode.PartialContent,
                            headersOf(HttpHeaders.ContentRange, "bytes 0-9/1000"),
                        )
                    }
                }
            val client = GithubReleaseClient(HttpClient(engine))

            val url = client.resolveAssetUrl(REPO, "tok", 7L)
            val bytes = client.downloadRange(url, ByteRange(0, 10))

            assertEquals("https://storage.example/blob", url)
            assertEquals(10, bytes.size)
            assertEquals("Bearer tok" to true, seen.first().auth to (seen.first().host == "api.github.com"))
            val leaked = seen.filter { it.host != "api.github.com" && it.auth != null }
            assertTrue(leaked.isEmpty(), "credential forwarded to storage on $leaked")
        }

    /**
     * The one-byte probe is the entire justification for resolution sharing the download's walk:
     * without it, learning the storage URL would drag the whole ~127 MB asset over the wire.
     *
     * Nothing else asserts it, so deleting `Range: bytes=0-0` from the walk leaves every other
     * test in this class green.
     */
    @Test
    fun `resolving probes for a single byte instead of fetching the asset`() =
        runTest {
            val seen = mutableListOf<SeenRequest>()
            val engine =
                MockEngine { request ->
                    seen += SeenRequest(request.url.host, null, request.headers[HttpHeaders.Range])
                    if (request.url.host == "api.github.com") {
                        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://storage.example/blob"))
                    } else {
                        respond(byteArrayOf(0), HttpStatusCode.PartialContent, headersOf(HttpHeaders.ContentRange, "bytes 0-0/1000"))
                    }
                }

            GithubReleaseClient(HttpClient(engine)).resolveAssetUrl(REPO, token = null, assetId = 7)

            assertEquals(2, seen.size)
            assertTrue(seen.all { it.range == "bytes=0-0" }, "every hop of the walk must probe, not download: $seen")
        }

    /**
     * A `200` to the probe -- a storage server that ignores `Range` -- must not be buffered.
     *
     * ktor's plain `get()` saves the whole body before the status can even be inspected, so that
     * answer would pull the entire ~127 MB asset into memory to learn a URL. The walk streams
     * instead, and resolution never touches the body. The body here fails as soon as anything
     * reads past its first byte, so buffering shows up as that failure rather than as a URL.
     */
    @Test
    fun `resolving abandons the response body instead of buffering it`() =
        runTest {
            val bodies = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val engine =
                MockEngine {
                    val exploding =
                        bodies
                            .writer {
                                channel.writeByte(1)
                                channel.flush()
                                error("the probe read the asset body instead of abandoning it")
                            }.channel
                    respond(exploding, HttpStatusCode.OK)
                }

            val url = GithubReleaseClient(HttpClient(engine)).resolveAssetUrl(REPO, token = null, assetId = 42)

            assertEquals("https://api.github.com/repos/DDZzzzz12/POS-KMP/releases/assets/42", url)
            bodies.cancel()
        }

    /**
     * ...and the download must **not** inherit that probe. A `Range` on this walk would return a
     * single byte, which is then staged and applied as if it were the release archive.
     */
    @Test
    fun `downloading an asset sends no Range header`() =
        runTest {
            val seen = mutableListOf<SeenRequest>()
            val engine =
                MockEngine { request ->
                    seen += SeenRequest(request.url.host, null, request.headers[HttpHeaders.Range])
                    if (request.url.host == "api.github.com") {
                        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://storage.example/blob"))
                    } else {
                        respond(byteArrayOf(1, 2, 3), HttpStatusCode.OK)
                    }
                }

            val bytes = GithubReleaseClient(HttpClient(engine)).downloadAsset(REPO, token = null, assetId = 7)

            assertContentEquals(byteArrayOf(1, 2, 3), bytes)
            assertEquals(2, seen.size)
            val ranged = seen.filter { it.range != null }
            assertTrue(ranged.isEmpty(), "a ranged download would stage one byte as the release archive: $ranged")
        }

    /**
     * A server that ignores `Range` answers `200` with the entire ~127 MB body. Accepting it would
     * turn a 2 MB delta into a full download held in memory, and write the bytes at wrong offsets.
     */
    @Test
    fun `a 200 answer to a range request is refused`() =
        runTest {
            val engine = MockEngine { respond("the whole file".toByteArray(), HttpStatusCode.OK) }
            val client = GithubReleaseClient(HttpClient(engine))

            val e =
                assertFailsWith<PosException> {
                    client.downloadRange("https://storage.example/blob", ByteRange(0, 10))
                }
            // Names the status *and* the expectation: asserting only "206" would also be
            // satisfied by the Content-Range complaint a 200 happens to trigger as well.
            assertTrue(e.message!!.contains("HTTP 200"), e.message!!)
            assertTrue(e.message!!.contains("expected 206"), e.message!!)
        }

    /** A truncated body would make the reconstruction quietly wrong. */
    @Test
    fun `a short range answer is refused`() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        "012".toByteArray(),
                        HttpStatusCode.PartialContent,
                        headersOf(HttpHeaders.ContentRange, "bytes 0-9/1000"),
                    )
                }
            val client = GithubReleaseClient(HttpClient(engine))

            val e =
                assertFailsWith<PosException> {
                    client.downloadRange("https://storage.example/blob", ByteRange(0, 10))
                }
            assertTrue(e.message!!.contains("expected 10"), e.message!!)
        }

    /**
     * A proxy serving a *different* span of the right length would corrupt the reconstruction
     * silently -- only the final SHA-256 would catch it, i.e. a wasted download instead of an error.
     */
    @Test
    fun `a correctly sized answer for the wrong offset is refused`() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        "0123456789".toByteArray(),
                        HttpStatusCode.PartialContent,
                        headersOf(HttpHeaders.ContentRange, "bytes 500-509/1000"),
                    )
                }
            val client = GithubReleaseClient(HttpClient(engine))

            val e =
                assertFailsWith<PosException> {
                    client.downloadRange("https://storage.example/blob", ByteRange(0, 10))
                }
            assertTrue(e.message!!.contains("bytes 500-509"), e.message!!)
        }

    /** A 206 without `Content-Range` is malformed; there is nothing to verify the span against. */
    @Test
    fun `a 206 without a Content-Range header is refused`() =
        runTest {
            val engine = MockEngine { respond("0123456789".toByteArray(), HttpStatusCode.PartialContent) }
            val client = GithubReleaseClient(HttpClient(engine))

            val e =
                assertFailsWith<PosException> {
                    client.downloadRange("https://storage.example/blob", ByteRange(0, 10))
                }
            assertTrue(e.message!!.contains("Content-Range"), e.message!!)
        }

    /**
     * Signed storage URLs expire, and re-resolving is the caller's decision, so expiry is a
     * **type**: matching `message.contains("expired")` would make the wording of a log line part
     * of the contract. The message still has to name the cause for the operator reading the log.
     */
    @Test
    fun `an expired signed url is reported as its own type`() =
        runTest {
            val engine = MockEngine { respond("expired", HttpStatusCode.Forbidden) }
            val client = GithubReleaseClient(HttpClient(engine))

            val e =
                assertFailsWith<GithubReleaseClient.SignedUrlExpired> {
                    client.downloadRange("https://storage.example/blob", ByteRange(0, 10))
                }
            assertTrue(e.message!!.contains("expired"), e.message!!)
            // Same code and status as any other download failure -- the subtype must not change
            // how the error is reported, only let the caller branch on it.
            assertEquals(PosErrorCode.UPDATE_DOWNLOAD_FAILED, e.code)
            assertEquals(HttpStatusCode.BadGateway, e.httpStatus)
        }

    /** A 401 is the same expiry, seen from a storage backend that answers it differently. */
    @Test
    fun `a 401 from storage is an expiry too`() =
        runTest {
            val client = GithubReleaseClient(HttpClient(MockEngine { respond("", HttpStatusCode.Unauthorized) }))

            assertFailsWith<GithubReleaseClient.SignedUrlExpired> {
                client.downloadRange("https://storage.example/blob", ByteRange(0, 10))
            }
            Unit
        }

    /**
     * A malformed span never reaches the wire, because [ByteRange] refuses to exist.
     *
     * The guard used to sit in `downloadRange`; it belongs to the type, so every producer is
     * covered rather than only this consumer. This asserts the consequence that matters here --
     * no request is made.
     */
    @Test
    fun `a malformed range is refused before any request is made`() =
        runTest {
            var requests = 0
            val engine =
                MockEngine {
                    requests++
                    respond("", HttpStatusCode.PartialContent)
                }
            val client = GithubReleaseClient(HttpClient(engine))

            assertFailsWith<IllegalArgumentException> {
                client.downloadRange("https://storage.example/blob", ByteRange(5, 5))
            }
            assertFailsWith<IllegalArgumentException> {
                client.downloadRange("https://storage.example/blob", ByteRange(-1, 10))
            }
            assertEquals(0, requests)
        }

    /**
     * With nothing to redirect to, resolution reports the API URL it started from -- it does not
     * invent a storage host or fail. (This pins that URL; it does not exercise `downloadAsset`.)
     */
    @Test
    fun `resolveAssetUrl returns the api url when nothing redirects`() =
        runTest {
            val client = GithubReleaseClient(HttpClient(MockEngine { respond(byteArrayOf(1), HttpStatusCode.OK) }))
            assertEquals(
                "https://api.github.com/repos/DDZzzzz12/POS-KMP/releases/assets/42",
                client.resolveAssetUrl(REPO, token = null, assetId = 42),
            )
        }

    /**
     * The streaming counterpart of the first test: the same redirect walk, the same credential
     * rule, and the digest computed while the bytes are written rather than by reading ~127 MB
     * back off disk.
     */
    @Test
    fun `downloadAssetTo streams to a file, returns its digest and drops the credential at storage`() =
        runTest {
            val payload = ByteArray(200_000) { (it % 251).toByte() }
            val seenAuthPerHost = mutableMapOf<String, String?>()
            val engine =
                MockEngine { request ->
                    seenAuthPerHost[request.url.host] = request.headers[HttpHeaders.Authorization]
                    when (request.url.host) {
                        "api.github.com" ->
                            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://objects.example.com/blob"))
                        else -> respond(payload, HttpStatusCode.OK)
                    }
                }
            val target = File.createTempFile("asset", ".jar").also { it.delete() }

            val digest =
                GithubReleaseClient(HttpClient(engine))
                    .downloadAssetTo(REPO, token = "github_pat_x", assetId = 42, target = target)

            assertContentEquals(payload, target.readBytes())
            assertEquals(
                MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) },
                digest,
            )
            assertEquals("Bearer github_pat_x", seenAuthPerHost["api.github.com"])
            assertNull(seenAuthPerHost["objects.example.com"])
            target.delete()
        }

    /**
     * A half-written jar must not survive a failed download: it is indistinguishable by name from
     * a finished one, and the caller's answer to any failure is to try again.
     */
    @Test
    fun `downloadAssetTo leaves no file behind when the download fails`() =
        runTest {
            val client = GithubReleaseClient(HttpClient(MockEngine { respond("nope", HttpStatusCode.NotFound) }))
            val target = File.createTempFile("asset", ".jar").also { it.writeText("debris") }

            assertFailsWith<PosException> { client.downloadAssetTo(REPO, token = null, assetId = 42, target = target) }

            assertFalse(target.exists(), "a failed download must not leave a file that looks finished")
        }

    /** One recorded request: enough to assert both the credential guard and the probe. */
    private data class SeenRequest(
        val host: String,
        val auth: String?,
        val range: String?,
    )

    private companion object {
        val REPO = GithubReleaseClient.Repo("https://api.github.com", "DDZzzzz12", "POS-KMP")
    }
}
