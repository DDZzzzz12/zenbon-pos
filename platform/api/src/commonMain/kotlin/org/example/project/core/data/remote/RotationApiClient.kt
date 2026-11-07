package org.example.project.core.data.remote

import org.example.project.core.domain.dto.rotation.RotationDto

/** API client for the table-rotation "next employee" snapshot. */
interface RotationApiClient {
    suspend fun getNext(): RotationDto.NextSnapshotDto
}
