package org.example.project.features.backup.domain.service

import org.example.project.core.domain.dto.setup.RestoredPathsDto
import org.example.project.core.domain.enums.setting.RestoredPathSetting

/**
 * The administrator's side of the post-restore path review.
 *
 * A restore flags the restored folder paths that lie outside this installation (see
 * `RestoredPathReviewStep`); while any is flagged, file writes to it are paused. This service lists
 * them and applies the admin's decision per path — keep it, or switch to this installation's
 * default — and lifts the pause once every flagged path is decided.
 */
interface RestoredPathReviewService {
    /** The flagged paths still awaiting a decision; `pending = false` in the normal case. */
    suspend fun getReview(): RestoredPathsDto.ReviewDto

    /**
     * Applies [request]'s decisions to the flagged paths it names; a setting that is not flagged is
     * ignored, so a repeated submit changes nothing.
     *
     * @return the review after the decisions — empty and `pending = false` once all are resolved
     * @throws org.example.project.infrastructure.errors.PosException VALIDATION_FAILED (400) for an
     *   empty request, a setting decided twice, or KEEP for the document path (its restored files
     *   live in this installation's default folder — see `RestoredPathSetting.keepAllowed`)
     */
    suspend fun resolve(request: RestoredPathsDto.ResolveRequestDto): RestoredPathsDto.ReviewDto

    /**
     * Brings the review in line with a path an administrator just stored for [setting] through its
     * own configuration endpoint (backup or DATEV config). A path of this installation resolves the
     * entry; another foreign path stays pending but is shown as configured (see
     * `RestoredPathPolicy.afterConfigured`). A setting that is not pending is left alone.
     */
    suspend fun onPathConfigured(
        setting: RestoredPathSetting,
        configuredPath: String,
    )
}
