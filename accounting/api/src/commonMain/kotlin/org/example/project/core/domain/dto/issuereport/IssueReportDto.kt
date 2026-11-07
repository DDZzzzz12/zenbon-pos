package org.example.project.core.domain.dto.issuereport

import kotlinx.serialization.Serializable

/**
 * Wire shapes of `/api/issue-reports`.
 *
 * The GitHub token is write-only: [SetTokenRequestDto] is the only shape carrying one and it only
 * travels inbound. [SettingsDto] has no token property at all (same rule as `UpdateDto`) — neither
 * the report's own token nor the update token it may be told to reuse.
 */
object IssueReportDto {
    /** Which client filed the report. The till is a later UI-only addition. */
    @Serializable
    enum class Source { ADMIN, TILL }

    @Serializable
    data class ReportRequestDto(
        val title: String,
        val description: String,
        val source: Source,
        /** The screen the operator was on, e.g. "Documents"; null when unknown. */
        val screen: String? = null,
        /**
         * Attach a scrubbed server log excerpt. Refused by the server for public repositories, and
         * on a send only honoured through a [previewId] — a log excerpt is never posted unseen.
         */
        val includeLogs: Boolean = false,
        /**
         * The [PreviewDto.previewId] of the preview the operator read. When set, the server posts
         * exactly that snapshot's title and body (the text fields here are only length-checked).
         * Single use, bound to the employee who previewed, expires after 15 minutes. Ignored by
         * the preview call itself.
         */
        val previewId: String? = null,
    )

    /** Exactly what a send carrying [previewId] will post — the server keeps this snapshot. */
    @Serializable
    data class PreviewDto(
        val title: String,
        val body: String,
        /** `owner/repo` the issue would go to. */
        val repository: String,
        val isPublic: Boolean,
        /** Opaque, unguessable handle on the stored snapshot; pass it back as [ReportRequestDto.previewId]. */
        val previewId: String,
    )

    @Serializable
    data class CreatedDto(
        val issueNumber: Long,
        val issueUrl: String,
    )

    @Serializable
    enum class TokenState { NONE, SET, UNREADABLE }

    @Serializable
    data class SettingsDto(
        val owner: String,
        val repo: String,
        val apiBaseUrl: String,
        val isPublic: Boolean,
        val tokenState: TokenState,
        /** State of the report's OWN token. Masked, at most the last four characters; null unless [tokenState] is `SET`. */
        val tokenPreview: String?,
        val tokenSetAt: String?,
        /**
         * The Administrator chose to file reports with the update feature's token. The own token
         * above is then ignored (kept, not sent), and the server sends the update token only while
         * [apiBaseUrl] equals the update source's API address.
         */
        val useUpdateToken: Boolean,
        /**
         * State of the UPDATE feature's token — `NONE`, `SET` or `UNREADABLE` only; no preview and
         * no other part of it is ever exposed here.
         */
        val updateTokenState: TokenState,
    )

    /**
     * The target of problem reports. [owner] and [repo] are checked for GitHub *syntax* only — the
     * server does not ask GitHub whether the repository exists. [isPublic] is the operator's
     * declaration of the repository's visibility; the server does not verify it with GitHub, it only
     * acts on it (no log excerpt for a public target). Moving [apiBaseUrl] to a different host
     * clears the report's own stored token, so a token is never sent to a host it was not entered for.
     *
     * [useUpdateToken] is the explicit opt-in to file reports with the update feature's token. The
     * server refuses it (400) unless [apiBaseUrl] equals the update source's API address, and refuses
     * turning it on while no readable update token is stored. Switching it off or moving the host
     * never touches the update token, which belongs to the update feature.
     */
    @Serializable
    data class UpdateSettingsRequestDto(
        val owner: String,
        val repo: String,
        val apiBaseUrl: String,
        val isPublic: Boolean,
        val useUpdateToken: Boolean = false,
    )

    @Serializable
    data class SetTokenRequestDto(
        val token: String,
    ) {
        /** The token must never reach a log line through a request dump. */
        override fun toString(): String = "SetTokenRequestDto(token=[redacted])"
    }
}
