package org.example.project.features.issuereport.domain.models

import java.time.Instant

/**
 * The stored report settings with the token already decrypted. [owner], [repo] and [isPublic] are
 * the operator's overrides, null while they follow the update channel; the service resolves them.
 * [token] is null both when none was set and when it is unreadable — [tokenUndecryptable] tells the
 * two apart (same contract as `UpdateSettings`).
 *
 * [useUpdateToken] is the Administrator's explicit choice to file reports with the update feature's
 * token instead of [token]. While it is true, [token] is ignored (kept, never sent); there is no
 * implicit fallback in either direction.
 */
data class IssueReportSettings(
    val owner: String?,
    val repo: String?,
    val apiBaseUrl: String,
    val isPublic: Boolean?,
    val token: String?,
    val tokenUndecryptable: Boolean,
    val tokenSetAt: Instant?,
    val useUpdateToken: Boolean = false,
) {
    /** A data-class dump would print the decrypted token; this one says only whether there is one. */
    override fun toString(): String =
        "IssueReportSettings(owner=$owner, repo=$repo, apiBaseUrl=$apiBaseUrl, isPublic=$isPublic, " +
            "token=${if (token != null) "[redacted]" else "null"}, tokenUndecryptable=$tokenUndecryptable, " +
            "tokenSetAt=$tokenSetAt, useUpdateToken=$useUpdateToken)"
}
