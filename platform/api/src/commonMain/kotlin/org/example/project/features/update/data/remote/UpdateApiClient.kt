package org.example.project.features.update.data.remote

import org.example.project.core.domain.dto.update.UpdateDto

/**
 * API client for the software-update endpoints (`/api/update`).
 *
 * Mirrors the route family exactly, including its split into three deliberate steps: [checkNow]
 * asks, [stageNow] additionally downloads and verifies, [applyNow] installs. They are not folded
 * into one call because an operator has to be able to learn a release exists without spending two
 * hundred megabytes on it.
 *
 * **The token is write-only.** [setToken] sends one; nothing here returns one. That is a property
 * of `UpdateDto` rather than a rule this interface remembers — the read shapes have no token
 * field to populate.
 *
 * Throws [org.example.project.core.network.error.PosApiException] for non-2xx responses and
 * [org.example.project.core.network.error.NetworkApiException] for transport failures.
 */
interface UpdateApiClient {
    suspend fun status(): UpdateDto.StatusDto

    suspend fun settings(): UpdateDto.SettingsDto

    suspend fun saveSettings(request: UpdateDto.UpdateSettingsRequestDto): UpdateDto.SettingsDto

    suspend fun setToken(token: String): UpdateDto.SettingsDto

    suspend fun clearToken(): UpdateDto.SettingsDto

    suspend fun checkNow(): UpdateDto.CheckResultDto

    suspend fun stageNow(): UpdateDto.CheckResultDto

    /**
     * Installs the staged release.
     *
     * The server writes this response and then exits, so the connection drops mid-flight: a
     * transport failure here is "in progress", not "failed". Poll [status] after the restart.
     */
    suspend fun applyNow(): UpdateDto.ApplyStartedDto
}
