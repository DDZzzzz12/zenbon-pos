package org.example.project.features.issuereport.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.issuereport.IssueReportDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.issuereport.domain.models.IssueCreateResult
import org.example.project.features.issuereport.domain.models.IssueReportSettings
import org.example.project.features.issuereport.domain.models.ReportContext
import org.example.project.features.issuereport.domain.models.ReportSnapshot
import org.example.project.features.issuereport.domain.models.ReportTarget
import org.example.project.features.issuereport.domain.models.UpdateTokenSource
import org.example.project.features.issuereport.domain.repository.IssueReportSettingsRepository
import org.example.project.features.issuereport.domain.service.IssueReportService
import org.example.project.features.issuereport.domain.tracker.IssueTracker
import org.example.project.features.update.data.crypto.TokenCipher
import org.example.project.features.update.data.remote.GithubReleaseClient
import org.example.project.features.update.data.service.UpdateSourceValidator
import org.example.project.features.update.domain.models.UpdateChannel
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.errorHandling.ExceptionConverter
import org.example.project.utils.log.AuditEntityRef
import org.slf4j.LoggerFactory
import java.net.URI
import java.time.Instant

/**
 * Files operator problem reports as issues. The title, description and log text are never logged
 * and never named in the audit row; the token is never logged or returned.
 *
 * ### Preview equals send
 *
 * [preview] builds the report once and stores it in [previewCache]; a send naming that preview posts
 * the stored title and body verbatim, never a rebuild, so log lines written in between (or a moved
 * clock, or a renamed shop) can never reach GitHub unseen. A send without a preview is allowed only
 * without a log excerpt, where a fresh build exposes nothing the operator did not type.
 *
 * ### Which token is sent
 *
 * The report's own token, unless the Administrator explicitly chose `useUpdateToken`: then the update
 * feature's token from [updateTokenSource], and the own token is ignored (kept, never sent). There is
 * no fallback in either direction. The update token may only ever go to the host the update feature
 * is configured for, so [IssueReportSettings.apiBaseUrl] must equal [UpdateTokenSource.apiBaseUrl] —
 * checked when the choice is saved and again on every send, because the update source can move later.
 */
class IssueReportServiceImpl(
    private val settings: IssueReportSettingsRepository,
    private val tracker: IssueTracker,
    private val cipher: TokenCipher,
    private val updateChannel: suspend () -> UpdateChannel,
    /** Read-only view of the update feature's token and API address; never written from here. */
    private val updateTokenSource: suspend () -> UpdateTokenSource,
    private val shopName: suspend () -> String?,
    private val logTail: () -> String?,
    private val appVersion: String,
    private val platform: String,
    private val rateLimiter: IssueRateLimiter = IssueRateLimiter(),
    private val clock: () -> Instant = { Instant.now() },
    private val previewCache: PreviewCache = PreviewCache(),
) : IssueReportService {
    private val log = LoggerFactory.getLogger(IssueReportServiceImpl::class.java)

    override suspend fun settings(): IssueReportDto.SettingsDto {
        val stored = settings.load()
        return stored.toDto(resolveTarget(stored), updateTokenSource().state())
    }

    override suspend fun saveSettings(request: IssueReportDto.UpdateSettingsRequestDto): IssueReportDto.SettingsDto {
        validateTarget(request)
        val baseUrl = request.apiBaseUrl.trim().trimEnd('/')
        val current = settings.load()
        val source = updateTokenSource()
        if (request.useUpdateToken) requireUpdateTokenAllowed(baseUrl, source, enabling = !current.useUpdateToken)
        clearTokenIfHostChanges(current, baseUrl)
        val saved =
            settings.saveTarget(
                owner = request.owner.trim(),
                repo = request.repo.trim(),
                apiBaseUrl = baseUrl,
                isPublic = request.isPublic,
                useUpdateToken = request.useUpdateToken,
            )
        return saved.toDto(resolveTarget(saved), source.state())
    }

    /**
     * The save-time half of the update-token guard. The host rule comes first and holds whenever the
     * choice is on; the "a readable update token exists" rule only refuses turning it ON (mirroring the
     * disabled switch), so a choice that is already on can still be saved, or switched off, after the
     * update token went away — the send refuses then, naming the cause.
     */
    private fun requireUpdateTokenAllowed(
        reportBaseUrl: String,
        source: UpdateTokenSource,
        enabling: Boolean,
    ) {
        if (!sameApiEndpoint(reportBaseUrl, source.apiBaseUrl)) {
            throw PosException(
                PosErrorCode.ISSUE_REPORT_UPDATE_TOKEN_HOST_MISMATCH,
                HttpStatusCode.BadRequest,
                "the update token may only be sent to the update source's API address (${source.apiBaseUrl}); " +
                    "set the problem-report API address to it, or use a dedicated problem-report token",
            )
        }
        if (!enabling || source.token != null) return
        throw validation(
            if (source.tokenUndecryptable) {
                "the update token cannot be read on this host; re-enter it under Settings → Updates before reusing it for problem reports"
            } else {
                "no update token is stored; store one under Settings → Updates before reusing it for problem reports"
            },
        )
    }

    override suspend fun setToken(token: String): IssueReportDto.SettingsDto {
        if (token.isBlank()) throw validation("token must not be blank")
        if (!cipher.isAvailable) throw keyMissing()
        settings.setToken(token.trim())
        log.info("Problem-report token stored")
        return settings()
    }

    override suspend fun clearToken(): IssueReportDto.SettingsDto {
        settings.clearToken()
        return settings()
    }

    override suspend fun preview(
        employeeId: String,
        reporterRole: String,
        request: IssueReportDto.ReportRequestDto,
    ): IssueReportDto.PreviewDto {
        requireWithinLimits(request)
        val target = resolveTarget(settings.load())
        requireLogsAllowed(target, request.includeLogs)
        val excerpt = if (request.includeLogs) logTail() else null
        val built = buildReport(reporterRole, request, target, excerpt)
        val snapshot =
            ReportSnapshot(
                employeeId = employeeId,
                title = built.title,
                body = built.body,
                target = target,
                hasLogExcerpt = excerpt != null,
                createdAt = clock(),
            )
        val previewId = previewCache.put(snapshot)
        return IssueReportDto.PreviewDto(built.title, built.body, "${target.owner}/${target.repo}", target.isPublic, previewId)
    }

    override suspend fun submit(
        employeeId: String,
        deviceId: String,
        reporterRole: String,
        request: IssueReportDto.ReportRequestDto,
    ): IssueReportDto.CreatedDto =
        ExceptionConverter.catchingThrows(
            context = "submitIssueReport(employeeId=$employeeId)",
            employeeId = employeeId,
            operation = "CREATE_ISSUE_REPORT",
            entityType = "ISSUE_REPORT",
            deviceId = deviceId,
            auditEntity = ::namesTheIssue,
        ) { sendReport(employeeId, reporterRole, request) }

    /** Names the issue by its number — never by title or body, which are operator free text. */
    private fun namesTheIssue(created: IssueReportDto.CreatedDto) = AuditEntityRef(id = created.issueNumber.toString())

    private suspend fun sendReport(
        employeeId: String,
        reporterRole: String,
        request: IssueReportDto.ReportRequestDto,
    ): IssueReportDto.CreatedDto {
        requireWithinLimits(request)
        val stored = settings.load()
        val target = resolveTarget(stored)
        val previewId = request.previewId ?: return sendFresh(stored, target, reporterRole, request)
        return sendSnapshot(stored, target, employeeId, previewId, request)
    }

    /** No preview named: only a log-free report may be built here, since nothing unseen can be in it. */
    private suspend fun sendFresh(
        stored: IssueReportSettings,
        target: ReportTarget,
        reporterRole: String,
        request: IssueReportDto.ReportRequestDto,
    ): IssueReportDto.CreatedDto {
        if (request.includeLogs) throw validation("preview the report before sending it with a log excerpt")
        val token = resolveToken(stored)
        val built = buildReport(reporterRole, request, target, logExcerpt = null)
        return post(target, token, built.title, built.body)
    }

    /**
     * Posts the stored snapshot verbatim. The snapshot is taken (single use) and put back only when
     * the attempt did not create an issue, so the operator can retry the same text.
     */
    private suspend fun sendSnapshot(
        stored: IssueReportSettings,
        target: ReportTarget,
        employeeId: String,
        previewId: String,
        request: IssueReportDto.ReportRequestDto,
    ): IssueReportDto.CreatedDto {
        val snapshot = previewCache.take(previewId, employeeId) ?: throw previewAgain()
        if (snapshot.target != target) throw previewAgain()
        var created = false
        try {
            requireLogsAllowed(target, snapshot.hasLogExcerpt || request.includeLogs)
            val token = resolveToken(stored)
            return post(target, token, snapshot.title, snapshot.body).also { created = true }
        } finally {
            if (!created) previewCache.restore(previewId, snapshot)
        }
    }

    /**
     * The token for this send, from exactly the source the Administrator chose — never a fallback.
     * With `useUpdateToken` the host rule is checked again here: the update source may have moved
     * since the settings were saved, and then nothing is sent (`ISSUE_REPORT_NOT_CONFIGURED`). A
     * missing or unreadable *update* token is `ISSUE_REPORT_UPDATE_TOKEN_UNAVAILABLE`, because its
     * remedy is under Settings → Updates; a missing or unreadable own token stays `ISSUE_REPORT_NOT_CONFIGURED`.
     */
    private suspend fun resolveToken(stored: IssueReportSettings): String {
        if (!stored.useUpdateToken) {
            return stored.token ?: throw notConfigured(
                if (stored.tokenUndecryptable) {
                    "the stored problem-report GitHub token cannot be read on this host and must be re-entered"
                } else {
                    "no problem-report GitHub token is configured"
                },
            )
        }
        val source = updateTokenSource()
        if (!sameApiEndpoint(stored.apiBaseUrl, source.apiBaseUrl)) {
            throw notConfigured(
                "problem reports use the update token, but the update source's API address no longer matches " +
                    "the problem-report API address, so the token is not sent; save the problem-report settings again",
            )
        }
        return source.token ?: throw updateTokenUnavailable(
            if (source.tokenUndecryptable) {
                "problem reports use the update token, but it cannot be read on this host and must be re-entered under Settings → Updates"
            } else {
                "problem reports use the update token, but no update token is stored (Settings → Updates)"
            },
        )
    }

    /** The slot is taken only here, after every local check, and is never given back. */
    private suspend fun post(
        target: ReportTarget,
        token: String,
        title: String,
        body: String,
    ): IssueReportDto.CreatedDto {
        rateLimiter.tryAcquire()?.let { throw rateLimited(it) }
        val result = tracker.create(GithubReleaseClient.Repo(target.baseUrl, target.owner, target.repo), token, title, body)
        return mapResult(result, target)
    }

    /** Every non-success keeps its rate-limit slot: an unreachable GitHub may still have created the issue. */
    private fun mapResult(
        result: IssueCreateResult,
        target: ReportTarget,
    ): IssueReportDto.CreatedDto =
        when (result) {
            is IssueCreateResult.Created -> {
                IssueReportDto.CreatedDto(result.number, result.url)
            }

            IssueCreateResult.TokenRejected -> {
                throw PosException(
                    PosErrorCode.ISSUE_REPORT_TOKEN_REJECTED,
                    HttpStatusCode.BadGateway,
                    "GitHub rejected the stored token — check it has Issues: read & write on ${target.owner}/${target.repo}",
                )
            }

            is IssueCreateResult.RateLimited -> {
                throw PosException(
                    PosErrorCode.ISSUE_REPORT_RATE_LIMITED,
                    HttpStatusCode.TooManyRequests,
                    "GitHub is rate limiting issue creation; retry in ${result.retryAfterSeconds ?: "a few"} seconds",
                )
            }

            is IssueCreateResult.Unreachable -> {
                throw PosException(
                    PosErrorCode.ISSUE_REPORT_GITHUB_UNREACHABLE,
                    HttpStatusCode.BadGateway,
                    "could not create the issue: ${result.reason}",
                )
            }
        }

    private suspend fun buildReport(
        reporterRole: String,
        request: IssueReportDto.ReportRequestDto,
        target: ReportTarget,
        logExcerpt: String?,
    ): ReportBuilder.Built {
        val context =
            ReportContext(
                appVersion = appVersion,
                platform = platform,
                source = request.source,
                screen = sanitiseScreen(request.screen),
                timestamp = clock(),
                // A public issue is world-readable: it must not name the business that filed it.
                shopName = if (target.isPublic) null else shopName(),
                reporterRole = reporterRole,
            )
        return ReportBuilder.build(request.title, request.description, context, logExcerpt)
    }

    /**
     * The screen is client-supplied and lands on its own context line, so a newline in it could forge
     * another line ("- Reporter role: ADMIN"). Control characters become spaces, then it is trimmed
     * and capped; never rejected, since it is only a hint.
     */
    private fun sanitiseScreen(screen: String?): String? =
        screen
            ?.map { if (it.isISOControl() || it == LINE_SEPARATOR || it == PARAGRAPH_SEPARATOR) ' ' else it }
            ?.joinToString("")
            ?.trim()
            ?.take(MAX_SCREEN)
            ?.trim()
            ?.ifEmpty { null }

    private suspend fun resolveTarget(stored: IssueReportSettings): ReportTarget {
        val channel = updateChannel()
        return ReportTarget(
            owner = stored.owner ?: channel.repoOwner,
            repo = stored.repo ?: channel.repoName,
            baseUrl = stored.apiBaseUrl,
            isPublic = stored.isPublic ?: (channel == UpdateChannel.PUBLIC),
        )
    }

    /**
     * The token was entered for one API host; sending it to another is exactly what a retargeting
     * attack wants. A host change therefore clears it — before the new target is written, so a failed
     * save can only ever leave the token gone, never pointed at the new host.
     */
    private suspend fun clearTokenIfHostChanges(
        current: IssueReportSettings,
        newBaseUrl: String,
    ) {
        if (hostOf(current.apiBaseUrl) == hostOf(newBaseUrl)) return
        settings.clearToken()
        log.info("Problem-report API host changed; the stored token was cleared and must be re-entered")
    }

    private fun hostOf(url: String): String? = runCatching { URI(url.trim()).host?.lowercase() }.getOrNull()

    /**
     * Same API endpoint after normalising: trailing slashes dropped, scheme and host compared
     * case-insensitively, port and path exactly. An unparseable side never matches.
     */
    private fun sameApiEndpoint(
        a: String,
        b: String,
    ): Boolean {
        val left = endpointKey(a) ?: return false
        return left == endpointKey(b)
    }

    private fun endpointKey(url: String): String? =
        runCatching {
            val uri = URI(url.trim().trimEnd('/'))
            val host = uri.host?.lowercase() ?: return@runCatching null
            "${uri.scheme?.lowercase()}://$host:${uri.port}${uri.rawPath.orEmpty().trimEnd('/')}"
        }.getOrNull()

    private fun requireWithinLimits(request: IssueReportDto.ReportRequestDto) {
        if (request.title.isBlank()) throw validation("title must not be blank")
        if (request.title.length > MAX_TITLE) throw validation("title must be at most $MAX_TITLE characters")
        if (request.description.isBlank()) throw validation("description must not be blank")
        if (request.description.length > MAX_DESCRIPTION) throw validation("description must be at most $MAX_DESCRIPTION characters")
    }

    private fun requireLogsAllowed(
        target: ReportTarget,
        includesLogs: Boolean,
    ) {
        if (includesLogs && target.isPublic) {
            throw validation("a log excerpt cannot be attached to a report for a public repository")
        }
    }

    private fun validateTarget(request: IssueReportDto.UpdateSettingsRequestDto) {
        if (!GITHUB_OWNER.matches(request.owner.trim())) throw validation("owner is not a valid GitHub account name")
        val repo = request.repo.trim()
        if (!GITHUB_REPO.matches(repo) || repo == "." || repo == "..") throw validation("repo is not a valid GitHub repository name")
        UpdateSourceValidator.validateBaseUrl(request.apiBaseUrl.trim())
    }

    private fun IssueReportSettings.toDto(
        target: ReportTarget,
        updateTokenState: IssueReportDto.TokenState,
    ): IssueReportDto.SettingsDto =
        IssueReportDto.SettingsDto(
            owner = target.owner,
            repo = target.repo,
            apiBaseUrl = target.baseUrl,
            isPublic = target.isPublic,
            tokenState = tokenState(),
            tokenPreview = token?.let(::mask),
            tokenSetAt = tokenSetAt?.toString(),
            useUpdateToken = useUpdateToken,
            updateTokenState = updateTokenState,
        )

    private fun IssueReportSettings.tokenState(): IssueReportDto.TokenState =
        when {
            tokenUndecryptable -> IssueReportDto.TokenState.UNREADABLE
            token != null -> IssueReportDto.TokenState.SET
            else -> IssueReportDto.TokenState.NONE
        }

    /** Only the state of the update token ever leaves this service — never any part of it. */
    private fun UpdateTokenSource.state(): IssueReportDto.TokenState =
        when {
            tokenUndecryptable -> IssueReportDto.TokenState.UNREADABLE
            token != null -> IssueReportDto.TokenState.SET
            else -> IssueReportDto.TokenState.NONE
        }

    /** At most the last four characters; a short token shows the mask only. */
    private fun mask(token: String): String = if (token.length >= MIN_LENGTH_FOR_PREVIEW) "••••" + token.takeLast(4) else "••••"

    private fun validation(detail: String) = PosException(PosErrorCode.VALIDATION_FAILED, HttpStatusCode.BadRequest, detail)

    private fun previewAgain() =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.Conflict,
            "the preview expired, was already used or no longer matches the target; preview again",
        )

    private fun keyMissing() =
        PosException(
            PosErrorCode.UPDATE_TOKEN_KEY_MISSING,
            HttpStatusCode.Conflict,
            "no UPDATE_TOKEN_KEY is provisioned on this host, so a token could never be read back — " +
                "set it in the systemd EnvironmentFile and retry",
        )

    private fun notConfigured(detail: String) = PosException(PosErrorCode.ISSUE_REPORT_NOT_CONFIGURED, HttpStatusCode.Conflict, detail)

    /** The chosen update token is unset or unreadable: its remedy is under Settings → Updates, hence its own code. */
    private fun updateTokenUnavailable(detail: String) = PosException(PosErrorCode.ISSUE_REPORT_UPDATE_TOKEN_UNAVAILABLE, HttpStatusCode.Conflict, detail)

    private fun rateLimited(retryAfterSeconds: Long) =
        PosException(
            PosErrorCode.ISSUE_REPORT_RATE_LIMITED,
            HttpStatusCode.TooManyRequests,
            "too many problem reports; retry in $retryAfterSeconds seconds",
        )

    private companion object {
        const val MAX_TITLE = 200
        const val MAX_DESCRIPTION = 10_000
        const val MIN_LENGTH_FOR_PREVIEW = 12
        const val MAX_SCREEN = 100
        const val LINE_SEPARATOR = '\u2028'
        const val PARAGRAPH_SEPARATOR = '\u2029'
        val GITHUB_OWNER = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$")
        val GITHUB_REPO = Regex("^[A-Za-z0-9._-]{1,100}$")
    }
}
