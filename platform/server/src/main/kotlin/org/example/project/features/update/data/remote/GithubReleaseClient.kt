package org.example.project.features.update.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import org.example.project.core.error.PosErrorCode
import org.example.project.core.utils.SemanticVersion
import org.example.project.features.update.domain.models.AvailableUpdate
import org.example.project.features.update.domain.models.ByteRange
import org.example.project.features.update.domain.models.ReleaseLookup
import org.example.project.infrastructure.errors.PosException
import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest

/**
 * Reads releases from GitHub. It knows nothing about updating -- one repository in, a typed
 * result out.
 *
 * Three GitHub facts shape it:
 * 1. An unauthenticated request to a **private** repository answers 404, not 403, so a missing or
 *    expired token looks exactly like "no releases". [latest] therefore reports a 404 seen *with*
 *    a token as [ReleaseLookup.AuthFailed] -- an expired PAT must never degrade to silence.
 * 2. `browser_download_url` accepts no credential on a private repository; the asset API
 *    (`/releases/assets/{id}`) is the only path that works for both channels.
 * 3. That asset API 302-redirects to signed object storage, **which rejects the request if our
 *    `Authorization` header is forwarded**. Both [downloadAsset] and [resolveAssetUrl] therefore
 *    go through one private redirect walk that follows the hops by hand and attaches the
 *    credential only while the request is still aimed at `api.github.com`. Neither calls the
 *    other: they differ solely in what they take from the response the walk ends on -- the bytes,
 *    or the URL they came from.
 *
 * The injected [client] may have redirect following enabled (a shared client usually does), so
 * every call goes through [noRedirect], a derived client with `followRedirects = false`. Without
 * that, ktor would swallow the 302 and forward the credential to storage.
 */
class GithubReleaseClient(
    client: HttpClient,
) {
    /** Which repository to read, and where the API lives (overridable for tests). */
    data class Repo(
        val baseUrl: String,
        val owner: String,
        val name: String,
    )

    private val log = LoggerFactory.getLogger(GithubReleaseClient::class.java)

    /**
     * The injected client with automatic redirect following removed.
     *
     * Visible to the module's tests only so the guard can be asserted directly: ktor's own
     * `HttpRedirect` happens to drop `Authorization` on a cross-host hop, so a black-box download
     * test passes either way and would not notice the guard disappearing.
     */
    internal val noRedirect: HttpClient = client.config { followRedirects = false }

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    /**
     * Returns the latest non-draft, non-prerelease release when it is strictly newer than
     * [current]; otherwise [ReleaseLookup.UpToDate].
     *
     * A [token] is sent as a bearer credential when present. Failures never throw -- the nightly
     * check records them.
     */
    suspend fun latest(
        repo: Repo,
        token: String?,
        current: SemanticVersion,
    ): ReleaseLookup {
        val response =
            try {
                noRedirect.get("${repo.baseUrl.trimEnd('/')}/repos/${repo.owner}/${repo.name}/releases/latest") {
                    applyGithubHeaders(token)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Update check could not reach GitHub: {}", e.toString())
                return ReleaseLookup.Unreachable(e.message ?: e::class.simpleName ?: "unreachable")
            }

        classifyFailure(response, token)?.let { return it }

        val release =
            try {
                json.decodeFromString(GithubRelease.serializer(), response.bodyAsText())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return ReleaseLookup.Unreachable("malformed release payload: ${e.message}")
            }
        return evaluate(release, current)
    }

    /**
     * Downloads one release asset by id, following the storage redirect by hand.
     *
     * The credential is attached only while the request is still aimed at the API host; signed
     * object storage rejects a request that carries both its signature and our `Authorization`.
     */
    suspend fun downloadAsset(
        repo: Repo,
        token: String?,
        assetId: Long,
    ): ByteArray = walkToAsset(repo, token, assetId, rangeHeader = null) { _, response -> response.body<ByteArray>() }

    /**
     * Streams one release asset into [target] and returns its SHA-256, lower-case hex.
     *
     * The counterpart of [downloadAsset] for an artifact too large to hold in heap: the server jar
     * is ~127 MB, and a POS host that reads it into a `ByteArray` only to write it straight back
     * out pays for it twice. The digest is accumulated while the bytes are written, so the caller
     * gets what `UpdateStager.stageJar` needs without a second pass over the file.
     *
     * The redirect walk -- and with it the rule that the credential is never sent to signed
     * storage -- is the same one [downloadAsset] uses; only what is done with the final response
     * differs.
     *
     * A failure leaves no file: [target] is deleted before the exception propagates, so a
     * half-written jar can never be mistaken for a download that finished.
     */
    suspend fun downloadAssetTo(
        repo: Repo,
        token: String?,
        assetId: Long,
        target: File,
        onProgress: (bytesDone: Long, bytesTotal: Long?) -> Unit = { _, _ -> },
    ): String =
        try {
            walkToAsset(repo, token, assetId, rangeHeader = null) { _, response ->
                target.parentFile?.mkdirs()
                streamToFile(response, target, onProgress)
            }
        } catch (failure: Throwable) {
            target.delete()
            throw failure
        }

    /** Writes the response body to [target] as it arrives, returning the SHA-256 of what it wrote. */
    private suspend fun streamToFile(
        response: HttpResponse,
        target: File,
        onProgress: (bytesDone: Long, bytesTotal: Long?) -> Unit,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(1 shl 16)
        val total = response.contentLength()
        var written = 0L
        target.outputStream().buffered().use { out ->
            while (true) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read < 0) break
                if (read == 0) continue
                digest.update(buffer, 0, read)
                out.write(buffer, 0, read)
                written += read
                onProgress(written, total)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Follows the asset API's redirect and returns the URL the bytes actually live at.
     *
     * Split out of [downloadAsset] so a delta download can resolve once and then issue many range
     * requests against the same signed URL. The credential is attached only while the request is
     * still aimed at the API host -- signed storage rejects a request carrying both its signature
     * and our `Authorization`.
     *
     * The walk is made with `Range: bytes=0-0`, so resolving costs a single byte instead of the
     * ~127 MB the asset weighs. That is also why [downloadAsset] does **not** call this: it would
     * pay for one round trip more than the plain walk it already does.
     *
     * **The returned URL is time-limited.** A signed storage URL expires within minutes, and a
     * delta may spend longer than that fetching its ranges, so a [downloadRange] against a URL
     * this returned can fail with [SignedUrlExpired] at any point -- that type, not the wording of
     * a message, is the signal to call this method again. Re-resolving is deliberately *not* done
     * here: only the caller holds [repo]/[token]/[assetId], only the caller knows how many ranges
     * are left, and only the caller can weigh resolving again against abandoning the delta for a
     * full download. This method never retries and never caches.
     */
    suspend fun resolveAssetUrl(
        repo: Repo,
        token: String?,
        assetId: Long,
    ): String = walkToAsset(repo, token, assetId, rangeHeader = PROBE_RANGE) { url, _ -> url }

    /**
     * Walks the asset API's redirects by hand until something that is not a redirect answers, then
     * hands that response to [readAsset] together with the URL it came from.
     *
     * The walk is **streamed** (`prepareGet(...).execute { }`), which is what lets the two callers
     * differ in cost rather than only in intent. ktor's plain `get()` returning an [HttpResponse]
     * saves the whole body before the status can even be inspected, so a storage server that
     * ignores `Range` would buffer the ~127 MB asset into memory to answer a one-byte probe.
     * Inside [readAsset] the body is still a live channel: [downloadAsset] reads it, and
     * [resolveAssetUrl] takes the URL and lets the response go, so the connection is released
     * without the body ever being read.
     *
     * [readAsset]'s result must not escape as a lazily-read stream -- it is only valid inside the
     * block, which is why the callers return a `ByteArray` or a `String`, never the response.
     *
     * [rangeHeader] is the single axis on which the callers differ. It is deliberately not an open
     * `HttpRequestBuilder.() -> Unit`: that would let a future third caller overwrite the
     * `Authorization` header on the one hop this class exists to control.
     */
    private suspend fun <T : Any> walkToAsset(
        repo: Repo,
        token: String?,
        assetId: Long,
        rangeHeader: String?,
        readAsset: suspend (url: String, response: HttpResponse) -> T,
    ): T {
        val apiHost = hostOf(repo.baseUrl)
        var url = "${repo.baseUrl.trimEnd('/')}/repos/${repo.owner}/${repo.name}/releases/assets/$assetId"
        var hops = 0
        while (true) {
            var location: String? = null
            val asset =
                noRedirect
                    .prepareGet(url) {
                        accept(ContentType.Application.OctetStream)
                        if (token != null && hostOf(url) == apiHost) bearerAuth(token)
                        header(HEADER_API_VERSION, API_VERSION)
                        if (rangeHeader != null) header(HttpHeaders.Range, rangeHeader)
                    }.execute { response ->
                        if (isRedirect(response.status)) {
                            // Read the hop *inside* the block; the response is gone after it.
                            location = response.headers[HttpHeaders.Location]
                            null
                        } else {
                            if (!response.status.isSuccess()) {
                                throw downloadFailure("asset $assetId download answered HTTP ${response.status.value}")
                            }
                            readAsset(url, response)
                        }
                    }
            if (asset != null) return asset
            val next = location ?: throw downloadFailure("asset $assetId redirected without a Location header")
            url = resolveLocation(url, next)
            if (++hops > MAX_REDIRECTS) {
                throw downloadFailure("asset $assetId exceeded $MAX_REDIRECTS redirects")
            }
        }
    }

    /**
     * Fetches exactly [range] from an already-resolved [url], carrying no credential.
     *
     * **Only valid against a *storage* URL.** [resolveAssetUrl] legitimately returns the
     * `api.github.com` asset URL when nothing redirects, and this method sends no token at all --
     * against a private repository that combination is a `404`, not the bytes. It fails safely
     * rather than silently, but a caller that hands over an API URL is asking the wrong question.
     *
     * Three answers are refused rather than accepted:
     * - **`200`** -- a server that ignores `Range` returns the entire ~127 MB body, which would
     *   turn a 2 MB delta into a full download held in memory *and* write those bytes at the
     *   offsets the range was meant for. The request is streamed, so the status is refused before
     *   that body is read.
     * - **a body of the wrong length** -- the reconstruction would be quietly wrong.
     * - **a `Content-Range` naming a different span** -- a proxy serving some other stretch of the
     *   right length corrupts the reconstruction silently; nothing but the final SHA-256 would
     *   notice, and by then the download is wasted.
     *
     * A `401`/`403` means the signed URL has expired, and throws [SignedUrlExpired] -- a subtype
     * carrying the same error code and status as every other failure here, so nothing downstream
     * changes, but one a caller can `catch` to decide whether to re-resolve (see [resolveAssetUrl])
     * instead of matching on the words in a message.
     *
     * An empty or negative [range] cannot be constructed: [ByteRange] rejects it, so nothing is
     * sent.
     */
    suspend fun downloadRange(
        url: String,
        range: ByteRange,
    ): ByteArray =
        noRedirect
            .prepareGet(url) {
                accept(ContentType.Application.OctetStream)
                header(HttpHeaders.Range, range.toHeaderValue())
            }.execute { response -> readRangeBody(response, range) }

    /** Refuses every answer that is not exactly the requested span, then reads it. */
    private suspend fun readRangeBody(
        response: HttpResponse,
        range: ByteRange,
    ): ByteArray {
        if (response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden) {
            throw SignedUrlExpired(
                "range ${range.toHeaderValue()} answered HTTP ${response.status.value}: the signed URL has expired",
            )
        }
        if (response.status != HttpStatusCode.PartialContent) {
            throw downloadFailure(
                "range ${range.toHeaderValue()} answered HTTP ${response.status.value}, expected 206",
            )
        }
        verifyContentRange(response.headers[HttpHeaders.ContentRange], range)

        val bytes = response.body<ByteArray>()
        if (bytes.size.toLong() != range.length) {
            throw downloadFailure(
                "range ${range.toHeaderValue()} returned ${bytes.size} bytes, expected ${range.length}",
            )
        }
        return bytes
    }

    /**
     * A signed storage URL that is no longer valid -- the one failure a caller can do something
     * about, so it is a type rather than a phrase inside a message.
     *
     * Carries [PosErrorCode.UPDATE_DOWNLOAD_FAILED] and `502` like every other failure here: the
     * subtype exists to let the delta download re-resolve and retry, not to be reported
     * differently.
     */
    class SignedUrlExpired(
        message: String,
    ) : PosException(PosErrorCode.UPDATE_DOWNLOAD_FAILED, HttpStatusCode.BadGateway, message)

    /**
     * Refuses a `206` whose `Content-Range` does not name the span that was asked for.
     *
     * The total after the `/` is ignored -- it may legitimately be `*` -- but the span itself must
     * match exactly, because a same-length answer for a different offset is the one corruption a
     * byte count cannot see.
     */
    private fun verifyContentRange(
        header: String?,
        range: ByteRange,
    ) {
        val served =
            header ?: throw downloadFailure(
                "range ${range.toHeaderValue()} answered 206 without a Content-Range header",
            )
        val span =
            CONTENT_RANGE.matchEntire(served.trim())
                ?: throw downloadFailure(
                    "range ${range.toHeaderValue()} answered an unreadable Content-Range \"$served\"",
                )
        val start = span.groupValues[1].toLongOrNull()
        val endInclusive = span.groupValues[2].toLongOrNull()
        if (start != range.start || endInclusive != range.endExclusive - 1) {
            throw downloadFailure(
                "asked for ${range.toHeaderValue()} but was served bytes $start-$endInclusive",
            )
        }
    }

    private fun HttpRequestBuilder.applyGithubHeaders(token: String?) {
        accept(ContentType.Application.Json)
        header(HEADER_API_VERSION, API_VERSION)
        if (token != null) bearerAuth(token)
    }

    /**
     * Turns a non-success status into a lookup outcome, or null when the response is usable.
     *
     * The 404 arm is the whole point of the class: with a token it means the credential no longer
     * opens the repository; without one it genuinely means there is nothing published.
     */
    private fun classifyFailure(
        response: HttpResponse,
        token: String?,
    ): ReleaseLookup? {
        val status = response.status
        return when {
            status.isSuccess() -> null
            status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden -> ReleaseLookup.AuthFailed
            status == HttpStatusCode.NotFound && token != null -> ReleaseLookup.AuthFailed
            status == HttpStatusCode.NotFound -> ReleaseLookup.UpToDate
            else -> ReleaseLookup.Unreachable("GitHub answered HTTP ${status.value}")
        }
    }

    private fun evaluate(
        release: GithubRelease,
        current: SemanticVersion,
    ): ReleaseLookup {
        if (release.draft || release.prerelease) return ReleaseLookup.UpToDate
        val version = SemanticVersion.parse(release.tagName) ?: return ReleaseLookup.UpToDate
        if (version.prerelease != null) return ReleaseLookup.UpToDate
        if (version <= current) return ReleaseLookup.UpToDate

        val server =
            release.assets.firstOrNull(::isServerArchive)
                ?: return ReleaseLookup.Unreachable(
                    "release ${release.tagName} publishes no server archive",
                )
        return ReleaseLookup.Found(
            AvailableUpdate(
                version = version,
                releaseNotes = release.body.orEmpty(),
                serverAssetId = server.id,
                checksumAssetId = release.assets.firstOrNull { it.name.equals(CHECKSUM_ASSET, ignoreCase = true) }?.id,
                sizeBytes = server.size,
                jarAssetId = release.assets.firstOrNull(::isServerJar)?.id,
                structureAssetId = release.assets.firstOrNull(::isJarStructure)?.id,
                // singleOrNull: zero or several -> null. Several APKs mean we cannot tell which is ours.
                apkAssetId = release.assets.singleOrNull(::isAndroidApk)?.id,
                clientInfoAssetId = release.assets.firstOrNull { it.name.equals(CLIENT_INFO_ASSET, ignoreCase = true) }?.id,
            ),
        )
    }

    private fun isServerArchive(asset: GithubAsset): Boolean {
        val name = asset.name.lowercase()
        return name.endsWith(".zip") && name.contains("server")
    }

    /**
     * The bare server jar -- the only file an update replaces.
     *
     * `endsWith` is load-bearing: `pos-server-<v>.jar.cdir.gz` also *contains* `.jar`, and a
     * `contains` spelling would select the structure file whenever it is listed first.
     */
    private fun isServerJar(asset: GithubAsset): Boolean {
        val name = asset.name.lowercase()
        return name.endsWith(".jar") && name.contains("pos-server")
    }

    /** The Android APK. End-anchored: `SHA256SUMS`, `.jar` and `.cdir.gz` must never match. */
    private fun isAndroidApk(asset: GithubAsset): Boolean = asset.name.lowercase().endsWith(".apk")

    /** The gzipped ZIP central directory of the jar above, used to plan a delta download. */
    private fun isJarStructure(asset: GithubAsset): Boolean = asset.name.lowercase().endsWith(".jar.cdir.gz")

    private fun downloadFailure(message: String) = PosException(PosErrorCode.UPDATE_DOWNLOAD_FAILED, HttpStatusCode.BadGateway, message)

    private fun isRedirect(status: HttpStatusCode) = status.value in 300..399

    /** A relative `Location` (RFC 9110 §10.2.2) is resolved against the URL that answered it. */
    private fun resolveLocation(
        base: String,
        location: String,
    ): String = if (location.startsWith("http://") || location.startsWith("https://")) location else URLBuilder(base).takeFrom(location).buildString()

    private fun hostOf(url: String) = runCatching { Url(url).host }.getOrElse { "" }

    companion object {
        private const val HEADER_API_VERSION = "X-GitHub-Api-Version"
        private const val API_VERSION = "2022-11-28"
        private const val CHECKSUM_ASSET = "SHA256SUMS"
        private const val CLIENT_INFO_ASSET = "android-client.json"
        private const val MAX_REDIRECTS = 5

        /** Resolving only needs the URL, so the walk asks for one byte rather than the asset. */
        private const val PROBE_RANGE = "bytes=0-0"

        /** `bytes <start>-<endInclusive>/<total|*>`; the total is deliberately not captured. */
        private val CONTENT_RANGE = Regex("""bytes (\d+)-(\d+)/(?:\d+|\*)""")

        /**
         * The engine used in production. `followRedirects = false` is part of the contract, not a
         * detail: the credential must be dropped by hand on the hop to object storage.
         */
        fun defaultHttpClient(): HttpClient =
            HttpClient(OkHttp) {
                followRedirects = false
                expectSuccess = false
            }
    }
}
