package org.example.project.core.data.provider.local

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.example.project.core.data.cache.CacheChange
import org.example.project.core.data.cache.CacheChangeBus
import org.example.project.core.data.database.RTableDataManager
import org.example.project.core.data.models.RTable
import org.example.project.core.data.models.toRTable
import org.example.project.core.data.provider.RTableDataProvider
import org.example.project.core.data.remote.RTableApiClient
import org.example.project.core.domain.enums.websocket.ChangeEntityType

/**
 * Cache-backed [RTableDataProvider]. Per-location-type reads come from the local
 * SQLDelight cache; the server is hit only to seed/refresh that cache.
 */
class LocalRTableDataProvider(
    private val manager: RTableDataManager,
    private val apiClient: RTableApiClient,
    private val cacheChangeBus: CacheChangeBus,
) : RTableDataProvider {
    private val seeded = mutableSetOf<Long>()
    private val seedLock = Mutex()

    override suspend fun getAllRTables(): List<RTable> = apiClient.getAllTables(locationTypeId = null, includeInactive = true).tables.map { RTable.fromDto(it) }

    override suspend fun getRTablesByLocationTypeId(
        locationTypeId: Long,
        includeInactive: Boolean,
    ): List<RTable> =
        manager
            .selectByLocationType(locationTypeId)
            .map { it.toRTable() }
            .filter { includeInactive || it.isActive }

    override suspend fun getRTableById(id: Long): RTable? = manager.selectById(id)?.let { it.toRTable() }

    override suspend fun searchRTables(query: String): List<RTable> =
        apiClient.searchTables(query, limit = 5, includeInactive = false).tables.map { RTable.fromDto(it) }

    override suspend fun refresh(
        locationTypeId: Long,
        includeInactive: Boolean,
    ) {
        val fresh =
            apiClient
                .getAllTables(locationTypeId = locationTypeId, includeInactive = includeInactive)
                .tables
                .map { RTable.fromDto(it) }
        manager.replaceByLocationType(locationTypeId, fresh)
        cacheChangeBus.emit(CacheChange(ChangeEntityType.RTABLE, locationTypeId))
    }

    override suspend fun ensureSeeded(
        locationTypeId: Long,
        includeInactive: Boolean,
    ) {
        seedLock.withLock {
            if (locationTypeId in seeded) return
            refresh(locationTypeId, includeInactive)
            seeded += locationTypeId
        }
    }

    override fun resetSeed() {
        // Best-effort synchronous clear: the only concurrent writer is ensureSeeded
        // under the same single-threaded VM context, so clearing the set is atomic enough.
        seeded.clear()
    }
}
