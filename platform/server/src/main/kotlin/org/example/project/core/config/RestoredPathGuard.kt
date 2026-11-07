package org.example.project.core.config

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.enums.setting.RestoredPathSetting
import org.example.project.core.error.PosErrorCode
import org.example.project.infrastructure.errors.PosException

/**
 * Refuses a file write to [setting]'s path while that path, restored from a backup of another
 * installation, still awaits an administrator's review. The server half of the post-restore pause;
 * the scheduler's half is in `SchedulerEngine`.
 *
 * @param operation What was about to write, for the refusal's detail (e.g. "DATEV Kassenbuch export")
 * @throws PosException [PosErrorCode.RESTORED_PATHS_NEED_REVIEW], 409
 */
suspend fun IShopConfigurationManager.requireRestoredPathReviewed(
    setting: RestoredPathSetting,
    operation: String,
) {
    val pendingPath = getRestoredPathsPendingReview()[setting] ?: return
    throw restoredPathNeedsReview(setting, pendingPath, operation)
}

/** The 409 for a write to a restored path that has not been reviewed yet. */
fun restoredPathNeedsReview(
    setting: RestoredPathSetting,
    pendingPath: String,
    operation: String,
): PosException =
    PosException(
        PosErrorCode.RESTORED_PATHS_NEED_REVIEW,
        HttpStatusCode.Conflict,
        "$operation refused: the restored $setting '$pendingPath' lies outside this installation " +
            "and has not been reviewed yet. Keep it or switch to the default via " +
            "/api/v1/backup/restored-paths first.",
    )
