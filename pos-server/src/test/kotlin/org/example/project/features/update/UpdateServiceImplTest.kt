package org.example.project.features.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.example.project.core.error.PosErrorCode
import org.example.project.core.utils.SemanticVersion
import org.example.project.features.update.data.crypto.TokenCipher
import org.example.project.features.update.data.remote.GithubReleaseClient
import org.example.project.features.update.data.service.AndroidClientStager
import org.example.project.features.update.data.service.ClientArtifactStore
import org.example.project.features.update.data.service.JarStructure
import org.example.project.features.update.data.service.UpdateApplier
import org.example.project.features.update.data.service.UpdatePreflight
import org.example.project.features.update.data.service.UpdateServiceImpl
import org.example.project.features.update.data.service.UpdateStager
import org.example.project.features.update.domain.models.PreflightVerdict
import org.example.project.features.update.domain.models.UpdateChannel
import org.example.project.features.update.domain.models.UpdateCheckOutcome
import org.example.project.features.update.domain.models.UpdateSettings
import org.example.project.features.update.domain.models.UpdateSettingsEdit
import org.example.project.features.update.domain.repository.UpdateSettingsRepository
import org.example.project.infrastructure.errors.PosException
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The branch table of one nightly check, asserted on both halves: the summary the scheduler
 * stores *and* the side effects (what was recorded, what was downloaded, whether the applier ran,
 * and whether the staged artifact survived).
 *
 * Only the outermost boundaries are faked -- HTTP, the process exit, the six preflight probes.
 * The stager, the applier and the release client are the real ones, so "the staged file is still
 * there after a blocked preflight" is a statement about real bytes on a real disk, which is the
 * only form of that assertion worth making.
 */
class UpdateServiceImplTest {
    private val current = SemanticVersion.parse("1.1.0")!!
    private val artifact = "the server archive".toByteArray()

    // ---------- fakes ----------

    private class FakeSettingsRepository(
        var settings: UpdateSettings,
    ) : UpdateSettingsRepository {
        val recorded = mutableListOf<UpdateCheckOutcome>()
        val stagedPaths = mutableListOf<String?>()

        override suspend fun load(): UpdateSettings = settings

        override suspend fun save(edit: UpdateSettingsEdit): UpdateSettings = settings

        override suspend fun setToken(token: String) = error("not used")

        override suspend fun clearToken() = error("not used")

        override suspend fun recordCheck(outcome: UpdateCheckOutcome) {
            recorded += outcome
            settings =
                settings.copy(
                    lastCheckedAt = outcome.checkedAt,
                    lastCheckOutcome = outcome.outcome,
                    availableVersion = outcome.availableVersion,
                    availableNotes = outcome.availableNotes,
                    availableAssetId = outcome.availableAssetId,
                    lastBlockedReason = outcome.blockedReason,
                )
        }

        override suspend fun setStagedPath(path: String?) {
            stagedPaths += path
            settings = settings.copy(stagedPath = path)
        }

        val lastOutcome: String? get() = recorded.lastOrNull()?.outcome
    }

    private fun settings(
        channel: UpdateChannel = UpdateChannel.PUBLIC,
        autoApply: Boolean = false,
        token: String? = null,
        tokenUndecryptable: Boolean = false,
        availableVersion: String? = null,
        stagedPath: String? = null,
    ) = UpdateSettings(
        channel = channel,
        apiBaseUrl = "https://api.github.com",
        autoApply = autoApply,
        token = token,
        tokenUndecryptable = tokenUndecryptable,
        tokenSetAt = null,
        lastCheckedAt = null,
        lastCheckOutcome = null,
        lastBlockedReason = null,
        availableVersion = availableVersion,
        availableNotes = null,
        availableAssetId = null,
        stagedPath = stagedPath,
    )

    /** A release payload whose server asset is id 42 and whose SHA256SUMS asset is id 43. */
    private fun releaseBody(tag: String) =
        """
        {"tag_name":"$tag","name":"Zenbon POS $tag","body":"notes here",
         "prerelease":false,"draft":false,
         "assets":[{"id":42,"name":"zenbon-pos-server-$tag.zip","size":1024},
                   {"id":43,"name":"SHA256SUMS","size":64}]}
        """.trimIndent()

    private fun sha256Hex(bytes: ByteArray) =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /** Records every request so "no HTTP at all" and "the token was sent" are directly assertable. */
    private class RecordingEngine(
        val requests: MutableList<HttpRequestData> = mutableListOf(),
    )

    private fun httpClient(
        recorder: RecordingEngine,
        releaseTag: String? = "v1.2.0",
        status: HttpStatusCode = HttpStatusCode.OK,
    ): HttpClient =
        HttpClient(
            MockEngine { request ->
                recorder.requests += request
                val path = request.url.encodedPath
                when {
                    status != HttpStatusCode.OK -> respond("nope", status)
                    path.endsWith("/releases/latest") ->
                        respond(
                            releaseBody(releaseTag!!),
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    path.endsWith("/assets/43") -> respond("${sha256Hex(artifact)}  zenbon-pos-server-1.2.0.zip\n")
                    path.endsWith("/assets/42") -> respond(artifact)
                    else -> respond("unexpected ${request.url}", HttpStatusCode.NotFound)
                }
            },
        )

    private class Rig(
        val repo: FakeSettingsRepository,
        val recorder: RecordingEngine,
        val stagingDir: File,
        val installDir: File,
        val service: UpdateServiceImpl,
    ) {
        var applyCalls = 0
        var exitCode: Int? = null

        fun stagedFiles(): List<String> =
            stagingDir
                .listFiles()
                .orEmpty()
                .map { it.name }
                .sorted()

        fun assetRequests(): Int = recorder.requests.count { it.url.encodedPath.contains("/assets/") }

        /**
         * The `owner/name` the release lookup actually went to.
         *
         * Asserting on this rather than on the stored settings is the point: the defect this pins
         * was invisible from the settings row, which said PRIVATE quite happily while the request
         * went to the public repository.
         */
        fun queriedRepo(): String =
            recorder.requests
                .first { it.url.encodedPath.endsWith("/releases/latest") }
                .url.encodedPath
                .removePrefix("/repos/")
                .removeSuffix("/releases/latest")
    }

    private fun rig(
        settings: UpdateSettings,
        verdict: PreflightVerdict = PreflightVerdict.Clear,
        releaseTag: String? = "v1.2.0",
        status: HttpStatusCode = HttpStatusCode.OK,
    ): Rig {
        val repo = FakeSettingsRepository(settings)
        val recorder = RecordingEngine()
        val stagingDir = Files.createTempDirectory("update-staging").toFile()
        val installDir = Files.createTempDirectory("update-install").toFile()
        File(installDir, "pos-server.jar").writeBytes("old jar".toByteArray())
        val backup = File(installDir, "pre-apply.dump").apply { writeBytes("dump".toByteArray()) }

        lateinit var built: Rig
        val applier =
            UpdateApplier(
                installDir = installDir,
                createBackup = { backup.absolutePath },
                extractServerJar = {
                    built.applyCalls++
                    File(installDir, "extracted.jar").apply { writeBytes("new jar".toByteArray()) }
                },
                exitProcess = { built.exitCode = it },
            )
        val blocked = verdict as? PreflightVerdict.Blocked
        val preflight =
            UpdatePreflight(
                hasOpenBusiness = { blocked?.reason == PreflightVerdict.Reason.OPEN_BUSINESS },
                countOpenSessions = { if (blocked?.reason == PreflightVerdict.Reason.OPEN_TABLE_SESSION) 1 else 0 },
                hasOpenTseWork = { blocked?.reason == PreflightVerdict.Reason.OPEN_TSE_WORK },
                countRunningJobs = { if (blocked?.reason == PreflightVerdict.Reason.JOB_RUNNING) 1 else 0 },
                freeDiskBytes = { if (blocked?.reason == PreflightVerdict.Reason.INSUFFICIENT_DISK) 0 else 1_000_000_000 },
                requiredBytes = { 1024 },
            )
        val releasesClient = GithubReleaseClient(httpClient(recorder, releaseTag, status))
        val serverStager = UpdateStager(stagingDir)
        val clientStore = ClientArtifactStore(Files.createTempDirectory("client").toFile(), serverStager)
        built =
            Rig(
                repo = repo,
                recorder = recorder,
                stagingDir = stagingDir,
                installDir = installDir,
                service =
                    UpdateServiceImpl(
                        settings = repo,
                        releases = releasesClient,
                        stager = serverStager,
                        clientStager = AndroidClientStager(releasesClient, clientStore),
                        clientArtifacts = clientStore,
                        preflight = preflight,
                        applier = applier,
                        currentVersion = current,
                        // Only consulted by setToken, which this suite does not exercise; a real
                        // key keeps it "available" so the fake never stands in for a missing one.
                        cipher = TokenCipher(TokenCipher.generateKeyBase64()),
                        resolveInstallDir = { installDir },
                    ),
            )
        return built
    }

    // ---------- the table ----------

    @Test
    fun `an unchanged release reports up to date and touches nothing`() =
        runTest {
            val r = rig(settings(), releaseTag = "v1.1.0")
            val summary = r.service.runScheduledCheck()

            assertContains(summary, "up to date")
            assertContains(summary, "1.1.0")
            assertEquals(0, r.applyCalls)
            assertEquals(emptyList(), r.stagedFiles())
            assertEquals(0, r.assetRequests())
            assertEquals(UpdateServiceImpl.OUTCOME_UP_TO_DATE, r.repo.lastOutcome)
            assertNull(
                r.repo.recorded
                    .single()
                    .availableVersion,
            )
        }

    @Test
    fun `a rejected credential is recorded as an auth failure, never as up to date`() =
        runTest {
            val r = rig(settings(channel = UpdateChannel.PRIVATE, token = "ghp_x"), status = HttpStatusCode.Unauthorized)
            val summary = r.service.runScheduledCheck()

            assertEquals(UpdateServiceImpl.OUTCOME_AUTH_FAILED, r.repo.lastOutcome)
            assertFalse(summary.contains("up to date"), "an expired token must not read as up to date: $summary")
            assertEquals(0, r.applyCalls)
        }

    @Test
    fun `an unreachable GitHub is recorded and applies nothing`() =
        runTest {
            val r = rig(settings(), status = HttpStatusCode.BadGateway)
            val summary = r.service.runScheduledCheck()

            assertEquals(UpdateServiceImpl.OUTCOME_UNREACHABLE, r.repo.lastOutcome)
            assertContains(summary, "failed")
            assertEquals(0, r.applyCalls)
        }

    @Test
    fun `with auto-apply off the release is staged and recorded but never installed`() =
        runTest {
            val r = rig(settings(autoApply = false))
            val summary = r.service.runScheduledCheck()

            assertContains(summary, "1.2.0")
            assertContains(summary, "auto-apply off")
            assertEquals(listOf("zenbon-pos-server-1.2.0.zip"), r.stagedFiles())
            assertEquals("1.2.0", r.repo.settings.availableVersion)
            assertEquals(42L, r.repo.settings.availableAssetId)
            assertEquals(0, r.applyCalls)
        }

    @Test
    fun `with auto-apply on and a clear preflight the applier runs once on the staged file`() =
        runTest {
            val r = rig(settings(autoApply = true))
            r.service.runScheduledCheck()

            assertEquals(1, r.applyCalls)
            assertEquals(UpdateApplier.EXIT_UPDATE_APPLIED, r.exitCode)
            assertEquals("new jar", File(r.installDir, "pos-server.jar").readText())
        }

    @Test
    fun `a blocked preflight names the reason and keeps the staged artifact`() =
        runTest {
            val r = rig(settings(autoApply = true), verdict = PreflightVerdict.Blocked(PreflightVerdict.Reason.OPEN_BUSINESS))
            val summary = r.service.runScheduledCheck()

            assertEquals(0, r.applyCalls)
            assertContains(summary, "skipped")
            assertContains(summary, "open business")
            // Re-downloading 200 MB every night because a shop closes late is the failure this design avoids.
            assertEquals(listOf("zenbon-pos-server-1.2.0.zip"), r.stagedFiles())
            assertTrue(File(r.repo.settings.stagedPath!!).isFile)
        }

    @Test
    fun `a blocked nightly run stores the blocker, not just a summary string nobody reads`() =
        runTest {
            // The summary asserted above is returned to the scheduler and never reaches the update
            // screen. Without the recorded reason the back office showed "staged, ready to install"
            // for a shop that had been turned away every night since.
            val r = rig(settings(autoApply = true), verdict = PreflightVerdict.Blocked(PreflightVerdict.Reason.OPEN_TSE_WORK))

            r.service.runScheduledCheck()

            assertEquals("UPDATE_SKIPPED", r.repo.lastOutcome)
            assertEquals(
                "OPEN_TSE_WORK",
                r.repo.recorded
                    .last()
                    .blockedReason,
            )
            assertEquals("OPEN_TSE_WORK", r.service.status().lastBlockedReason)
        }

    @Test
    fun `a later clear run clears the stored blocker`() =
        runTest {
            // A reason that outlives its condition is worse than none: the screen would keep
            // accusing the shop of open TSE work days after it was finished.
            val r = rig(settings(autoApply = true), verdict = PreflightVerdict.Blocked(PreflightVerdict.Reason.OPEN_TSE_WORK))
            r.service.runScheduledCheck()
            assertEquals("OPEN_TSE_WORK", r.service.status().lastBlockedReason)

            val clear = rig(r.repo.settings.copy(autoApply = false))
            clear.service.runScheduledCheck()

            assertNull(
                clear.repo.recorded
                    .last()
                    .blockedReason,
            )
            assertNull(clear.service.status().lastBlockedReason)
        }

    @Test
    fun `a manual apply refused by the preflight carries the blocker as a field error`() =
        runTest {
            // The detail string is for a log. The client cannot localize English prose, so the
            // machine-readable name is what lets the back office say "there are still open bills"
            // in the operator's own language instead of "the update failed".
            val r = rig(settings(), verdict = PreflightVerdict.Blocked(PreflightVerdict.Reason.OPEN_TABLE_SESSION))

            val e = assertFailsWith<PosException> { r.service.applyNow() }

            assertEquals(PosErrorCode.UPDATE_BLOCKED, e.code)
            assertEquals(listOf("preflight" to "OPEN_TABLE_SESSION"), e.fields.map { it.field to it.code })
        }

    @Test
    fun `no download is reported once a staging run has ended`() =
        runTest {
            val r = rig(settings())
            r.service.stageNow()

            assertNull(r.service.status().download)
        }

    @Test
    fun `the internal channel queries the private repository`() =
        runTest {
            // The defect this pins, found 2026-09-04 by driving the real admin panel: the channel
            // and the repository were independent settings, and the panel had no control for the
            // repository -- so selecting "Intern" stored PRIVATE while leaving the PUBLIC repository
            // configured. Everything the private channel exists for was unreachable, and nothing in
            // the UI or the settings row said so. Only the outbound URL ever revealed it, which is
            // why this test asserts on the URL and not on the stored settings.
            val r = rig(settings(channel = UpdateChannel.PRIVATE, token = "github_pat_example"))

            r.service.runScheduledCheck()

            assertEquals("DDZzzzz12/POS-KMP", r.queriedRepo())
        }

    @Test
    fun `the public channel queries the open repository`() =
        runTest {
            val r = rig(settings(channel = UpdateChannel.PUBLIC))

            r.service.runScheduledCheck()

            assertEquals("DDZzzzz12/zenbon-pos", r.queriedRepo())
        }

    @Test
    fun `switching back to public leaves no private repository behind`() =
        runTest {
            // The reverse direction matters as much: a channel that changes without the repository
            // following it is the same defect pointing the other way, and it would send an operator
            // who returned to the public channel on querying the internal one.
            val r = rig(settings(channel = UpdateChannel.PRIVATE, token = "github_pat_example"))
            r.service.runScheduledCheck()
            assertEquals("DDZzzzz12/POS-KMP", r.queriedRepo(), "precondition: started on the private repo")

            val back = rig(settings(channel = UpdateChannel.PUBLIC))
            back.service.runScheduledCheck()

            assertEquals("DDZzzzz12/zenbon-pos", back.queriedRepo())
        }

    @Test
    fun `an undecryptable token stops the check before any request is made`() =
        runTest {
            val r = rig(settings(channel = UpdateChannel.PRIVATE, tokenUndecryptable = true))
            val summary = r.service.runScheduledCheck()

            assertEquals(0, r.recorder.requests.size, "no request may be made with an unreadable credential")
            assertEquals(UpdateServiceImpl.OUTCOME_TOKEN_UNREADABLE, r.repo.lastOutcome)
            assertContains(summary, "key")
            assertEquals(0, r.applyCalls)
        }

    @Test
    fun `a private channel sends the stored token to GitHub`() =
        runTest {
            val r = rig(settings(channel = UpdateChannel.PRIVATE, token = "ghp_secret"))
            r.service.runScheduledCheck()

            val auth =
                r.recorder.requests
                    .first()
                    .headers[HttpHeaders.Authorization]
            assertEquals("Bearer ghp_secret", auth)
        }

    @Test
    fun `an already staged release is not downloaded again`() =
        runTest {
            val first = rig(settings(autoApply = false))
            first.service.runScheduledCheck()
            val staged = first.repo.settings.stagedPath!!
            assertEquals(2, first.assetRequests())

            val second =
                rig(
                    settings(autoApply = false, availableVersion = "1.2.0", stagedPath = staged),
                ).also { it.stagingDir.deleteRecursively() }
            val summary = second.service.runScheduledCheck()

            assertEquals(0, second.assetRequests(), "the staged 1.2.0 artifact must be reused: $summary")
            assertTrue(File(staged).isFile)
        }

    @Test
    fun `a staged older release is not reused for a newer one`() =
        runTest {
            // The check has already recorded 1.2.0 as available; what is staged on disk is the 1.1.0 artifact.
            val staleDir =
                kotlin.io.path
                    .createTempDirectory("stale-staging")
                    .toFile()
            val stale = File(staleDir, "zenbon-pos-server-1.1.0.zip").also { it.writeText("old release bytes") }
            val r = rig(settings(autoApply = false, availableVersion = "1.2.0", stagedPath = stale.absolutePath))

            r.service.runScheduledCheck()

            assertEquals(2, r.assetRequests(), "1.2.0 must be downloaded, not satisfied by the staged 1.1.0 file")
            assertTrue(
                r.repo.settings.stagedPath!!
                    .contains("1.2.0"),
                "the staged path must now name 1.2.0",
            )
            staleDir.deleteRecursively()
        }

    // ---------- the delta route ----------

    /**
     * A release publishing the zip, `SHA256SUMS`, the bare jar and the jar's structure, served over
     * [MockEngine]. The installed jar and the published jar are real jars built by [JarFixtures],
     * so the planner, the reconstructor, the digest check and the stager all run on real ZIP bytes;
     * the asset API redirects to a storage host exactly as GitHub's does, so the range requests go
     * where they do in production.
     */
    private class DeltaWorld(
        val service: UpdateServiceImpl,
        val counts: Counts,
        val settings: FakeSettingsRepository,
        val publishedJar: ByteArray,
        val stagingDir: File,
    ) {
        /**
         * What actually went over the wire.
         *
         * [resolveProbes] is counted apart from [rangeRequests] because resolving a signed URL is
         * itself a one-byte range request; conflating them would make "no ranges were fetched"
         * unassertable. No plan in these fixtures asks for `bytes=0-0`, so the split is exact.
         */
        class Counts {
            /**
             * What the staging directory held while a range was being fetched, i.e. mid-rebuild.
             *
             * The stager's contract is that a file present in the staging directory verified when
             * it was written, and the rebuild is written long before any digest is compared. The
             * only moment that is observable from outside is while the fetch lambda is running.
             */
            var stagingDuringFetch: List<String>? = null
            var rangeRequests = 0
            var resolveProbes = 0
            var jarDownloads = 0
            var zipDownloads = 0
            var structureDownloads = 0
        }

        fun stagedFiles(): List<String> =
            stagingDir
                .listFiles()
                .orEmpty()
                .map { it.name }
                .sorted()

        fun stagedBytes(): ByteArray = File(settings.settings.stagedPath!!).readBytes()
    }

    private fun deltaWorld(
        structureAsset: Boolean = true,
        jarAsset: Boolean = true,
        corruptRanges: Boolean = false,
        rangesRefused: Boolean = false,
        installedJarUnrelated: Boolean = false,
        installedJarPresent: Boolean = true,
        jarChecksumPublished: Boolean = true,
        bailoutFraction: Double = UpdateServiceImpl.DEFAULT_DELTA_BAILOUT_FRACTION,
    ): DeltaWorld {
        val dir = Files.createTempDirectory("deltaworld").toFile()
        val stagingDir = File(dir, "staging").apply { mkdirs() }

        if (installedJarPresent) {
            val installedEntries =
                if (installedJarUnrelated) {
                    listOf("nothing/InCommon.class" to "unrelated body", "nothing/Else.class" to "also unrelated")
                } else {
                    JarFixtures.THREE_ENTRIES
                }
            JarFixtures.writeJar(File(dir, "pos-server.jar"), installedEntries)
        }

        val published =
            JarFixtures.writeJar(
                File(dir, "published.jar"),
                JarFixtures.THREE_ENTRIES.map {
                    if (it.first == "org/example/B.class") it.first to "class B has changed since 1.1.0" else it
                },
            )
        val publishedBytes = published.readBytes()
        val structureGz = ByteArrayOutputStream()
        GZIPOutputStream(structureGz).use { it.write(JarStructure.readCentralDirectory(published)) }

        val sums =
            buildString {
                append("${sha256Hex(artifact)}  zenbon-pos-server-v1.2.0.zip\n")
                if (jarChecksumPublished) append("${sha256Hex(publishedBytes)}  pos-server-v1.2.0.jar\n")
            }

        val counts = DeltaWorld.Counts()
        val assets =
            buildList {
                add("""{"id":1,"name":"zenbon-pos-server-v1.2.0.zip","size":${artifact.size}}""")
                add("""{"id":2,"name":"SHA256SUMS","size":${sums.length}}""")
                if (jarAsset) add("""{"id":3,"name":"pos-server-v1.2.0.jar","size":${publishedBytes.size}}""")
                if (jarAsset && structureAsset) {
                    add("""{"id":4,"name":"pos-server-v1.2.0.jar.cdir.gz","size":${structureGz.size()}}""")
                }
            }.joinToString(",")

        val engine =
            MockEngine { request ->
                val path = request.url.encodedPath
                val range = request.headers[HttpHeaders.Range]
                when {
                    path.endsWith("/releases/latest") ->
                        respond(
                            """{"tag_name":"v1.2.0","draft":false,"prerelease":false,"body":"notes","assets":[$assets]}""",
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    path.endsWith("/assets/2") -> respond(sums)
                    path.endsWith("/assets/4") -> {
                        counts.structureDownloads++
                        respond(structureGz.toByteArray())
                    }
                    path.endsWith("/assets/1") -> {
                        counts.zipDownloads++
                        respond(artifact)
                    }
                    // The asset API hands out a signed storage URL; the credential must not follow.
                    path.endsWith("/assets/3") ->
                        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, STORAGE_URL))
                    path == STORAGE_PATH ->
                        serveStorage(range, publishedBytes, counts, corruptRanges, rangesRefused, stagingDir)
                    else -> respond("unexpected ${request.url}", HttpStatusCode.NotFound)
                }
            }

        val repo = FakeSettingsRepository(settings())
        val releasesClient = GithubReleaseClient(HttpClient(engine))
        val serverStager = UpdateStager(stagingDir)
        val clientStore = ClientArtifactStore(Files.createTempDirectory("client").toFile(), serverStager)
        val service =
            UpdateServiceImpl(
                settings = repo,
                releases = releasesClient,
                stager = serverStager,
                clientStager = AndroidClientStager(releasesClient, clientStore),
                clientArtifacts = clientStore,
                preflight = clearPreflight(),
                applier = neverApplies(dir),
                currentVersion = current,
                cipher = TokenCipher(TokenCipher.generateKeyBase64()),
                resolveInstallDir = { dir },
                deltaBailoutFraction = bailoutFraction,
            )
        return DeltaWorld(service, counts, repo, publishedBytes, stagingDir)
    }

    /** The signed storage host: no credential is accepted here, and only ranges are served. */
    private fun MockRequestHandleScope.serveStorage(
        range: String?,
        publishedBytes: ByteArray,
        counts: DeltaWorld.Counts,
        corruptRanges: Boolean,
        rangesRefused: Boolean,
        stagingDir: File,
    ): HttpResponseData {
        if (range == null) {
            counts.jarDownloads++
            return respond(publishedBytes)
        }
        val (from, to) = range.removePrefix("bytes=").split("-").map { it.toInt() }
        // bytes=0-0 is GithubReleaseClient resolving the URL, not a planned fetch; see Counts.
        if (from == 0 && to == 0) {
            counts.resolveProbes++
        } else {
            counts.rangeRequests++
            if (counts.stagingDuringFetch == null) {
                counts.stagingDuringFetch =
                    stagingDir
                        .listFiles()
                        .orEmpty()
                        .map { it.name }
                        .sorted()
            }
            if (rangesRefused) return respond("no", HttpStatusCode.RequestedRangeNotSatisfiable)
        }
        val slice = publishedBytes.copyOfRange(from, to + 1)
        if (corruptRanges) slice.fill(0)
        return respond(
            slice,
            HttpStatusCode.PartialContent,
            headersOf(HttpHeaders.ContentRange, listOf("bytes $from-$to/${publishedBytes.size}")),
        )
    }

    private fun clearPreflight() =
        UpdatePreflight(
            hasOpenBusiness = { false },
            countOpenSessions = { 0 },
            hasOpenTseWork = { false },
            countRunningJobs = { 0 },
            freeDiskBytes = { 1_000_000_000 },
            requiredBytes = { 1024 },
        )

    /** Staging never installs; an applier that ran here would be the bug, not a fixture detail. */
    private fun neverApplies(dir: File) =
        UpdateApplier(
            installDir = dir,
            createBackup = { error("a staging run must not back up") },
            extractServerJar = { error("a staging run must not install") },
            exitProcess = { error("a staging run must not exit") },
        )

    @Test
    fun `stages through the delta when the release publishes a jar and its structure`() =
        runTest {
            val world = deltaWorld()

            world.service.stageNow()

            assertTrue(world.counts.rangeRequests > 0, "expected range requests")
            assertEquals(0, world.counts.jarDownloads, "the whole jar must not be fetched")
            assertEquals(0, world.counts.zipDownloads, "the archive must not be fetched")
            assertEquals(listOf("pos-server-1.2.0.jar"), world.stagedFiles())
            // The point of the whole feature: what a delta assembles is the published jar, byte
            // for byte -- which is also the only reason its digest can be checked against the
            // release's own SHA256SUMS line.
            assertTrue(world.publishedJar.contentEquals(world.stagedBytes()))
        }

    /**
     * The rebuild is assembled in a subdirectory, so the staging directory never holds an
     * unverified artifact.
     *
     * `UpdateStager`'s whole contract is that a file present in its directory matched its published
     * digest at the moment it was written. A rebuild is ~114 MB that exists long before any digest
     * is compared, so writing it there made that contract literally false: a power cut mid-rebuild
     * -- the exact scenario a POS host is built around -- left an unverified jar sitting in a
     * directory whose contract says the opposite. Nothing read it (only `settings.stagedPath` is
     * ever applied), but a contract that holds only because nobody looks is not a contract.
     *
     * Asserted mid-fetch rather than afterwards: by then the rebuild has been verified, moved and
     * pruned, and every arrangement looks identical.
     */
    @Test
    fun `a rebuild in progress is not written into the staging directory itself`() =
        runTest {
            val world = deltaWorld()

            world.service.stageNow()

            assertEquals(
                listOf("work"),
                world.counts.stagingDuringFetch,
                "only the work subdirectory may exist in staging while the jar is still unverified",
            )
            // And it is gone again once the verified artifact has been staged and the sweep has run.
            assertEquals(listOf("pos-server-1.2.0.jar"), world.stagedFiles())
        }

    @Test
    fun `falls back to the whole jar when the release publishes no structure`() =
        runTest {
            val world = deltaWorld(structureAsset = false)

            world.service.stageNow()

            assertEquals(0, world.counts.rangeRequests)
            assertEquals(1, world.counts.jarDownloads)
            assertEquals(listOf("pos-server-1.2.0.jar"), world.stagedFiles())
            assertTrue(world.publishedJar.contentEquals(world.stagedBytes()))
        }

    @Test
    fun `falls back to the whole jar when a range request is refused`() =
        runTest {
            val world = deltaWorld(rangesRefused = true)

            world.service.stageNow()

            assertTrue(world.counts.rangeRequests > 0, "the delta must have been attempted")
            assertEquals(1, world.counts.jarDownloads)
            assertTrue(world.publishedJar.contentEquals(world.stagedBytes()))
        }

    @Test
    fun `falls back to the whole jar when the rebuilt jar fails its digest`() =
        runTest {
            // The invariant that must survive a corrupt transport: a rebuild nobody can verify is
            // never staged, and the update still installs.
            val world = deltaWorld(corruptRanges = true)

            world.service.stageNow()

            assertTrue(world.counts.rangeRequests > 0)
            assertEquals(1, world.counts.jarDownloads, "a failed rebuild must still install")
            assertEquals(listOf("pos-server-1.2.0.jar"), world.stagedFiles())
            assertTrue(world.publishedJar.contentEquals(world.stagedBytes()), "the corrupt rebuild must not be staged")
        }

    @Test
    fun `falls back to the whole jar when there is no installed jar to copy from`() =
        runTest {
            val world = deltaWorld(installedJarPresent = false)

            world.service.stageNow()

            assertEquals(0, world.counts.structureDownloads, "nothing may be fetched to plan a delta that cannot run")
            assertEquals(1, world.counts.jarDownloads)
        }

    @Test
    fun `downloads the whole jar instead when the plan would fetch most of it`() =
        runTest {
            // Many small ranges eventually cost more than one sequential transfer, so past the
            // threshold the delta is abandoned before a single byte is fetched.
            val world = deltaWorld(installedJarUnrelated = true)

            world.service.stageNow()

            assertEquals(0, world.counts.rangeRequests, "past the bail-out, ranges are pointless")
            assertEquals(0, world.counts.resolveProbes, "not even the signed URL is worth resolving")
            assertEquals(1, world.counts.jarDownloads)
        }

    @Test
    fun `fetches the same unrelated jar by ranges when the threshold allows it`() =
        runTest {
            // The twin of the test above, on the same fixture: it is the threshold that decides,
            // not some other property of an unrelated installed jar. Without the comparison this
            // fixture would take the delta path and the bail-out test would fail.
            val world = deltaWorld(installedJarUnrelated = true, bailoutFraction = 1.0)

            world.service.stageNow()

            assertTrue(world.counts.rangeRequests > 0)
            assertEquals(0, world.counts.jarDownloads)
            assertTrue(world.publishedJar.contentEquals(world.stagedBytes()))
        }

    @Test
    fun `a release that publishes no bare jar still stages the zip`() =
        runTest {
            // The old path, unchanged: releases published before the jar asset existed must keep
            // updating exactly as they did.
            val world = deltaWorld(jarAsset = false)

            world.service.stageNow()

            assertEquals(0, world.counts.rangeRequests)
            assertEquals(0, world.counts.jarDownloads)
            assertEquals(1, world.counts.zipDownloads)
            assertEquals(listOf("zenbon-pos-server-1.2.0.zip"), world.stagedFiles())
        }

    @Test
    fun `a release whose jar carries no checksum line still stages the zip`() =
        runTest {
            // The archive is the floor, not merely the older shape: a release that publishes a jar
            // the checksum file does not name must install exactly as it did before any of this
            // existed, rather than failing because the newer, cheaper artifact was unusable.
            val world = deltaWorld(structureAsset = false, jarChecksumPublished = false)

            world.service.stageNow()

            assertEquals(1, world.counts.jarDownloads, "the jar was tried")
            assertEquals(1, world.counts.zipDownloads, "and the archive carried the update")
            assertEquals(listOf("zenbon-pos-server-1.2.0.zip"), world.stagedFiles())
        }

    @Test
    fun `the staged jar survives the prune that follows it`() =
        runTest {
            // Pruning by version derives the *zip* name, so the sweep right after a delta deleted
            // the artifact of that very run: success reported, nothing left to install.
            val world = deltaWorld()
            File(world.stagingDir, "zenbon-pos-server-1.1.9.zip").writeText("an older staging")

            world.service.stageNow()

            assertEquals(listOf("pos-server-1.2.0.jar"), world.stagedFiles())
            assertTrue(File(world.settings.settings.stagedPath!!).isFile, "the staged path must still name a file")
        }

    private companion object {
        const val STORAGE_PATH = "/signed/pos-server.jar"
        const val STORAGE_URL = "https://objects.example.com$STORAGE_PATH?signature=abc"
    }
}
