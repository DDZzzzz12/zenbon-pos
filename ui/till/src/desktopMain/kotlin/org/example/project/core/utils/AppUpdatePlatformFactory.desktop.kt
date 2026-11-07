package org.example.project.core.utils

import org.example.project.core.domain.dto.update.UpdateDto
import org.example.project.features.appupdate.domain.AppUpdatePlatform

actual fun createAppUpdatePlatform(): AppUpdatePlatform = NoAppUpdatePlatformDesktop

private object NoAppUpdatePlatformDesktop : AppUpdatePlatform {
    override fun installedVersionCode(): Long? = null

    override suspend fun download(
        url: String,
        info: UpdateDto.AndroidClientDto,
        onProgress: (Long, Long) -> Unit,
    ) = Unit

    override fun canInstall(): Boolean = false

    override fun openInstallPermissionSettings() = Unit

    override suspend fun install() = Unit

    override fun discardDownload() = Unit
}
