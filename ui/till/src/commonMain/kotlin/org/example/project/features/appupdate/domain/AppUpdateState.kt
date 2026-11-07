package org.example.project.features.appupdate.domain

import org.example.project.core.domain.dto.update.UpdateDto

sealed interface AppUpdateState {
    /** Nothing to show. Also the state on platforms without self-update. */
    data object Idle : AppUpdateState

    data object Checking : AppUpdateState

    /** A manual check found nothing newer. */
    data object UpToDate : AppUpdateState

    data class Available(
        val info: UpdateDto.AndroidClientDto,
    ) : AppUpdateState

    data class Downloading(
        val info: UpdateDto.AndroidClientDto,
        val downloadedBytes: Long,
    ) : AppUpdateState

    /** Verified and on disk; waiting for the user to allow installs or confirm. */
    data class ReadyToInstall(
        val info: UpdateDto.AndroidClientDto,
        val needsPermission: Boolean,
    ) : AppUpdateState

    data class Installing(
        val info: UpdateDto.AndroidClientDto,
    ) : AppUpdateState

    /** A device-side failure; the installed app is untouched. [info] lets the dialog offer a retry. */
    data class Failed(
        val failure: AppUpdateFailure,
        val info: UpdateDto.AndroidClientDto?,
    ) : AppUpdateState
}

val AppUpdateState.isBusy: Boolean
    get() = this is AppUpdateState.Checking || this is AppUpdateState.Downloading || this is AppUpdateState.Installing
