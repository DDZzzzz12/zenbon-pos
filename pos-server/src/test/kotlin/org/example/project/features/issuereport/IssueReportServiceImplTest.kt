package org.example.project.features.issuereport

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.example.project.core.domain.dto.issuereport.IssueReportDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.issuereport.data.service.IssueRateLimiter
import org.example.project.features.issuereport.data.service.IssueReportServiceImpl
import org.example.project.features.issuereport.data.service.PreviewCache
import org.example.project.features.issuereport.domain.models.IssueCreateResult
import org.example.project.features.issuereport.domain.models.IssueReportSettings
import org.example.project.features.issuereport.domain.models.UpdateTokenSource
import org.example.project.features.issuereport.domain.repository.IssueReportSettingsRepository
import org.example.project.features.issuereport.domain.tracker.IssueTracker
import org.example.project.features.update.data.crypto.TokenCipher
import org.example.project.features.update.data.remote.GithubReleaseClient
import org.example.project.features.update.domain.models.UpdateChannel
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestDatabase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("IssueReportServiceImpl")
class IssueReportServiceImplTest {
    private val fixedNow = Instant.parse("2026-10-10T12:00:00Z")

    /** Read by the default service clock; a test may move it. */
    private var now = fixedNow
    private val secret = "github_pat_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    @BeforeEach
    fun setUp() {
        TestDatabase.setup()
    }

    @AfterEach
    fun tearDown() {
        TestDatabase.teardown()
    }

    private class FakeSettings(
        var state: IssueReportSettings =
            IssueReportSettings(
                owner = null,
                repo = null,
                apiBaseUrl = "https://api.github.com",
                isPublic = null,
                token = null,
                tokenUndecryptable = false,
                tokenSetAt = null,
            ),
    ) : IssueReportSettingsRepository {
        override suspend fun load() = state

        override suspend fun saveTarget(
            owner: String,
            repo: String,
            apiBaseUrl: String,
            isPublic: Boolean,
            useUpdateToken: Boolean,
        ): IssueReportSettings {
            state = state.copy(owner = owner, repo = repo, apiBaseUrl = apiBaseUrl, isPublic = isPublic, useUpdateToken = useUpdateToken)
            return state
        }

        override suspend fun setToken(token: String) {
            state = state.copy(token = token, tokenUndecryptable = false, tokenSetAt = Instant.parse("2026-10-10T12:00:00Z"))
        }

        override suspend fun clearToken() {
            state = state.copy(token = null, tokenUndecryptable = false, tokenSetAt = null)
        }
    }

    private class FakeTracker(
        var result: IssueCreateResult = IssueCreateResult.Created(42, "https://github.com/o/r/issues/42"),
    ) : IssueTracker {
        var calls = 0
        var lastRepo: GithubReleaseClient.Repo? = null
        var lastToken: String? = null
        var lastTitle: String? = null
        var lastBody: String? = null

        override suspend fun create(
            repo: GithubReleaseClient.Repo,
            token: String,
            title: String,
            body: String,
        ): IssueCreateResult {
            calls++
            lastRepo = repo
            lastToken = token
            lastTitle = title
            lastBody = body
            return result
        }
    }

    private val request =
        IssueReportDto.ReportRequestDto(
            title = "Export fails",
            description = "Saving gives HTTP 500",
            source = IssueReportDto.Source.ADMIN,
            screen = "Documents",
        )

    private fun service(
        repo: FakeSettings = FakeSettings(),
        tracker: FakeTracker = FakeTracker(),
        cipher: TokenCipher = TokenCipher(TokenCipher.generateKeyBase64()),
        channel: UpdateChannel = UpdateChannel.PRIVATE,
        logTail: () -> String? = { null },
        limiter: IssueRateLimiter = IssueRateLimiter(),
        clock: () -> Instant = { now },
        shopName: String? = "Zum Anker",
        updateSource: () -> UpdateTokenSource = { UpdateTokenSource("https://api.github.com", null, false) },
    ) = IssueReportServiceImpl(
        settings = repo,
        tracker = tracker,
        cipher = cipher,
        updateChannel = { channel },
        updateTokenSource = { updateSource() },
        shopName = { shopName },
        logTail = logTail,
        appVersion = "1.1.2",
        platform = "Linux / Java 21",
        rateLimiter = limiter,
        clock = clock,
        previewCache = PreviewCache(clock = { now }),
    )

    private fun withToken(repo: FakeSettings = FakeSettings()) = repo.also { it.state = it.state.copy(token = secret) }

    private suspend fun refusal(block: suspend () -> Any?): PosException = assertFailsWith<PosException> { block() }

    private fun assertRefusal(
        e: PosException,
        code: PosErrorCode,
        status: HttpStatusCode,
    ) {
        assertEquals(code, e.code)
        assertEquals(status, e.httpStatus)
    }

    @Test
    fun `defaults follow the PRIVATE channel to POS-KMP and the PUBLIC channel to zenbon-pos and mark public`() =
        runTest {
            val priv = service(channel = UpdateChannel.PRIVATE).settings()
            assertEquals("DDZzzzz12", priv.owner)
            assertEquals("POS-KMP", priv.repo)
            assertFalse(priv.isPublic)

            val pub = service(channel = UpdateChannel.PUBLIC).settings()
            assertEquals("DDZzzzz12", pub.owner)
            assertEquals("zenbon-pos", pub.repo)
            assertTrue(pub.isPublic)
        }

    @Test
    fun `an explicit override wins over the channel default`() =
        runTest {
            val repo = FakeSettings()
            repo.saveTarget("acme", "tickets", "https://api.github.com", isPublic = false, useUpdateToken = false)
            val dto = service(repo = repo, channel = UpdateChannel.PUBLIC).settings()
            assertEquals("acme", dto.owner)
            assertEquals("tickets", dto.repo)
            assertFalse(dto.isPublic)
        }

    @Test
    fun `settings never expose the token only state and a masked preview`() =
        runTest {
            val none = service().settings()
            assertEquals(IssueReportDto.TokenState.NONE, none.tokenState)
            assertNull(none.tokenPreview)

            val set = service(repo = withToken()).settings()
            assertEquals(IssueReportDto.TokenState.SET, set.tokenState)
            assertEquals("••••" + secret.takeLast(4), set.tokenPreview)
            assertFalse(set.toString().contains(secret))
            assertFalse(set.toString().contains(secret.dropLast(4)))

            val short = service(repo = FakeSettings().also { it.state = it.state.copy(token = "short") }).settings()
            assertEquals("••••", short.tokenPreview)

            val unreadable = service(repo = FakeSettings().also { it.state = it.state.copy(tokenUndecryptable = true) }).settings()
            assertEquals(IssueReportDto.TokenState.UNREADABLE, unreadable.tokenState)
            assertNull(unreadable.tokenPreview)
        }

    @Test
    fun `saveSettings rejects a bad owner repo and a non-https or local base url with 400`() =
        runTest {
            val svc = service()

            fun req(
                owner: String = "acme",
                repo: String = "pos",
                url: String = "https://api.github.com",
            ) = IssueReportDto.UpdateSettingsRequestDto(owner, repo, url, isPublic = false)
            val bad =
                listOf(
                    req(owner = "-bad"),
                    req(owner = "has space"),
                    req(owner = ""),
                    req(repo = "a/b"),
                    req(repo = "../x"),
                    req(repo = "."),
                    req(repo = ".."),
                    req(url = "http://api.github.com"),
                    req(url = "https://10.0.0.1"),
                    req(url = "https://localhost"),
                )
            for (r in bad) {
                val e = refusal { svc.saveSettings(r) }
                assertRefusal(e, PosErrorCode.VALIDATION_FAILED, HttpStatusCode.BadRequest)
            }
            val ok = svc.saveSettings(req(url = "https://api.github.com/"))
            assertEquals("https://api.github.com", ok.apiBaseUrl)
            assertEquals("acme", ok.owner)
        }

    @Test
    fun `setToken without a provisioned key is 409 UPDATE_TOKEN_KEY_MISSING`() =
        runTest {
            val repo = FakeSettings()
            val e = refusal { service(repo = repo, cipher = TokenCipher(null)).setToken(secret) }
            assertRefusal(e, PosErrorCode.UPDATE_TOKEN_KEY_MISSING, HttpStatusCode.Conflict)
            assertNull(repo.state.token)
        }

    @Test
    fun `setToken blank is 400`() =
        runTest {
            val e = refusal { service().setToken("   ") }
            assertRefusal(e, PosErrorCode.VALIDATION_FAILED, HttpStatusCode.BadRequest)
        }

    @Test
    fun `setToken stores a trimmed token and clearToken removes it`() =
        runTest {
            val repo = FakeSettings()
            val svc = service(repo = repo)
            val dto = svc.setToken("  $secret  ")
            assertEquals(secret, repo.state.token)
            assertEquals(IssueReportDto.TokenState.SET, dto.tokenState)
            assertEquals(IssueReportDto.TokenState.NONE, svc.clearToken().tokenState)
        }

    @Test
    fun `submit without a token is 409 ISSUE_REPORT_NOT_CONFIGURED`() =
        runTest {
            val tracker = FakeTracker()
            val e = refusal { service(tracker = tracker).submit("1", "dev", "MANAGER", request) }
            assertRefusal(e, PosErrorCode.ISSUE_REPORT_NOT_CONFIGURED, HttpStatusCode.Conflict)
            assertEquals(0, tracker.calls)

            val unreadable = FakeSettings().also { it.state = it.state.copy(tokenUndecryptable = true) }
            val e2 = refusal { service(repo = unreadable, tracker = tracker).submit("1", "dev", "MANAGER", request) }
            assertRefusal(e2, PosErrorCode.ISSUE_REPORT_NOT_CONFIGURED, HttpStatusCode.Conflict)
            assertTrue(e2.message!!.contains("re-entered"), e2.message)
            assertEquals(0, tracker.calls)
        }

    @Test
    fun `submit with includeLogs against a public target is 400 and never calls the tracker`() =
        runTest {
            val tracker = FakeTracker()
            val svc = service(repo = withToken(), tracker = tracker, channel = UpdateChannel.PUBLIC, logTail = { "line" })
            val e = refusal { svc.submit("1", "dev", "MANAGER", request.copy(includeLogs = true)) }
            assertRefusal(e, PosErrorCode.VALIDATION_FAILED, HttpStatusCode.BadRequest)
            assertEquals(0, tracker.calls)
        }

    @Test
    fun `submit blank title or description is 400 and over-long is 400`() =
        runTest {
            val tracker = FakeTracker()
            val svc = service(repo = withToken(), tracker = tracker)
            val bad =
                listOf(
                    request.copy(title = "  "),
                    request.copy(title = "x".repeat(201)),
                    request.copy(description = ""),
                    request.copy(description = "x".repeat(10_001)),
                )
            for (r in bad) {
                val e = refusal { svc.submit("1", "dev", "MANAGER", r) }
                assertRefusal(e, PosErrorCode.VALIDATION_FAILED, HttpStatusCode.BadRequest)
            }
            assertEquals(0, tracker.calls)
            // The boundaries themselves are accepted.
            svc.submit("1", "dev", "MANAGER", request.copy(title = "x".repeat(200), description = "y".repeat(10_000)))
            assertEquals(1, tracker.calls)
        }

    @Test
    fun `submit with a previewId posts exactly the previewed text even though the log and clock moved on`() =
        runTest {
            val tracker = FakeTracker()
            var reads = 0
            // Every read of the log returns different text, and the clock advances on every call:
            // a send that rebuilt the report would post something the operator never saw.
            val tail = { "ERROR read #${++reads} at step 3" }
            var tick = fixedNow
            val svc =
                service(repo = withToken(), tracker = tracker, logTail = tail, clock = {
                    tick = tick.plusSeconds(1)
                    tick
                })
            val preview = svc.preview("1", "MANAGER", request.copy(includeLogs = true))
            assertTrue(preview.body.contains("read #1"), preview.body)

            val created = svc.submit("1", "dev", "MANAGER", request.copy(includeLogs = true, previewId = preview.previewId))

            assertEquals(preview.title, tracker.lastTitle)
            assertEquals(preview.body, tracker.lastBody)
            assertEquals(1, reads, "the send must not read the log again")
            assertEquals("DDZzzzz12/POS-KMP", preview.repository)
            assertEquals(42L, created.issueNumber)
            assertEquals("https://github.com/o/r/issues/42", created.issueUrl)
            assertEquals(secret, tracker.lastToken)
            assertEquals(GithubReleaseClient.Repo("https://api.github.com", "DDZzzzz12", "POS-KMP"), tracker.lastRepo)
        }

    @Test
    fun `the snapshot wins over a request whose text changed after the preview`() =
        runTest {
            val tracker = FakeTracker()
            val svc = service(repo = withToken(), tracker = tracker)
            val preview = svc.preview("1", "MANAGER", request)
            svc.submit("1", "dev", "MANAGER", request.copy(title = "Edited", description = "Edited too", previewId = preview.previewId))
            assertEquals(preview.title, tracker.lastTitle)
            assertEquals(preview.body, tracker.lastBody)
        }

    @Test
    fun `a send with logs but without a previewId is 400 and never reads the log`() =
        runTest {
            val tracker = FakeTracker()
            var reads = 0
            val svc = service(repo = withToken(), tracker = tracker, logTail = { "line ${++reads}" })
            val e = refusal { svc.submit("1", "dev", "MANAGER", request.copy(includeLogs = true)) }
            assertRefusal(e, PosErrorCode.VALIDATION_FAILED, HttpStatusCode.BadRequest)
            assertTrue(e.message!!.contains("preview"), e.message)
            assertEquals(0, reads)
            assertEquals(0, tracker.calls)
        }

    @Test
    fun `a send without logs and without a previewId builds fresh text`() =
        runTest {
            val tracker = FakeTracker()
            service(repo = withToken(), tracker = tracker).submit("1", "dev", "MANAGER", request)
            assertEquals("Export fails", tracker.lastTitle)
            assertTrue(tracker.lastBody!!.startsWith("Saving gives HTTP 500"), tracker.lastBody)
            assertFalse(tracker.lastBody!!.contains("Server log"), tracker.lastBody)
        }

    @Test
    fun `an unknown, foreign, expired or already used previewId is 409 preview again`() =
        runTest {
            val tracker = FakeTracker()
            val svc = service(repo = withToken(), tracker = tracker)

            suspend fun assertPreviewAgain(
                employeeId: String,
                previewId: String,
            ) {
                val e = refusal { svc.submit(employeeId, "dev", "MANAGER", request.copy(previewId = previewId)) }
                assertRefusal(e, PosErrorCode.VALIDATION_FAILED, HttpStatusCode.Conflict)
                assertTrue(e.message!!.contains("preview again"), e.message)
            }

            assertPreviewAgain("1", "0123456789abcdef0123456789abcdef")

            val foreign = svc.preview("1", "MANAGER", request).previewId
            assertPreviewAgain("2", foreign)

            val used = svc.preview("1", "MANAGER", request).previewId
            svc.submit("1", "dev", "MANAGER", request.copy(previewId = used))
            assertPreviewAgain("1", used)

            val expired = svc.preview("1", "MANAGER", request).previewId
            now = now.plus(Duration.ofMinutes(16))
            assertPreviewAgain("1", expired)

            assertEquals(1, tracker.calls)
        }

    @Test
    fun `a target changed since the preview is 409 preview again`() =
        runTest {
            val tracker = FakeTracker()
            val repo = withToken()
            val svc = service(repo = repo, tracker = tracker)
            val preview = svc.preview("1", "MANAGER", request)
            repo.state = repo.state.copy(owner = "acme", repo = "elsewhere")
            val e = refusal { svc.submit("1", "dev", "MANAGER", request.copy(previewId = preview.previewId)) }
            assertRefusal(e, PosErrorCode.VALIDATION_FAILED, HttpStatusCode.Conflict)
            assertEquals(0, tracker.calls)
        }

    @Test
    fun `a target turned public after a log preview refuses the snapshot`() =
        runTest {
            val tracker = FakeTracker()
            val repo = withToken()
            val svc = service(repo = repo, tracker = tracker, logTail = { "line" })
            val preview = svc.preview("1", "MANAGER", request.copy(includeLogs = true))
            repo.state = repo.state.copy(isPublic = true)
            val e = refusal { svc.submit("1", "dev", "MANAGER", request.copy(previewId = preview.previewId)) }
            assertEquals(PosErrorCode.VALIDATION_FAILED, e.code)
            assertEquals(0, tracker.calls)
        }

    @Test
    fun `a failed send keeps the snapshot so the same text can be retried`() =
        runTest {
            val tracker = FakeTracker(IssueCreateResult.Unreachable("timeout"))
            val svc = service(repo = withToken(), tracker = tracker)
            val preview = svc.preview("1", "MANAGER", request)
            refusal { svc.submit("1", "dev", "MANAGER", request.copy(previewId = preview.previewId)) }
            tracker.result = IssueCreateResult.Created(43, "https://github.com/o/r/issues/43")
            val created = svc.submit("1", "dev", "MANAGER", request.copy(previewId = preview.previewId))
            assertEquals(43L, created.issueNumber)
            assertEquals(preview.body, tracker.lastBody)
        }

    @Test
    fun `a screen cannot forge a context line and is capped`() =
        runTest {
            val svc = service()
            val forged = svc.preview("1", "MANAGER", request.copy(screen = "Docs\n- Reporter role: ADMIN\r\u0007"))
            val roleLines = forged.body.lines().filter { it.startsWith("- Reporter role:") }
            assertEquals(listOf("- Reporter role: MANAGER"), roleLines, forged.body)
            assertTrue(forged.body.contains("- Screen: Docs - Reporter role: ADMIN\n"), forged.body)

            val huge = svc.preview("1", "MANAGER", request.copy(screen = "S".repeat(1_000_000)))
            val screenLine = huge.body.lines().single { it.startsWith("- Screen: ") }
            assertEquals("- Screen: " + "S".repeat(100), screenLine)
        }

    @Test
    fun `the shop name is omitted for a public target`() =
        runTest {
            val pub = service(channel = UpdateChannel.PUBLIC).preview("1", "MANAGER", request)
            assertFalse(pub.body.contains("Zum Anker"), pub.body)
            assertFalse(pub.body.contains("- Shop:"), pub.body)

            val priv = service(channel = UpdateChannel.PRIVATE).preview("1", "MANAGER", request)
            assertTrue(priv.body.contains("- Shop: Zum Anker"), priv.body)
        }

    @Test
    fun `saveSettings to another host clears the token and the same host keeps it`() =
        runTest {
            val repo = withToken()
            val svc = service(repo = repo)

            val sameHost = svc.saveSettings(IssueReportDto.UpdateSettingsRequestDto("acme", "pos", "https://api.github.com/", isPublic = false))
            assertEquals(IssueReportDto.TokenState.SET, sameHost.tokenState)
            assertEquals(secret, repo.state.token)

            val moved =
                svc.saveSettings(IssueReportDto.UpdateSettingsRequestDto("acme", "pos", "https://github.example.com/api/v3", isPublic = false))
            assertEquals(IssueReportDto.TokenState.NONE, moved.tokenState)
            assertNull(repo.state.token)
            assertEquals("https://github.example.com/api/v3", repo.state.apiBaseUrl)
        }

    // ---- Update-token reuse (2026-10-11) ------------------------------------------------------

    private val updateSecret = "github_pat_UPDATE_TOKEN_9876543210_zyxwvutsrq"

    private fun updateSourceWith(
        token: String? = updateSecret,
        undecryptable: Boolean = false,
        apiBaseUrl: String = "https://api.github.com",
    ) = UpdateTokenSource(apiBaseUrl, token, undecryptable)

    private fun usingUpdateToken(repo: FakeSettings = FakeSettings()) = repo.also { it.state = it.state.copy(useUpdateToken = true) }

    private fun targetRequest(
        url: String = "https://api.github.com",
        useUpdateToken: Boolean,
    ) = IssueReportDto.UpdateSettingsRequestDto("acme", "pos", url, isPublic = false, useUpdateToken = useUpdateToken)

    @Test
    fun `with useUpdateToken the update token is sent and the own token is ignored`() =
        runTest {
            val tracker = FakeTracker()
            val repo = usingUpdateToken(withToken())
            val svc = service(repo = repo, tracker = tracker, updateSource = { updateSourceWith() })

            svc.submit("1", "dev", "MANAGER", request)
            assertEquals(updateSecret, tracker.lastToken)

            val preview = svc.preview("1", "MANAGER", request)
            svc.submit("1", "dev", "MANAGER", request.copy(previewId = preview.previewId))
            assertEquals(updateSecret, tracker.lastToken)
            assertEquals(2, tracker.calls)
            // Ignored, not deleted.
            assertEquals(secret, repo.state.token)
        }

    @Test
    fun `without useUpdateToken a missing own token is 409 and never falls back to the update token`() =
        runTest {
            val tracker = FakeTracker()
            val svc = service(tracker = tracker, updateSource = { updateSourceWith() })
            val e = refusal { svc.submit("1", "dev", "MANAGER", request) }
            assertRefusal(e, PosErrorCode.ISSUE_REPORT_NOT_CONFIGURED, HttpStatusCode.Conflict)
            assertTrue(e.message!!.contains("problem-report GitHub token"), e.message)
            assertEquals(0, tracker.calls)

            // And with an own token, the own token is what goes out.
            service(repo = withToken(), tracker = tracker, updateSource = { updateSourceWith() }).submit("1", "dev", "MANAGER", request)
            assertEquals(secret, tracker.lastToken)
        }

    @Test
    fun `with useUpdateToken an unset or unreadable update token is 409 ISSUE_REPORT_UPDATE_TOKEN_UNAVAILABLE`() =
        runTest {
            val tracker = FakeTracker()
            val none =
                refusal {
                    service(repo = usingUpdateToken(withToken()), tracker = tracker, updateSource = { updateSourceWith(token = null) })
                        .submit("1", "dev", "MANAGER", request)
                }
            assertRefusal(none, PosErrorCode.ISSUE_REPORT_UPDATE_TOKEN_UNAVAILABLE, HttpStatusCode.Conflict)
            assertTrue(none.message!!.contains("no update token is stored"), none.message)

            val unreadable =
                refusal {
                    service(
                        repo = usingUpdateToken(withToken()),
                        tracker = tracker,
                        updateSource = { updateSourceWith(token = null, undecryptable = true) },
                    ).submit("1", "dev", "MANAGER", request)
                }
            assertRefusal(unreadable, PosErrorCode.ISSUE_REPORT_UPDATE_TOKEN_UNAVAILABLE, HttpStatusCode.Conflict)
            assertTrue(unreadable.message!!.contains("update token") && unreadable.message!!.contains("cannot be read"), unreadable.message)
            assertEquals(0, tracker.calls)
        }

    @Test
    fun `saving useUpdateToken for a host other than the update source's is 400 ISSUE_REPORT_UPDATE_TOKEN_HOST_MISMATCH and saves nothing`() =
        runTest {
            val repo = FakeSettings()
            val svc = service(repo = repo, updateSource = { updateSourceWith() })
            val e = refusal { svc.saveSettings(targetRequest(url = "https://github.evil.example/api/v3", useUpdateToken = true)) }
            assertRefusal(e, PosErrorCode.ISSUE_REPORT_UPDATE_TOKEN_HOST_MISMATCH, HttpStatusCode.BadRequest)
            assertTrue(e.message!!.contains("update source"), e.message)
            assertFalse(repo.state.useUpdateToken)
            assertNull(repo.state.owner)
        }

    @Test
    fun `the host rule ignores a trailing slash and the host's case`() =
        runTest {
            val repo = FakeSettings()
            val svc = service(repo = repo, updateSource = { updateSourceWith(apiBaseUrl = "https://API.GitHub.com/") })
            val saved = svc.saveSettings(targetRequest(url = "https://api.github.com/", useUpdateToken = true))
            assertTrue(saved.useUpdateToken)
            assertTrue(repo.state.useUpdateToken)
        }

    @Test
    fun `turning useUpdateToken on is 400 while no readable update token is stored`() =
        runTest {
            for (source in listOf(updateSourceWith(token = null), updateSourceWith(token = null, undecryptable = true))) {
                val repo = FakeSettings()
                val e = refusal { service(repo = repo, updateSource = { source }).saveSettings(targetRequest(useUpdateToken = true)) }
                assertRefusal(e, PosErrorCode.VALIDATION_FAILED, HttpStatusCode.BadRequest)
                assertTrue(e.message!!.contains("update token"), e.message)
                assertFalse(repo.state.useUpdateToken)
            }
        }

    @Test
    fun `an update source moved to another host after saving refuses the send and never calls the tracker`() =
        runTest {
            val tracker = FakeTracker()
            var source = updateSourceWith()
            val repo = FakeSettings()
            val svc = service(repo = repo, tracker = tracker, updateSource = { source })
            svc.saveSettings(targetRequest(useUpdateToken = true))
            val preview = svc.preview("1", "MANAGER", request)

            source = updateSourceWith(apiBaseUrl = "https://github.example.com/api/v3")

            val fresh = refusal { svc.submit("1", "dev", "MANAGER", request) }
            assertRefusal(fresh, PosErrorCode.ISSUE_REPORT_NOT_CONFIGURED, HttpStatusCode.Conflict)
            assertTrue(fresh.message!!.contains("no longer matches"), fresh.message)
            val snapshot = refusal { svc.submit("1", "dev", "MANAGER", request.copy(previewId = preview.previewId)) }
            assertRefusal(snapshot, PosErrorCode.ISSUE_REPORT_NOT_CONFIGURED, HttpStatusCode.Conflict)
            assertEquals(0, tracker.calls)

            // Once the hosts agree again, the kept snapshot goes out with the update token.
            source = updateSourceWith()
            svc.submit("1", "dev", "MANAGER", request.copy(previewId = preview.previewId))
            assertEquals(updateSecret, tracker.lastToken)
            assertEquals(preview.body, tracker.lastBody)
        }

    @Test
    fun `toggling useUpdateToken clears neither token`() =
        runTest {
            val repo = withToken()
            val source = updateSourceWith()
            val svc = service(repo = repo, updateSource = { source })

            val on = svc.saveSettings(targetRequest(useUpdateToken = true))
            assertTrue(on.useUpdateToken)
            assertEquals(secret, repo.state.token)
            assertEquals(IssueReportDto.TokenState.SET, on.tokenState)

            val off = svc.saveSettings(targetRequest(useUpdateToken = false))
            assertFalse(off.useUpdateToken)
            assertEquals(secret, repo.state.token)
            assertEquals(IssueReportDto.TokenState.SET, off.updateTokenState)
            assertEquals(updateSecret, source.token)
        }

    @Test
    fun `an already-on choice can be saved or switched off after the update token went away`() =
        runTest {
            val repo = usingUpdateToken()
            val svc = service(repo = repo, updateSource = { updateSourceWith(token = null) })
            val kept = svc.saveSettings(targetRequest(useUpdateToken = true))
            assertTrue(kept.useUpdateToken)
            val off = svc.saveSettings(targetRequest(useUpdateToken = false))
            assertFalse(off.useUpdateToken)
        }

    @Test
    fun `settings report the update token state and never any token text`() =
        runTest {
            val repo = usingUpdateToken(withToken())
            val set = service(repo = repo, updateSource = { updateSourceWith() }).settings()
            assertTrue(set.useUpdateToken)
            assertEquals(IssueReportDto.TokenState.SET, set.updateTokenState)
            for (needle in listOf(updateSecret, updateSecret.takeLast(4), updateSecret.dropLast(4), secret, secret.dropLast(4))) {
                assertFalse(set.toString().contains(needle), set.toString())
            }

            val none = service(updateSource = { updateSourceWith(token = null) }).settings()
            assertEquals(IssueReportDto.TokenState.NONE, none.updateTokenState)
            assertFalse(none.useUpdateToken)

            val unreadable = service(updateSource = { updateSourceWith(token = null, undecryptable = true) }).settings()
            assertEquals(IssueReportDto.TokenState.UNREADABLE, unreadable.updateTokenState)
        }

    @Test
    fun `the update token source never prints its token`() {
        val source = updateSourceWith()
        assertFalse(source.toString().contains(updateSecret), source.toString())
        assertFalse(source.toString().contains(updateSecret.takeLast(8)), source.toString())
    }

    @Test
    fun `the domain settings never print the token`() {
        val settings = withToken().state
        assertFalse(settings.toString().contains(secret), settings.toString())
        assertFalse(settings.toString().contains(secret.takeLast(8)), settings.toString())
    }

    @Test
    fun `submit maps TokenRejected to 502 ISSUE_REPORT_TOKEN_REJECTED`() =
        runTest {
            val tracker = FakeTracker(IssueCreateResult.TokenRejected)
            val e = refusal { service(repo = withToken(), tracker = tracker).submit("1", "dev", "MANAGER", request) }
            assertRefusal(e, PosErrorCode.ISSUE_REPORT_TOKEN_REJECTED, HttpStatusCode.BadGateway)
            assertFalse(e.message!!.contains(secret))
        }

    @Test
    fun `submit maps Unreachable to 502 ISSUE_REPORT_GITHUB_UNREACHABLE and the attempt still costs a slot`() =
        runTest {
            val tracker = FakeTracker(IssueCreateResult.Unreachable("timeout"))
            val svc = service(repo = withToken(), tracker = tracker)
            // An unreachable or timed-out GitHub may still have created the issue, so every attempt
            // that reached the tracker counts: five failures, and the sixth is this server's 429.
            repeat(5) {
                val e = refusal { svc.submit("1", "dev", "MANAGER", request) }
                assertRefusal(e, PosErrorCode.ISSUE_REPORT_GITHUB_UNREACHABLE, HttpStatusCode.BadGateway)
            }
            val sixth = refusal { svc.submit("1", "dev", "MANAGER", request) }
            assertRefusal(sixth, PosErrorCode.ISSUE_REPORT_RATE_LIMITED, HttpStatusCode.TooManyRequests)
            assertEquals(5, tracker.calls)
        }

    @Test
    fun `a refusal before the tracker is reached never costs a slot`() =
        runTest {
            val tracker = FakeTracker()
            val repo = FakeSettings()
            val svc = service(repo = repo, tracker = tracker)
            // No token, logs without a preview, an unknown preview: none of these may eat the budget.
            repeat(6) { refusal { svc.submit("1", "dev", "MANAGER", request) } }
            repo.state = repo.state.copy(token = secret)
            repeat(6) { refusal { svc.submit("1", "dev", "MANAGER", request.copy(includeLogs = true)) } }
            repeat(6) { refusal { svc.submit("1", "dev", "MANAGER", request.copy(previewId = "nope")) } }
            repeat(5) { svc.submit("1", "dev", "MANAGER", request) }
            assertEquals(5, tracker.calls)
        }

    @Test
    fun `submit maps RateLimited to 429 ISSUE_REPORT_RATE_LIMITED`() =
        runTest {
            val tracker = FakeTracker(IssueCreateResult.RateLimited(30))
            val e = refusal { service(repo = withToken(), tracker = tracker).submit("1", "dev", "MANAGER", request) }
            assertRefusal(e, PosErrorCode.ISSUE_REPORT_RATE_LIMITED, HttpStatusCode.TooManyRequests)
            assertTrue(e.message!!.contains("30"), e.message)
            assertEquals(1, tracker.calls)
        }

    @Test
    fun `the sixth submit within the hour is 429 without calling the tracker`() =
        runTest {
            val tracker = FakeTracker()
            val svc = service(repo = withToken(), tracker = tracker)
            repeat(5) { svc.submit("1", "dev", "MANAGER", request) }
            val e = refusal { svc.submit("1", "dev", "MANAGER", request) }
            assertRefusal(e, PosErrorCode.ISSUE_REPORT_RATE_LIMITED, HttpStatusCode.TooManyRequests)
            assertEquals(5, tracker.calls)
        }

    @Test
    fun `preview with includeLogs reads the tail scrubbed and refuses for public`() =
        runTest {
            val tail = { "ERROR failed for max@example.de at step 3" }
            val preview = service(logTail = tail, channel = UpdateChannel.PRIVATE).preview("1", "MANAGER", request.copy(includeLogs = true))
            assertTrue(preview.body.contains("Server log"), preview.body)
            assertTrue(preview.body.contains("[email]"), preview.body)
            assertFalse(preview.body.contains("max@example.de"), preview.body)

            val without = service(logTail = tail).preview("1", "MANAGER", request)
            assertFalse(without.body.contains("Server log"), without.body)

            val e = refusal { service(logTail = tail, channel = UpdateChannel.PUBLIC).preview("1", "MANAGER", request.copy(includeLogs = true)) }
            assertRefusal(e, PosErrorCode.VALIDATION_FAILED, HttpStatusCode.BadRequest)
        }
}
