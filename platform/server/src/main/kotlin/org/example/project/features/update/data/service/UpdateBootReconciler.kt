package org.example.project.features.update.data.service

import kotlinx.serialization.json.Json
import org.example.project.features.update.domain.models.UpdateCheckOutcome
import org.example.project.features.update.domain.models.UpdateMarker
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant

/**
 * Works out, on the next boot, whether the update that wrote the marker actually took.
 *
 * ### Why the version comparison is the only signal
 *
 * `restart-guard.sh` is the thing that reverts a failed update, and it is a shell script: it runs
 * as `ExecStartPre`, logs to `logger`, moves `pos-server.jar.bak` into place and tells the
 * application **nothing**. There is no exit code we see, no flag it sets, no row it writes. So the
 * server cannot be told it was rolled back -- it can only notice. Comparing the version that is
 * actually running against [UpdateMarker.targetVersion] is the sole way it can learn that the jar
 * it is executing is not the one the update installed.
 *
 * ### Why the marker is always deleted
 *
 * The marker outlives the process by design, and nothing else ever removes it. Left behind, it
 * would make every later boot re-report an update that finished months ago, and a future apply
 * could no longer tell "in progress" from "long finished". It is therefore cleared on every path
 * -- applied, rolled back, and unreadable.
 *
 * ### Why nothing here throws
 *
 * This runs on the boot path. A reconciler that failed would turn a bookkeeping problem into an
 * outage before opening time, so every failure is logged and swallowed.
 */
class UpdateBootReconciler(
    private val installDir: File,
    private val runningVersion: String,
    private val recordAudit: suspend (operation: String, detail: String) -> Unit,
    private val recordStatus: suspend (UpdateCheckOutcome) -> Unit = {},
) {
    /** Reads the marker if there is one, records the outcome, and clears it. Never throws. */
    suspend fun reconcile() {
        try {
            val marker = File(installDir, UpdateApplier.MARKER)
            if (!marker.isFile) return

            val parsed = parse(marker)
            if (parsed != null) recordOutcome(parsed)
            marker.delete()
        } catch (e: Exception) {
            logger.warn("Update boot reconciliation failed; continuing startup", e)
        }
    }

    private suspend fun recordOutcome(marker: UpdateMarker) {
        if (runningVersion == marker.targetVersion) {
            recordAudit(
                OPERATION_APPLIED,
                "Update to ${marker.targetVersion} applied successfully (started ${marker.startedAt}).",
            )
            recordStatus(UpdateCheckOutcome(checkedAt = Instant.now(), outcome = OUTCOME_UP_TO_DATE))
            return
        }
        recordAudit(
            OPERATION_ROLLED_BACK,
            "Update to ${marker.targetVersion} did not take: running $runningVersion after the restart guard " +
                "swapped the previous jar back in (started ${marker.startedAt}). A jar rollback does NOT undo a " +
                "schema migration -- if the new build migrated the database, restore ${marker.databaseBackupPath}.",
        )
        // The status row still says UPDATE_APPLYING from the apply; without this the operator's
        // screen reads "installing" forever after the guard has already reverted the jar.
        recordStatus(
            UpdateCheckOutcome(checkedAt = Instant.now(), outcome = OUTCOME_ROLLED_BACK, availableVersion = marker.targetVersion),
        )
    }

    /** An unreadable marker is not an error worth reporting -- it is only deleted. */
    private fun parse(marker: File): UpdateMarker? =
        runCatching { JSON.decodeFromString<UpdateMarker>(marker.readText()) }
            .onFailure { logger.warn("Unreadable ${UpdateApplier.MARKER}; deleting it", it) }
            .getOrNull()

    companion object {
        const val OPERATION_APPLIED = "UPDATE_APPLIED"
        const val OPERATION_ROLLED_BACK = "UPDATE_ROLLED_BACK"
        const val OUTCOME_ROLLED_BACK = "UPDATE_ROLLED_BACK"
        const val OUTCOME_UP_TO_DATE = "UP_TO_DATE"

        private val JSON = Json { ignoreUnknownKeys = true }
        private val logger = LoggerFactory.getLogger(UpdateBootReconciler::class.java)
    }
}
