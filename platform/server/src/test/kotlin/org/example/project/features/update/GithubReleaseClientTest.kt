package org.example.project.features.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.example.project.core.utils.SemanticVersion
import org.example.project.features.update.data.remote.GithubReleaseClient
import org.example.project.features.update.domain.models.ReleaseLookup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class GithubReleaseClientTest {
    private fun release(
        tag: String,
        prerelease: Boolean = false,
        draft: Boolean = false,
        assets: String =
            """[{"id":42,"name":"zenbon-pos-server-$tag.zip","size":1024},
                {"id":43,"name":"SHA256SUMS","size":64}]""",
    ) = """
        {"tag_name":"$tag","name":"Zenbon POS $tag","body":"notes here",
         "prerelease":$prerelease,"draft":$draft,
         "assets":$assets}
        """.trimIndent()

    private fun clientReturning(body: String) =
        HttpClient(
            MockEngine {
                respond(
                    body,
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )

    private val current = SemanticVersion.parse("1.1.0")!!

    @Test
    fun `reports a newer release as found`() =
        runTest {
            val found =
                assertIs<ReleaseLookup.Found>(
                    GithubReleaseClient(clientReturning(release("v1.2.0"))).latest(REPO, null, current),
                )
            assertEquals("1.2.0", found.update.version.toString())
            assertEquals(42L, found.update.serverAssetId)
            assertEquals(43L, found.update.checksumAssetId)
            assertEquals(1024L, found.update.sizeBytes)
        }

    @Test
    fun `reports the same version as up to date`() =
        runTest {
            assertIs<ReleaseLookup.UpToDate>(
                GithubReleaseClient(clientReturning(release("v1.1.0"))).latest(REPO, null, current),
            )
        }

    @Test
    fun `reports an older release as up to date`() =
        runTest {
            assertIs<ReleaseLookup.UpToDate>(
                GithubReleaseClient(clientReturning(release("v1.0.9"))).latest(REPO, null, current),
            )
        }

    @Test
    fun `ignores a prerelease`() =
        runTest {
            assertIs<ReleaseLookup.UpToDate>(
                GithubReleaseClient(clientReturning(release("v1.2.0", prerelease = true)))
                    .latest(REPO, null, current),
            )
        }

    @Test
    fun `ignores a draft`() =
        runTest {
            assertIs<ReleaseLookup.UpToDate>(
                GithubReleaseClient(clientReturning(release("v1.2.0", draft = true)))
                    .latest(REPO, null, current),
            )
        }

    @Test
    fun `ignores an unparseable tag`() =
        runTest {
            assertIs<ReleaseLookup.UpToDate>(
                GithubReleaseClient(clientReturning(release("nightly"))).latest(REPO, null, current),
            )
        }

    @Test
    fun `404 without a token means the repo has no releases`() =
        runTest {
            val sut = GithubReleaseClient(HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) }))
            assertIs<ReleaseLookup.UpToDate>(sut.latest(REPO, token = null, current = current))
        }

    @Test
    fun `404 WITH a token means the token is invalid or expired`() =
        runTest {
            val sut = GithubReleaseClient(HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) }))
            assertIs<ReleaseLookup.AuthFailed>(sut.latest(REPO, token = "github_pat_x", current = current))
        }

    @Test
    fun `401 is an auth failure regardless of token`() =
        runTest {
            val sut = GithubReleaseClient(HttpClient(MockEngine { respondError(HttpStatusCode.Unauthorized) }))
            assertIs<ReleaseLookup.AuthFailed>(sut.latest(REPO, null, current))
        }

    @Test
    fun `a network failure is unreachable, not up to date`() =
        runTest {
            val sut = GithubReleaseClient(HttpClient(MockEngine { throw java.io.IOException("dns") }))
            assertIs<ReleaseLookup.Unreachable>(sut.latest(REPO, null, current))
        }

    @Test
    fun `sends the token as a bearer credential when one is configured`() =
        runTest {
            var seen: String? = null
            val sut =
                GithubReleaseClient(
                    HttpClient(
                        MockEngine { req ->
                            seen = req.headers[HttpHeaders.Authorization]
                            respond(
                                release("v1.1.0"),
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        },
                    ),
                )
            sut.latest(REPO, token = "github_pat_x", current = current)
            assertEquals("Bearer github_pat_x", seen)
        }

    /**
     * A newer release whose server archive has not been uploaded (or was named unexpectedly) must
     * not read as "you are current" -- that would hide the release forever. It is reported as
     * [ReleaseLookup.Unreachable] so the state stays visible and the nightly check retries.
     */
    @Test
    fun `a newer release without a server archive is unreachable, not up to date`() =
        runTest {
            val sut =
                GithubReleaseClient(
                    clientReturning(release("v1.2.0", assets = """[{"id":43,"name":"SHA256SUMS","size":64}]""")),
                )
            assertIs<ReleaseLookup.Unreachable>(sut.latest(REPO, null, current))
        }

    /** Checksums were not published for every historical release; their absence is not fatal. */
    @Test
    fun `a release without SHA256SUMS still reports found with a null checksum asset`() =
        runTest {
            val sut =
                GithubReleaseClient(
                    clientReturning(
                        release("v1.2.0", assets = """[{"id":42,"name":"zenbon-pos-server-v1.2.0.zip","size":1024}]"""),
                    ),
                )
            val found = assertIs<ReleaseLookup.Found>(sut.latest(REPO, null, current))
            assertEquals(null, found.update.checksumAssetId)
        }

    @Test
    fun `reports the bare jar and structure assets when the release publishes them`() =
        runTest {
            val sut =
                GithubReleaseClient(
                    clientReturning(
                        release(
                            "v1.2.0",
                            assets =
                                """[{"id":1,"name":"zenbon-pos-server-v1.2.0.zip","size":10},
                                    {"id":2,"name":"SHA256SUMS","size":1},
                                    {"id":3,"name":"pos-server-v1.2.0.jar","size":9},
                                    {"id":4,"name":"pos-server-v1.2.0.jar.cdir.gz","size":2}]""",
                        ),
                    ),
                )
            val found = assertIs<ReleaseLookup.Found>(sut.latest(REPO, null, current))
            assertEquals(3L, found.update.jarAssetId)
            assertEquals(4L, found.update.structureAssetId)
        }

    @Test
    fun `an older release without the new assets reports them as null`() =
        runTest {
            val sut =
                GithubReleaseClient(
                    clientReturning(
                        release(
                            "v1.2.0",
                            assets =
                                """[{"id":1,"name":"zenbon-pos-server-v1.2.0.zip","size":10},
                                    {"id":2,"name":"SHA256SUMS","size":1}]""",
                        ),
                    ),
                )
            val found = assertIs<ReleaseLookup.Found>(sut.latest(REPO, null, current))
            assertNull(found.update.jarAssetId)
            assertNull(found.update.structureAssetId)
        }

    /**
     * The whole published asset set at once, with the structure listed *before* the jar it belongs
     * to: asset order is not guaranteed, and a jar predicate spelled `contains(".jar")` would then
     * claim `pos-server-<v>.jar.cdir.gz` and quietly select the wrong download. Each id is
     * distinct so a mis-selection cannot look right by coincidence.
     */
    @Test
    fun `selects exactly one asset each against the full published asset set`() =
        runTest {
            val sut =
                GithubReleaseClient(
                    clientReturning(
                        release(
                            "v1.2.0",
                            assets =
                                """[{"id":1,"name":"zenbon-pos-v1.2.0.apk","size":11},
                                    {"id":2,"name":"pos-server-v1.2.0.jar.cdir.gz","size":22},
                                    {"id":3,"name":"pos-server-v1.2.0.jar","size":33},
                                    {"id":4,"name":"zenbon-pos-server-v1.2.0.zip","size":44},
                                    {"id":5,"name":"zenbon-pos-v1.2.0.deb","size":55},
                                    {"id":6,"name":"SHA256SUMS","size":66}]""",
                        ),
                    ),
                )
            val found = assertIs<ReleaseLookup.Found>(sut.latest(REPO, null, current))
            assertEquals(4L, found.update.serverAssetId)
            assertEquals(44L, found.update.sizeBytes)
            assertEquals(6L, found.update.checksumAssetId)
            assertEquals(3L, found.update.jarAssetId)
            assertEquals(2L, found.update.structureAssetId)
        }

    private fun assetsWith(vararg extra: String) =
        """[{"id":42,"name":"zenbon-pos-server-v1.2.0.zip","size":1024},
            {"id":43,"name":"SHA256SUMS","size":64}${extra.joinToString("") { ",$it" }}]"""

    private fun found(assets: String) =
        assertIs<ReleaseLookup.Found>(
            runBlocking { GithubReleaseClient(clientReturning(release("v1.2.0", assets = assets))).latest(REPO, null, current) },
        ).update

    @Test
    fun `finds the apk and the client info asset`() {
        val update =
            found(
                assetsWith(
                    """{"id":44,"name":"zenbon-pos-v1.2.0.apk","size":2048}""",
                    """{"id":45,"name":"android-client.json","size":40}""",
                ),
            )
        assertEquals(44L, update.apkAssetId)
        assertEquals(45L, update.clientInfoAssetId)
    }

    @Test
    fun `a release without an apk offers none`() {
        val update = found(assetsWith())
        assertNull(update.apkAssetId)
        assertNull(update.clientInfoAssetId)
    }

    @Test
    fun `two apks are refused rather than guessed between`() {
        val update =
            found(
                assetsWith(
                    """{"id":44,"name":"zenbon-pos-v1.2.0.apk","size":2048}""",
                    """{"id":46,"name":"zenbon-pos-v1.2.0-debug.apk","size":2048}""",
                ),
            )
        assertNull(update.apkAssetId)
    }

    private companion object {
        val REPO = GithubReleaseClient.Repo("https://api.github.com", "DDZzzzz12", "zenbon-pos")
    }
}
