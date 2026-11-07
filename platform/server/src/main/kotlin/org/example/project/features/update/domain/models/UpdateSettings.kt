package org.example.project.features.update.domain.models

import java.time.Instant

/**
 * The single update-check settings row, with the GitHub token already decrypted.
 *
 * Nothing above the repository ever sees ciphertext: `token` is either the usable token or null.
 * A null `token` is deliberately ambiguous on its own -- [tokenUndecryptable] is what tells the
 * two causes apart, and the caller must not collapse them. "No token" means the operator never
 * entered one; "undecryptable" means this database was restored onto a host whose
 * `UPDATE_TOKEN_KEY` differs from the one that sealed it, and the operator has to re-enter it.
 */
data class UpdateSettings(
    val channel: UpdateChannel,
    val apiBaseUrl: String,
    val autoApply: Boolean,
    /** Decrypted, or null when unset OR unreadable on this host -- see [tokenUndecryptable]. */
    val token: String?,
    /** True when ciphertext exists but this host's key cannot open it. */
    val tokenUndecryptable: Boolean,
    val tokenSetAt: Instant?,
    val lastCheckedAt: Instant?,
    val lastCheckOutcome: String?,
    /** The blocker that refused the last unattended install, or null. See [UpdateCheckOutcome]. */
    val lastBlockedReason: String?,
    val availableVersion: String?,
    val availableNotes: String?,
    val availableAssetId: Long?,
    val stagedPath: String?,
)
