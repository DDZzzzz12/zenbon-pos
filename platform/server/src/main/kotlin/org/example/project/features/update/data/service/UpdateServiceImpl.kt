package org.example.project.features.update.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import org.example.project.core.domain.dto.update.UpdateDto
import org.example.project.core.error.FieldError
import org.example.project.core.error.PosErrorCode
import org.example.project.core.utils.SemanticVersion
import org.example.project.features.update.data.crypto.TokenCipher
import org.example.project.features.update.data.remote.GithubReleaseClient
import org.example.project.features.update.domain.models.AvailableUpdate
import org.example.project.features.update.domain.models.ByteRange
import org.example.project.features.update.domain.models.PreflightVerdict
import org.example.project.features.update.domain.models.ReleaseLookup
import org.example.project.features.update.domain.models.UpdateChannel
import org.example.project.features.update.domain.models.UpdateCheckOutcome
import org.example.project.features.update.domain.models.UpdateSettings
import org.example.project.features.update.domain.repository.UpdateSettingsRepository
import org.example.project.features.update.domain.service.UpdateService
import org.example.project.infrastructure.errors.PosException
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant
import java.util.zip.GZIPInputStream

/**
 * The nightly check, in order: ask, stage, decide, install.
 *
 * ### Auto-apply is opt-in, and that is the whole safety story
 *
 * Checking is harmless; installing unattended on a fiscal till is not. With `autoApply` off the
 * run stops after staging, which is the useful half anyway -- the operator gets a verified
 * artifact sitting on disk and a one-click apply the next morning.
 *
 * ### A blocked preflight keeps the staged artifact
 *
 * Nothing here deletes a staging on a refusal. A shop that closes late would otherwise re-download
 * two hundred megabytes every single night and never install anything, and the symptom -- bandwidth
 * -- looks nothing like the cause. The next run sees the same version already staged and skips
 * straight past the download.
 *
 * ### An unreadable token never becomes an anonymous request
 *
 * On a database restored to another host the sealed token cannot be opened. Querying GitHub
 * unauthenticated from there would answer 404 for a private repository, which
 * [GithubReleaseClient] can only read as "nothing published" when no token was sent -- so the shop
 * would be told it is up to date, forever. The check therefore stops before the first request and
 * says which problem it is.
 *
 * ### Recording happens exactly once per run
 *
 * [UpdateSettingsRepository.recordCheck] writes the outcome and the release fields together, so
 * two records in one run could leave a version behind that a later arm did not mean. Every path
 * ends in exactly one [record] call, and on the apply path it happens *before* the applier, which
 * does not return.
 *
 * It is a deliberate exception to the "at most three parameters" guidance: each collaborator owns
 * one step of a pipeline, and grouping them into a parameter object would only rename them.
 */
class UpdateServiceImpl(
    private val settings: UpdateSettingsRepository,
    private val releases: GithubReleaseClient,
    private val stager: UpdateStager,
    private val clientStager: AndroidClientStager,
    private val clientArtifacts: ClientArtifactStore,
    private val preflight: UpdatePreflight,
    private val applier: UpdateApplier,
    private val currentVersion: SemanticVersion,
    private val cipher: TokenCipher,
    private val planner: DeltaPlanner = DeltaPlanner(),
    /** Where the running `pos-server.jar` lives; a delta copies its blocks out of that file. */
    private val resolveInstallDir: () -> File,
    /**
     * Past this fraction of the published jar, the delta is abandoned for one whole download.
     *
     * Many small ranges are not free: each is a request, and past roughly half the file the round
     * trips cost more than a single sequential transfer of everything. Injectable so a test can
     * pin both sides of the decision with one fixture rather than having to build a jar of a
     * particular size.
     */
    private val deltaBailoutFraction: Double = DEFAULT_DELTA_BAILOUT_FRACTION,
    private val clock: () -> Instant = { Instant.now() },
    /** What the staging run in flight has downloaded, surfaced on [status] for the back office. */
    private val downloads: UpdateDownloadTracker = UpdateDownloadTracker(),
) : UpdateService {
    private val log = LoggerFactory.getLogger(UpdateServiceImpl::class.java)

    override suspend fun runScheduledCheck(): String = runCheck { current, update -> handleFound(current, update) }

    override suspend fun status(): UpdateDto.StatusDto = settings.load().toStatus()

    override suspend fun settings(): UpdateDto.SettingsDto = settings.load().toSettings()

    override suspend fun saveSettings(request: UpdateDto.UpdateSettingsRequestDto): UpdateDto.SettingsDto =
        settings.save(UpdateSourceValidator.validate(request)).toSettings()

    override suspend fun setToken(token: String): UpdateDto.SettingsDto {
        if (token.isBlank()) {
            throw PosException(PosErrorCode.VALIDATION_FAILED, HttpStatusCode.BadRequest, "token must not be blank")
        }
        // Refused rather than attempted: TokenCipher.encrypt throws without a key, and a 500 would
        // tell the operator nothing about the one thing they have to fix on the host.
        if (!cipher.isAvailable) {
            throw PosException(
                PosErrorCode.UPDATE_TOKEN_KEY_MISSING,
                HttpStatusCode.Conflict,
                "no UPDATE_TOKEN_KEY is provisioned on this host, so a token could never be read back — " +
                    "set it in the systemd EnvironmentFile and retry",
            )
        }
        settings.setToken(token)
        return settings.load().toSettings()
    }

    override suspend fun clearToken(): UpdateDto.SettingsDto {
        settings.clearToken()
        return settings.load().toSettings()
    }

    override suspend fun checkNow(): UpdateDto.CheckResultDto =
        finish(runCheck { _, update -> record(OUTCOME_AVAILABLE, update) { "${update.version} available" } })

    override suspend fun stageNow(): UpdateDto.CheckResultDto = finish(runCheck { current, update -> stageOnly(current, update) })

    override suspend fun applyNow(): UpdateDto.ApplyStartedDto {
        // Preflight first, deliberately: an operator who is told "open bills" can go and close
        // them, whereas "nothing staged" sent first would send them to stage an artifact they
        // still could not install.
        val verdict = preflight.evaluate()
        if (verdict is PreflightVerdict.Blocked) {
            throw PosException(
                PosErrorCode.UPDATE_BLOCKED,
                HttpStatusCode.Conflict,
                "update not installed: ${describe(verdict.reason)}",
                // The prose above is for a log; this is for the operator's screen. A client cannot
                // localize an English sentence, and every back-office language needs its own
                // wording per blocker, so the reason travels as a name the client resolves against
                // `UpdateBlockedReason`.
                fields = listOf(FieldError(field = "preflight", code = verdict.reason.name)),
            )
        }

        val current = settings.load()
        val version = current.availableVersion ?: throw notStaged()
        val staged = current.stagedPath?.let(::File)?.takeIf { it.isFile } ?: throw notStaged()

        applier.apply(version, staged)
        return UpdateDto.ApplyStartedDto(
            version = version,
            message = "installing $version; the server restarts and the connection will drop",
        )
    }

    /**
     * The half of a check that is identical everywhere: refuse to ask anonymously with an
     * unreadable token, ask, and record every answer that is not a release. Only what happens with
     * a release found differs between the nightly job, a manual check and a manual stage, so that
     * is the one thing the caller supplies.
     */
    private suspend fun runCheck(onFound: suspend (UpdateSettings, AvailableUpdate) -> String): String {
        val current = settings.load()
        if (current.channel == UpdateChannel.PRIVATE && current.tokenUndecryptable) {
            return record(OUTCOME_TOKEN_UNREADABLE, null) {
                "check skipped: the stored GitHub token cannot be opened with this host's key — re-enter it"
            }
        }

        return when (val lookup = releases.latest(current.repo(), current.token, currentVersion)) {
            is ReleaseLookup.UpToDate -> record(OUTCOME_UP_TO_DATE, null) { "up to date ($currentVersion)" }
            is ReleaseLookup.AuthFailed ->
                record(OUTCOME_AUTH_FAILED, null) {
                    "check failed: GitHub rejected the stored token — re-enter it in the back office"
                }
            is ReleaseLookup.Unreachable ->
                record(OUTCOME_UNREACHABLE, null) { "check failed: GitHub unreachable (${lookup.reason})" }
            is ReleaseLookup.Found -> onFound(current, lookup.update)
        }
    }

    /** Download and verify, never install. The manual counterpart of [handleFound]'s first half. */
    private suspend fun stageOnly(
        current: UpdateSettings,
        update: AvailableUpdate,
    ): String {
        try {
            stage(current, update)
        } catch (e: PosException) {
            log.warn("Staging ${update.version} failed", e)
            return record(OUTCOME_STAGING_FAILED, update) { "${update.version} available but staging failed: ${e.message}" }
        }
        return record(OUTCOME_STAGED, update) { "${update.version} staged, not applied" }
    }

    private suspend fun finish(summary: String) = UpdateDto.CheckResultDto(summary = summary, status = settings.load().toStatus())

    private fun notStaged() =
        PosException(
            PosErrorCode.UPDATE_NOT_STAGED,
            HttpStatusCode.Conflict,
            "nothing is staged to install — run a check and a stage first",
        )

    private fun UpdateSettings.toStatus(): UpdateDto.StatusDto =
        UpdateDto.StatusDto(
            currentVersion = currentVersion.toString(),
            channel = channel.name,
            autoApply = autoApply,
            tokenState = tokenState(),
            lastCheckedAt = lastCheckedAt?.toString(),
            lastCheckOutcome = lastCheckOutcome,
            lastBlockedReason = lastBlockedReason,
            availableVersion = availableVersion,
            availableNotes = availableNotes,
            staged = stagedPath?.let(::File)?.isFile == true,
            stagedClientVersionCode = clientArtifacts.current()?.versionCode,
            download = downloads.snapshot(),
        )

    private fun UpdateSettings.toSettings(): UpdateDto.SettingsDto =
        UpdateDto.SettingsDto(
            channel = channel.name,
            // Reported, not stored: the repository follows the channel.
            repoOwner = channel.repoOwner,
            repoName = channel.repoName,
            apiBaseUrl = apiBaseUrl,
            autoApply = autoApply,
            tokenState = tokenState(),
            tokenPreview = token?.let(::mask),
            tokenSetAt = tokenSetAt?.toString(),
        )

    private fun UpdateSettings.tokenState(): UpdateDto.TokenState =
        when {
            tokenUndecryptable -> UpdateDto.TokenState.UNREADABLE
            token != null -> UpdateDto.TokenState.SET
            else -> UpdateDto.TokenState.NONE
        }

    /**
     * At most the last four characters, so an operator can tell which token is stored.
     *
     * A short token is shown as mask only: four of six characters is not a hint, it is most of the
     * secret.
     */
    private fun mask(token: String): String = if (token.length >= MIN_LENGTH_FOR_PREVIEW) "••••" + token.takeLast(4) else "••••"

    /** Stage first, then decide. A release that will not verify is never worth a preflight. */
    private suspend fun handleFound(
        current: UpdateSettings,
        update: AvailableUpdate,
    ): String {
        val staged =
            try {
                stage(current, update)
            } catch (e: PosException) {
                log.warn("Staging ${update.version} failed", e)
                return record(OUTCOME_STAGING_FAILED, update) { "${update.version} available but staging failed: ${e.message}" }
            }

        if (!current.autoApply) {
            return record(OUTCOME_STAGED, update) { "${update.version} available, staged, not applied (auto-apply off)" }
        }

        val verdict = preflight.evaluate()
        if (verdict is PreflightVerdict.Blocked) {
            // The staging is deliberately left in place; see the class KDoc.
            //
            // The reason is stored, not merely logged. It used to live only in the summary string
            // the scheduler keeps, so the back office showed "staged, ready to install" for a
            // server that had been turned away every night since -- the operator could not see that
            // the system was trying and failing.
            return record(OUTCOME_SKIPPED, update, verdict.reason) {
                "${update.version} staged, skipped: ${describe(verdict.reason)}"
            }
        }

        // Recorded before the applier, which swaps the jar and exits without returning.
        val summary = record(OUTCOME_APPLYING, update) { "${update.version} applied; restarting" }
        applier.apply(update.version.toString(), staged)
        return summary
    }

    /**
     * Stages the server artifact, then -- best effort -- the Android build tills will download.
     *
     * The client is staged **after** the server artifact and outside its idempotency check on
     * purpose: `stageServer` returns early when this version is already staged, and a host that
     * staged the jar before this feature existed must still get its APK on the next check.
     */
    private suspend fun stage(
        current: UpdateSettings,
        update: AvailableUpdate,
    ): File {
        try {
            val staged = stageServer(current, update)
            clientStager.stage(current.repo(), current.token, update, downloads)
            return staged
        } finally {
            downloads.clear()
        }
    }

    /**
     * Returns the verified artifact for [update], reusing the one already on disk when the last
     * check staged this very version.
     */
    private suspend fun stageServer(
        current: UpdateSettings,
        update: AvailableUpdate,
    ): File {
        alreadyStaged(current, update)?.let { return it }

        downloads.start(UpdateDto.DownloadPhase.SERVER)
        val repo = current.repo()
        // The checksum first: a release that publishes none is refused before the large download.
        val checksumAssetId =
            update.checksumAssetId
                ?: throw PosException(
                    PosErrorCode.UPDATE_CHECKSUM_MISMATCH,
                    HttpStatusCode.BadGateway,
                    "staging ${update.version} refused: the release publishes no SHA256SUMS asset",
                )
        val checksums = String(releases.downloadAsset(repo, current.token, checksumAssetId), Charsets.UTF_8)

        val staged =
            stageByDelta(current, update, repo, checksums)
                ?: stageByFullDownload(current, update, repo, checksums)

        // Keyed on the file rather than on the version: the two paths stage differently named
        // artifacts, and a version-derived name would prune away whichever one this run produced.
        stager.pruneExcept(staged)
        settings.setStagedPath(staged.absolutePath)
        return staged
    }

    /**
     * Rebuilds the published jar from the installed one plus a few fetched ranges, or returns null.
     *
     * Null is the whole contract. A delta is a transport optimisation and nothing more, so every
     * way this can fail -- a release that publishes no structure, no jar on disk to copy from, a
     * range the storage host refuses, a signed URL that expired twice over, a doctored central
     * directory, a rebuild whose digest does not match -- must leave the caller free to install
     * the update the slow way. Nothing here decides *what* is installed: [UpdateStager.stageJar]
     * still refuses anything whose published digest does not verify, exactly as on the full path.
     *
     * The bail-out above it is not a failure but a measurement: past [deltaBailoutFraction] of the
     * jar, fetching it in pieces costs more than fetching it once.
     */
    private suspend fun stageByDelta(
        current: UpdateSettings,
        update: AvailableUpdate,
        repo: GithubReleaseClient.Repo,
        checksums: String,
    ): File? {
        val structureAssetId = update.structureAssetId ?: return skipDelta(update, "the release publishes no jar structure")
        val jarAssetId = update.jarAssetId ?: return skipDelta(update, "the release publishes no bare jar")
        val installed =
            File(resolveInstallDir(), INSTALLED_JAR).takeIf { it.isFile }
                ?: return skipDelta(update, "no installed $INSTALLED_JAR to copy blocks from")

        val rebuilt = File(stager.workDir, "rebuilt-${update.version}.jar")
        return try {
            val target = JarStructure.parse(gunzip(releases.downloadAsset(repo, current.token, structureAssetId)))
            val plan = planner.plan(JarStructure.of(installed), target)
            if (plan.fetchBytes > target.totalSize * deltaBailoutFraction) {
                return skipDelta(
                    update,
                    "the delta would fetch ${plan.fetchBytes} of ${target.totalSize} bytes",
                )
            }

            downloads.start(UpdateDto.DownloadPhase.SERVER, plan.fetchBytes)
            val fetchRange = rangeFetcher(current, repo, jarAssetId)
            val digest =
                DeltaReconstructor { range -> fetchRange(range).also { downloads.advance(it.size.toLong()) } }
                    .reconstruct(installed, target, plan, rebuilt)
            log.info(
                "Update {}: rebuilt the jar from {} ranges ({} bytes) instead of {}",
                update.version,
                plan.fetch.size,
                plan.fetchBytes,
                target.totalSize,
            )
            stager.stageJar(update.version.toString(), rebuilt, digest, checksums)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Update {}: delta download failed, falling back to the full artifact: {}", update.version, e.toString())
            null
        } finally {
            // The failure path's cleanup, and only that: stageJar CONSUMES the rebuild by moving
            // it, so after a success there is nothing here to delete.
            rebuilt.delete()
        }
    }

    /**
     * Fetches one range from the signed storage URL, re-resolving it when it has expired.
     *
     * A signed URL lives for minutes and a delta may spend longer than that fetching, so expiry
     * mid-download is ordinary rather than exceptional -- and it is the one failure the caller can
     * do something about, which is why [GithubReleaseClient.SignedUrlExpired] is a type and not a
     * phrase in a message. Renewing the URL is deliberately not a retry policy: nothing here
     * re-issues a range that failed for any other reason, there is no backoff, and after
     * [MAX_URL_REFRESHES] renewals the delta is abandoned for the full download like any other
     * failure.
     */
    private fun rangeFetcher(
        current: UpdateSettings,
        repo: GithubReleaseClient.Repo,
        jarAssetId: Long,
    ): suspend (ByteRange) -> ByteArray {
        var url: String? = null
        var refreshes = 0
        return { range ->
            if (url == null) url = releases.resolveAssetUrl(repo, current.token, jarAssetId)
            try {
                releases.downloadRange(url!!, range)
            } catch (expired: GithubReleaseClient.SignedUrlExpired) {
                if (++refreshes > MAX_URL_REFRESHES) throw expired
                url = releases.resolveAssetUrl(repo, current.token, jarAssetId)
                releases.downloadRange(url!!, range)
            }
        }
    }

    /** Logs why a delta was not attempted and answers null, which is how the caller reads "fall back". */
    private fun skipDelta(
        update: AvailableUpdate,
        reason: String,
    ): File? {
        log.info("Update {}: downloading the whole artifact — {}", update.version, reason)
        return null
    }

    /**
     * The published jar alone when the release carries a usable one, otherwise the whole deploy zip.
     *
     * The jar is preferred because it is the only file an update replaces and it is a third of the
     * archive. The zip arm is not merely the older shape: it is the floor everything else falls
     * back onto, so it is the last arm and the only one allowed to fail the staging. A release that
     * publishes a jar asset but no `.jar` line in `SHA256SUMS` -- or one whose jar does not match
     * it -- must still install exactly as it did before any of this existed, and it is not this
     * method's place to decide that such a release is unusable.
     *
     * The jar is streamed to disk rather than held as a `ByteArray` -- ~127 MB in a POS host's
     * heap, only to be written straight back out, is waste the delta path exists to avoid.
     */
    private suspend fun stageByFullDownload(
        current: UpdateSettings,
        update: AvailableUpdate,
        repo: GithubReleaseClient.Repo,
        checksums: String,
    ): File =
        stageBareJar(current, update, repo, checksums)
            ?: stager.stage(
                update.version.toString(),
                releases.downloadAsset(repo, current.token, update.serverAssetId),
                checksums,
            )

    /** The whole published jar, or null when the release has none or it will not verify. */
    private suspend fun stageBareJar(
        current: UpdateSettings,
        update: AvailableUpdate,
        repo: GithubReleaseClient.Repo,
        checksums: String,
    ): File? {
        val jarAssetId = update.jarAssetId ?: return null
        val downloaded = File(stager.workDir, "downloaded-${update.version}.jar")
        return try {
            downloads.start(UpdateDto.DownloadPhase.SERVER)
            val digest = releases.downloadAssetTo(repo, current.token, jarAssetId, downloaded) { done, total -> downloads.report(done, total) }
            stager.stageJar(update.version.toString(), downloaded, digest, checksums)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Update {}: the published jar could not be staged, falling back to the archive: {}", update.version, e.toString())
            null
        } finally {
            // Only the failure path leaves anything behind — see stageByDelta's finally.
            downloaded.delete()
        }
    }

    private fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(bytes.inputStream()).use { it.readBytes() }

    /**
     * The recorded version, the file on disk and the file's own name must agree; any one alone proves nothing.
     *
     * The name matters because a check records the newest release as `availableVersion` BEFORE staging runs:
     * with an older release still staged, the version test alone passed and the stale artifact was reused as
     * if it were the new release (found in the in-app update rehearsal: "1.1.4 staged" installed the 1.1.3 jar).
     * Both staged names carry the version: `zenbon-pos-server-<v>.zip` and `pos-server-<v>.jar`.
     */
    private fun alreadyStaged(
        current: UpdateSettings,
        update: AvailableUpdate,
    ): File? {
        if (current.availableVersion != update.version.toString()) return null
        val staged = current.stagedPath?.let(::File) ?: return null
        return staged.takeIf { it.isFile && it.name.contains("-${update.version}.") }
    }

    private suspend fun record(
        outcome: String,
        update: AvailableUpdate?,
        blockedReason: PreflightVerdict.Reason? = null,
        summary: () -> String,
    ): String {
        settings.recordCheck(
            UpdateCheckOutcome(
                checkedAt = clock(),
                outcome = outcome,
                availableVersion = update?.version?.toString(),
                availableNotes = update?.releaseNotes,
                availableAssetId = update?.serverAssetId,
                blockedReason = blockedReason?.name,
            ),
        )
        return summary()
    }

    /**
     * The repository to query, taken from the [channel] rather than the stored `repo_owner`/
     * `repo_name` columns. Those columns are kept in step by `save()`, but deriving here as well
     * means a row edited by hand — or written by an older build — still cannot point the private
     * channel at the public repository.
     */
    private fun UpdateSettings.repo() = GithubReleaseClient.Repo(baseUrl = apiBaseUrl, owner = channel.repoOwner, name = channel.repoName)

    /** Operator-facing wording for the blocker; the enum name alone reads like a stack trace. */
    private fun describe(reason: PreflightVerdict.Reason): String =
        when (reason) {
            PreflightVerdict.Reason.OPEN_BUSINESS -> "open business"
            PreflightVerdict.Reason.OPEN_TABLE_SESSION -> "an open table session"
            PreflightVerdict.Reason.OPEN_TSE_WORK -> "unfinished TSE work"
            PreflightVerdict.Reason.JOB_RUNNING -> "another job is running"
            PreflightVerdict.Reason.INSUFFICIENT_DISK -> "not enough free disk"
        }

    companion object {
        /** The file an update replaces, and the only one a delta reads blocks out of. */
        private const val INSTALLED_JAR = "pos-server.jar"

        /** Half the jar: past it, many ranges cost more than one sequential transfer. */
        const val DEFAULT_DELTA_BAILOUT_FRACTION = 0.5

        /**
         * How often a signed storage URL may be renewed within one delta.
         *
         * Bounded so an endpoint answering 401 forever ends in a full download rather than a loop.
         */
        private const val MAX_URL_REFRESHES = 3

        /** Shortest token whose last four characters are a hint rather than most of the secret. */
        private const val MIN_LENGTH_FOR_PREVIEW = 12

        const val OUTCOME_UP_TO_DATE = "UP_TO_DATE"
        const val OUTCOME_AUTH_FAILED = "AUTH_FAILED"
        const val OUTCOME_UNREACHABLE = "UNREACHABLE"
        const val OUTCOME_TOKEN_UNREADABLE = "TOKEN_UNREADABLE"
        const val OUTCOME_STAGING_FAILED = "STAGING_FAILED"
        const val OUTCOME_AVAILABLE = "UPDATE_AVAILABLE"
        const val OUTCOME_STAGED = "UPDATE_STAGED"
        const val OUTCOME_SKIPPED = "UPDATE_SKIPPED"
        const val OUTCOME_APPLYING = "UPDATE_APPLYING"
    }
}
