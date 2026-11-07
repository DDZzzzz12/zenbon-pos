/**
 * API client-backed implementation of [RTableDataProvider].
 *
 * Stage C Batch 9: flipped to throws-style.
 *
 * @since 1.0.0
 */
package org.example.project.core.data.provider.remote

import org.example.project.core.data.models.RTable
import org.example.project.core.data.provider.RTableDataProvider
import org.example.project.core.data.remote.RTableApiClient

class ApiClientRTableDataProvider(
    private val rTableApiClient: RTableApiClient,
) : RTableDataProvider {
    override suspend fun getAllRTables(): List<RTable> = rTableApiClient.getAllTables().tables.map { RTable.fromDto(it) }

    override suspend fun getRTablesByLocationTypeId(
        locationTypeId: Long,
        includeInactive: Boolean,
    ): List<RTable> =
        rTableApiClient
            .getAllTables(
                locationTypeId = locationTypeId,
                includeInactive = includeInactive,
            ).tables
            .map { RTable.fromDto(it) }

    override suspend fun getRTableById(id: Long): RTable? = RTable.fromDto(rTableApiClient.getTableById(id))

    override suspend fun searchRTables(query: String): List<RTable> = rTableApiClient.searchTables(query).tables.map { RTable.fromDto(it) }

    override suspend fun refresh(
        locationTypeId: Long,
        includeInactive: Boolean,
    ) { /* no local cache */ }

    override suspend fun ensureSeeded(
        locationTypeId: Long,
        includeInactive: Boolean,
    ) { /* no local cache */ }

    override fun resetSeed() { /* no local cache */ }
}
