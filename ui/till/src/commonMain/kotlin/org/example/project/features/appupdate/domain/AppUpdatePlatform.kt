package org.example.project.features.appupdate.domain

import org.example.project.core.domain.dto.update.UpdateDto

/**
 * The seam between the update flow and the operating system. Only Android has an APK to replace:
 * every other platform answers `null` from [installedVersionCode] and the flow never starts.
 */
interface AppUpdatePlatform {
    /** The installed build's versionCode, or null where self-update does not exist. */
    fun installedVersionCode(): Long?

    /** @throws AppUpdateException [AppUpdateFailure.DOWNLOAD_FAILED] or [AppUpdateFailure.HASH_MISMATCH]. */
    suspend fun download(
        url: String,
        info: UpdateDto.AndroidClientDto,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    )

    /** Whether Android lets this app install packages. */
    fun canInstall(): Boolean

    /** Opens the system page where the user allows installs from this app. */
    fun openInstallPermissionSettings()

    /**
     * Hands the downloaded file to the system installer and waits for its verdict.
     *
     * @throws AppUpdateException [AppUpdateFailure.INSTALL_CANCELLED] or [AppUpdateFailure.INSTALL_FAILED].
     */
    suspend fun install()

    /** Deletes any downloaded file. Safe to call at any time. */
    fun discardDownload()
}
