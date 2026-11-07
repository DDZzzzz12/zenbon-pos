package org.example.project.features.update.domain.models

import java.time.Instant

/**
 * What one nightly check found, recorded together so a half-written result cannot describe a
 * release that was never seen.
 *
 * When [availableVersion] is null the remaining release fields are meaningless and are cleared,
 * which is how "we checked and we are already current" is stored.
 */
data class UpdateCheckOutcome(
    val checkedAt: Instant,
    /** A short machine-readable word -- `UP_TO_DATE`, `UPDATE_AVAILABLE`, `FAILED`, ... */
    val outcome: String,
    val availableVersion: String? = null,
    val availableNotes: String? = null,
    val availableAssetId: Long? = null,
    /**
     * The [PreflightVerdict.Reason] name that refused an unattended install, or null when the run
     * never reached a preflight.
     *
     * It is written on every run, null included: a reason left over from a previous night would
     * otherwise keep accusing the shop of open bills long after they were closed.
     */
    val blockedReason: String? = null,
)
