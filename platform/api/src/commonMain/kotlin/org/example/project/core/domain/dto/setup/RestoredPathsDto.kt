package org.example.project.core.domain.dto.setup

import kotlinx.serialization.Serializable
import org.example.project.core.domain.enums.setting.RestoredPathDecision
import org.example.project.core.domain.enums.setting.RestoredPathSetting

/**
 * DTOs for the post-restore path review (`/api/v1/backup/restored-paths`).
 *
 * A backup restored onto another installation brings the original shop's absolute folder paths
 * with it. Those outside this installation's app-data directory are listed here, and every file
 * write to them pauses until an administrator keeps each one or switches it to the local default.
 */
object RestoredPathsDto {
    /**
     * One restored path awaiting review.
     *
     * @property setting Which configured path this is
     * @property configuredPath The path as restored from the backup
     * @property defaultPath What this installation would use instead ("Standard verwenden")
     * @property keepAllowed Whether "keep" may be chosen. False for the document path: the restored
     *   files were extracted into this installation's default folder, so only "use default" keeps
     *   the stored documents readable.
     */
    @Serializable
    data class PathDto(
        val setting: RestoredPathSetting,
        val configuredPath: String,
        val defaultPath: String,
        val keepAllowed: Boolean = true,
    )

    /**
     * The current review state.
     *
     * @property pending True while at least one restored path is unresolved — file writers using
     *   those paths are paused
     * @property paths The unresolved paths (empty when nothing is pending)
     */
    @Serializable
    data class ReviewDto(
        val pending: Boolean,
        val paths: List<PathDto>,
    )

    /** The decision for one flagged path. */
    @Serializable
    data class DecisionDto(
        val setting: RestoredPathSetting,
        val decision: RestoredPathDecision,
    )

    /**
     * Resolves one or more flagged paths. Settings that are not pending are ignored, so a repeated
     * submit is harmless; once every flagged path is resolved the pause lifts.
     */
    @Serializable
    data class ResolveRequestDto(
        val decisions: List<DecisionDto>,
    ) {
        fun validate() {
            require(decisions.isNotEmpty()) { "decisions must not be empty" }
            require(decisions.map { it.setting }.toSet().size == decisions.size) {
                "each setting may be decided only once per request"
            }
            decisions.firstOrNull { it.decision == RestoredPathDecision.KEEP && !it.setting.keepAllowed }?.let {
                throw IllegalArgumentException(
                    "${it.setting} cannot be kept: the restored files were extracted into this installation's " +
                        "default folder, so only USE_DEFAULT keeps the stored documents readable",
                )
            }
        }
    }
}
