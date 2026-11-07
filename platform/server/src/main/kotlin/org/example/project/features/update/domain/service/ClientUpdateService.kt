package org.example.project.features.update.domain.service

import org.example.project.core.domain.dto.update.UpdateDto
import org.example.project.features.update.domain.models.AndroidClientDownload

/**
 * What a till may ask for. Read-only: staging is the updater's job (`UpdateService`), never a
 * request handler's.
 */
interface ClientUpdateService {
    /**
     * @throws org.example.project.infrastructure.errors.PosException 404 when no APK is staged.
     */
    fun androidClient(): UpdateDto.AndroidClientDto

    /**
     * @throws org.example.project.infrastructure.errors.PosException 404 when no APK is staged.
     */
    fun androidClientDownload(): AndroidClientDownload
}
