package org.example.project.features.update.data.remote.impl

import io.ktor.client.request.get
import org.example.project.core.domain.dto.update.UpdateDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall
import org.example.project.features.update.data.remote.AppUpdateApiClient

class KtorAppUpdateApiClient(
    private val httpCall: HttpCall,
) : AppUpdateApiClient {
    override suspend fun latestAndroidClient(): UpdateDto.AndroidClientDto = httpCall.httpClient.apiCall { get(downloadBase()) }

    override fun downloadUrl(): String = "${downloadBase()}/download"

    private fun downloadBase(): String = "${httpCall.getBaseUrl()}$PATH"

    private companion object {
        const val PATH = "api/update/client/android"
    }
}
