package org.example.project.core.data.remote.impl

import io.ktor.client.request.get
import org.example.project.core.data.remote.RotationApiClient
import org.example.project.core.domain.dto.rotation.RotationDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

class KtorRotationApiClient(
    private val httpCall: HttpCall,
) : RotationApiClient {
    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun getNext(): RotationDto.NextSnapshotDto = httpCall.httpClient.apiCall { get(url("api/tables/rotation/next")) }
}
