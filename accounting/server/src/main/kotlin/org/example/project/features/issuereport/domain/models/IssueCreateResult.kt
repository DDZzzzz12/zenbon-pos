package org.example.project.features.issuereport.domain.models

/** Outcome of one attempt to create an issue. Failures are values, never exceptions. */
sealed interface IssueCreateResult {
    data class Created(
        val number: Long,
        val url: String,
    ) : IssueCreateResult

    /** 401, a 403 that is not a rate limit, or a 404 seen WITH a token (GitHub hides repos from bad tokens). */
    data object TokenRejected : IssueCreateResult

    data class RateLimited(
        val retryAfterSeconds: Long?,
    ) : IssueCreateResult

    data class Unreachable(
        val reason: String,
    ) : IssueCreateResult
}
