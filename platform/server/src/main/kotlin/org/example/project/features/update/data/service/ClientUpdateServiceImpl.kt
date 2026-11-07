package org.example.project.features.update.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.update.UpdateDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.update.domain.models.AndroidClientDownload
import org.example.project.features.update.domain.service.ClientUpdateService
import org.example.project.infrastructure.errors.PosException

class ClientUpdateServiceImpl(
    private val store: ClientArtifactStore,
) : ClientUpdateService {
    override fun androidClient(): UpdateDto.AndroidClientDto {
        val staged = store.current() ?: throw nothingStaged()
        return UpdateDto.AndroidClientDto(
            versionCode = staged.versionCode,
            versionName = staged.versionName,
            sha256 = staged.sha256,
            sizeBytes = staged.sizeBytes,
            releaseNotes = staged.releaseNotes,
        )
    }

    override fun androidClientDownload(): AndroidClientDownload {
        val staged = store.current() ?: throw nothingStaged()
        val file = store.apkFile() ?: throw nothingStaged()
        return AndroidClientDownload(file = file, sha256 = staged.sha256)
    }

    private fun nothingStaged() = PosException(PosErrorCode.UPDATE_NOT_STAGED, HttpStatusCode.NotFound, "no Android client build is staged on this server")
}
