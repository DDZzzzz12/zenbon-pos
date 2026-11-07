/**
 * API client-backed implementation of [RoomElementDataProvider].
 *
 * Stage C Batch 9: flipped to throws-style.
 *
 * @since 1.0.0
 */
package org.example.project.core.data.provider.remote

import org.example.project.core.data.models.RoomElement
import org.example.project.core.data.provider.RoomElementDataProvider
import org.example.project.core.data.remote.RoomElementApiClient

class ApiClientRoomElementDataProvider(
    private val roomElementApiClient: RoomElementApiClient,
) : RoomElementDataProvider {
    override suspend fun getAllElements(): List<RoomElement> = roomElementApiClient.getAllRoomElements().elements.map { RoomElement.fromDto(it) }

    override suspend fun getElementsByLocationType(locationType: String): List<RoomElement> {
        val locationTypeId = locationType.toLongOrNull()
        return roomElementApiClient
            .getAllRoomElements(type = null, locationId = null)
            .elements
            .filter { locationTypeId == null || it.locationTypeId == locationTypeId }
            .map { RoomElement.fromDto(it) }
    }

    override suspend fun refresh(locationTypeId: Long) { /* no local cache */ }

    override suspend fun ensureSeeded(locationTypeId: Long) { /* no local cache */ }

    override fun resetSeed() { /* no local cache */ }
}
