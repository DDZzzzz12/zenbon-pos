package org.example.project.core.data.provider.local

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.example.project.core.data.cache.CacheChange
import org.example.project.core.data.cache.CacheChangeBus
import org.example.project.core.data.database.RoomElementDataManager
import org.example.project.core.data.models.RoomElement
import org.example.project.core.data.models.toRoomElement
import org.example.project.core.data.provider.RoomElementDataProvider
import org.example.project.core.data.remote.RoomElementApiClient
import org.example.project.core.domain.enums.websocket.ChangeEntityType

/**
 * Cache-backed [RoomElementDataProvider]. Per-location-type reads come from the local
 * SQLDelight cache; the server is hit only to seed/refresh that cache.
 */
class LocalRoomElementDataProvider(
    private val manager: RoomElementDataManager,
    private val apiClient: RoomElementApiClient,
    private val cacheChangeBus: CacheChangeBus,
) : RoomElementDataProvider {
    private val seeded = mutableSetOf<Long>()
    private val seedLock = Mutex()

    override suspend fun getAllElements(): List<RoomElement> = apiClient.getAllRoomElements().elements.map { RoomElement.fromDto(it) }

    override suspend fun getElementsByLocationType(locationType: String): List<RoomElement> {
        val locationTypeId = locationType.toLongOrNull() ?: return emptyList()
        return manager.selectByLocationType(locationTypeId).map { it.toRoomElement() }
    }

    override suspend fun refresh(locationTypeId: Long) {
        // Use the wrapper-returning list endpoint + client-side filter (matches the proven
        // ApiClientRoomElementDataProvider path). The /by-location/{id} endpoint returns a bare
        // JSON array, which the client's RoomElementListResponseDto wrapper cannot deserialize.
        val fresh =
            apiClient
                .getAllRoomElements()
                .elements
                .map { RoomElement.fromDto(it) }
                .filter { it.locationTypeId == locationTypeId }
        manager.replaceByLocationType(locationTypeId, fresh)
        cacheChangeBus.emit(CacheChange(ChangeEntityType.ROOM_ELEMENT, locationTypeId))
    }

    override suspend fun ensureSeeded(locationTypeId: Long) {
        seedLock.withLock {
            if (locationTypeId in seeded) return
            refresh(locationTypeId)
            seeded += locationTypeId
        }
    }

    override fun resetSeed() {
        // Best-effort synchronous clear: the only concurrent writer is ensureSeeded
        // under the same single-threaded VM context, so clearing the set is atomic enough.
        seeded.clear()
    }
}
