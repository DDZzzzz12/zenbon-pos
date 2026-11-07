package org.example.project.features.update.domain.models

import kotlinx.serialization.Serializable

/**
 * The one thing an update leaves behind for the *next* process to read.
 *
 * The applier exits mid-update; systemd, not the application, decides what runs next. Nothing of
 * the update's intent survives in memory, so the marker file is the only channel between the
 * process that swapped the jar and the process that comes back up -- whichever jar that turns out
 * to be. [UpdateBootReconciler][org.example.project.features.update.data.service.UpdateBootReconciler]
 * is its only reader.
 */
@Serializable
data class UpdateMarker(
    /** The version the applier was installing. Compared against the version that actually booted. */
    val targetVersion: String,
    /**
     * The pre-apply database backup. The one thing that makes a failed migration recoverable.
     *
     * A jar rollback cannot undo a Flyway migration: `restart-guard.sh` can put the old binary
     * back, but it faces the new schema. Recovery has to be one restore, not an investigation,
     * so the path is recorded here and repeated verbatim in the rollback audit entry.
     */
    val databaseBackupPath: String,
    /** ISO-8601 instant at which the apply began. */
    val startedAt: String,
)
