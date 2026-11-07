package org.example.project.testutils.fakes

import org.example.project.core.data.remote.RotationApiClient
import org.example.project.core.domain.dto.rotation.RotationDto

/** Rotation client that answers "nobody is next" at version 0. */
class NoopRotationApiClient : RotationApiClient {
    override suspend fun getNext(): RotationDto.NextSnapshotDto = RotationDto.NextSnapshotDto(version = 0, next = null)
}
