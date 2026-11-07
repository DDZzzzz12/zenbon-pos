package org.example.project.testutils.fakes

import org.example.project.core.data.models.RTable
import org.example.project.core.data.provider.RTableDataProvider

/** No tables: for tests that only need a TableOrderViewModel to exist. */
class StubRTableDataProvider : RTableDataProvider {
    override suspend fun getAllRTables(): List<RTable> = emptyList()

    override suspend fun getRTablesByLocationTypeId(
        locationTypeId: Long,
        includeInactive: Boolean,
    ): List<RTable> = emptyList()

    override suspend fun getRTableById(id: Long): RTable? = null

    override suspend fun searchRTables(query: String): List<RTable> = emptyList()

    override suspend fun refresh(
        locationTypeId: Long,
        includeInactive: Boolean,
    ) = Unit

    override suspend fun ensureSeeded(
        locationTypeId: Long,
        includeInactive: Boolean,
    ) = Unit

    override fun resetSeed() = Unit
}
