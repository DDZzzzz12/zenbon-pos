package org.example.project.features.issuereport.domain.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import org.example.project.core.domain.dto.issuereport.IssueReportDto
import org.example.project.features.issuereport.domain.service.IssueReportService
import org.example.project.infrastructure.plugins.getTokenClaims
import org.koin.ktor.ext.inject

/**
 * Problem-report endpoints — filing behind `ISSUE_REPORT_CREATE`, configuration behind
 * `ISSUE_REPORT_MANAGE` (see `IssueReportRules`). The token is write-only across the family.
 */
fun Route.issueReportRoutes() {
    val service by inject<IssueReportService>()

    authenticate("auth-jwt") {
        route("/api/issue-reports") {
            /**
             * File a problem report as a GitHub issue.
             *
             * Description: Requires ISSUE_REPORT_CREATE. With a previewId from POST /preview, posts exactly that preview's title and body (single use, bound to the caller, valid for 15 minutes, only to the same target); the request's text is then only length-checked. Without a previewId the text is built here, and only without a log excerpt — a log excerpt is never sent unseen. A log excerpt is refused when the target repository is public. Returns the issue's number and URL. At most 5 attempts per hour per installation; an attempt that reached GitHub counts even when it failed.
             *
             * Tag: issue-reports
             * Response: 400 Blank or over-long text, a log excerpt requested for a public repository, or a log excerpt requested without a previewId.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks ISSUE_REPORT_CREATE.
             * Response: 409 The chosen token is not configured or unreadable on this host (ISSUE_REPORT_NOT_CONFIGURED for the report's own token, ISSUE_REPORT_UPDATE_TOKEN_UNAVAILABLE for the update token when useUpdateToken is on), or the update source's API address no longer matches the report's (ISSUE_REPORT_NOT_CONFIGURED), or the previewId is unknown, expired, already used, someone else's, or the target changed since — preview again (VALIDATION_FAILED).
             * Response: 429 Too many reports, or GitHub is rate limiting.
             * Response: 502 GitHub rejected the token or could not be reached.
             */
            post {
                val claims = call.getTokenClaims() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val body = call.receive<IssueReportDto.ReportRequestDto>()
                call.respond(HttpStatusCode.Created, service.submit(claims.employeeId, claims.deviceId, claims.role, body))
            }

            /**
             * Preview a problem report.
             *
             * Description: Requires ISSUE_REPORT_CREATE. Builds the report once and returns exactly what a send with the returned previewId will post — the server keeps this snapshot for 15 minutes, for the calling employee only. Sends nothing.
             *
             * Tag: issue-reports
             * Response: 400 Blank or over-long text, or a log excerpt requested for a public repository.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks ISSUE_REPORT_CREATE.
             */
            post("/preview") {
                val claims = call.getTokenClaims() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val body = call.receive<IssueReportDto.ReportRequestDto>()
                call.respond(HttpStatusCode.OK, service.preview(claims.employeeId, claims.role, body))
            }

            /**
             * Read the problem-report settings.
             *
             * Description: Requires ISSUE_REPORT_MANAGE. The target repository, its API base URL and visibility, plus whether a GitHub token is stored. The token itself is never returned — only its state, a masked preview of its last four characters and the time it was stored. useUpdateToken says whether the Administrator chose to file reports with the update feature's token instead; updateTokenState is that update token's state only (NONE, SET or UNREADABLE — no preview, no other part of it), and reading it here needs no update capability.
             *
             * Tag: issue-reports
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks ISSUE_REPORT_MANAGE.
             */
            get("/settings") {
                call.respond(HttpStatusCode.OK, service.settings())
            }

            /**
             * Replace the problem-report target settings.
             *
             * Description: Requires ISSUE_REPORT_MANAGE. The API base URL decides which host this server sends the token to, so it must be an https URL naming a host — not an IP address, not a loopback or site-local name, with no embedded credentials, query or fragment. Changing it to a different host clears the stored token in the same call (the response then shows tokenState NONE): a token is only ever sent to the host it was entered for. Owner and repository are checked for GitHub name syntax only, not for existence; isPublic is the operator's declaration and is not verified with GitHub. useUpdateToken (default false) is the explicit choice to file reports with the update feature's token; the report's own token is then ignored but kept. It is refused unless apiBaseUrl equals the update source's API address (trailing slash and host case ignored), and turning it on is refused while no readable update token is stored. Switching it off or moving the host never clears the update token; a host change still clears the report's own token.
             *
             * Tag: issue-reports
             * Response: 400 The body named a target this server refuses to post to, or turned the update token on while no readable update token is stored (VALIDATION_FAILED; the detail says which rule), or chose the update token for an API address other than the update source's (ISSUE_REPORT_UPDATE_TOKEN_HOST_MISMATCH).
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks ISSUE_REPORT_MANAGE.
             */
            put("/settings") {
                call.respond(HttpStatusCode.OK, service.saveSettings(call.receive<IssueReportDto.UpdateSettingsRequestDto>()))
            }

            /**
             * Store the GitHub token used to file reports.
             *
             * Description: Requires ISSUE_REPORT_MANAGE. The token is sealed with this host's UPDATE_TOKEN_KEY before it is written and can never be read back through the API.
             *
             * Tag: issue-reports
             * Response: 400 The token was blank.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks ISSUE_REPORT_MANAGE.
             * Response: 409 No UPDATE_TOKEN_KEY is provisioned on this host, so the token could never be read back.
             */
            put("/token") {
                call.respond(HttpStatusCode.OK, service.setToken(call.receive<IssueReportDto.SetTokenRequestDto>().token))
            }

            /**
             * Remove the stored GitHub token.
             *
             * Description: Requires ISSUE_REPORT_MANAGE. Idempotent. Reports cannot be filed until a new token is stored.
             *
             * Tag: issue-reports
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks ISSUE_REPORT_MANAGE.
             */
            delete("/token") {
                call.respond(HttpStatusCode.OK, service.clearToken())
            }
        }
    }
}
