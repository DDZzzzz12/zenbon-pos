package org.example.project.features.update.data.remote

import org.example.project.core.domain.dto.update.UpdateDto

/**
 * The till's side of Android self-update (`/api/update/client/android`). Both routes are public --
 * the check runs on the login screen -- so no session is needed.
 *
 * Throws [org.example.project.core.network.error.PosApiException] for non-2xx (404 = nothing staged
 * on this server) and [org.example.project.core.network.error.NetworkApiException] for transport failures.
 */
interface AppUpdateApiClient {
    suspend fun latestAndroidClient(): UpdateDto.AndroidClientDto

    /** Absolute URL of the APK download; the bytes are fetched by the platform, not by this client. */
    fun downloadUrl(): String
}
