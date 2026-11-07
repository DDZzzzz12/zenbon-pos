package org.example.project.features.issuereport.domain.models

/**
 * What problem reports may borrow from the update feature when the Administrator chose
 * `useUpdateToken`: its decrypted GitHub token and the API host that token was entered for.
 *
 * Read through `UpdateSettingsRepository.load()` only; the report feature never writes it. [token]
 * is null both when unset and when unreadable on this host — [tokenUndecryptable] tells the two
 * apart (same contract as `UpdateSettings`).
 */
data class UpdateTokenSource(
    val apiBaseUrl: String,
    val token: String?,
    val tokenUndecryptable: Boolean,
) {
    /** Never prints the token: a log line or a test failure must not carry it. */
    override fun toString(): String =
        "UpdateTokenSource(apiBaseUrl=$apiBaseUrl, token=${if (token != null) "[redacted]" else "null"}, " +
            "tokenUndecryptable=$tokenUndecryptable)"
}
