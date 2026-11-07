package org.example.project.core.data.provider

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import org.example.project.core.data.cache.CacheChangeBus
import org.example.project.core.data.database.RoomElementDataManager
import org.example.project.core.data.local.DecimalColumnAdapter
import org.example.project.core.data.provider.local.LocalRoomElementDataProvider
import org.example.project.core.data.remote.RoomElementApiClient
import org.example.project.core.domain.dto.table.RoomElementDto
import org.example.project.database.Local_choice_group_options
import org.example.project.database.Local_product_extras
import org.example.project.database.Local_products
import org.example.project.database.PosDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalRoomElementDataProviderTest {
    private fun db(): PosDatabase {
        val d = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        PosDatabase.Schema.create(d)
        return PosDatabase(
            d,
            local_choice_group_optionsAdapter =
                Local_choice_group_options.Adapter(price_deltaAdapter = DecimalColumnAdapter),
            local_productsAdapter = Local_products.Adapter(priceAdapter = DecimalColumnAdapter),
            local_product_extrasAdapter =
                Local_product_extras.Adapter(priceAdapter = DecimalColumnAdapter),
        )
    }

    private fun el(
        id: Long,
        loc: Long,
    ) = RoomElementDto.RoomElementResponseDto(
        id = id,
        elementType = "WALL",
        positionX = 1f,
        positionY = 1f,
        width = 1f,
        height = 1f,
        rotation = 0f,
        locationTypeId = loc,
        metadata = null,
    )

    // Fake implementing the full RoomElementApiClient. getAllRoomElements (the wrapper endpoint
    // that refresh must use) counts calls; the by-location/by-type methods now return List<...>.
    private fun fakeApi(all: List<RoomElementDto.RoomElementResponseDto>) =
        object : RoomElementApiClient {
            var calls = 0

            override suspend fun getAllRoomElements(
                type: String?,
                locationId: Long?,
                lastUpdateTime: String?,
            ) = RoomElementDto
                .RoomElementListResponseDto(all, all.size, all.size, RoomElementDto.FilterInfo())
                .also { calls++ }

            override suspend fun getRoomElementById(id: Long) = all.first { it.id == id }

            override suspend fun getRoomElementsByType(type: String) = all

            override suspend fun getRoomElementsByLocation(locationId: Long) = all.filter { it.locationTypeId == locationId }

            override suspend fun createRoomElement(request: RoomElementDto.CreateRoomElementRequestDto) = el(99, 1)

            override suspend fun updateRoomElement(
                id: Long,
                request: RoomElementDto.UpdateRoomElementRequestDto,
            ) = el(id, 1)

            override suspend fun updateRoomElementPosition(
                id: Long,
                request: RoomElementDto.UpdateRoomElementPositionRequestDto,
            ) = el(id, 1)

            override suspend fun updateRoomElementDimensions(
                id: Long,
                request: RoomElementDto.UpdateRoomElementDimensionsRequestDto,
            ) = el(id, 1)

            override suspend fun deleteRoomElement(id: Long) {}

            override suspend fun syncRoomElements(lastSyncTime: String) = emptyList<RoomElementDto.RoomElementResponseDto>()
        }

    @Test
    fun `refresh fetches via getAllRoomElements, filters by location, and caches`() =
        runTest {
            val manager = RoomElementDataManager(db())
            val api = fakeApi(listOf(el(1, loc = 1), el(2, loc = 1), el(3, loc = 2)))
            val provider = LocalRoomElementDataProvider(manager, api, CacheChangeBus())
            provider.refresh(locationTypeId = 1)
            // Only the location-1 elements are cached; reads come from the cache.
            assertEquals(listOf(1L, 2L), provider.getElementsByLocationType("1").map { it.id })
        }

    @Test
    fun `ensureSeeded fetches once then resetSeed re-enables`() =
        runTest {
            val api = fakeApi(listOf(el(1, loc = 1)))
            val provider = LocalRoomElementDataProvider(RoomElementDataManager(db()), api, CacheChangeBus())
            provider.ensureSeeded(1)
            provider.ensureSeeded(1)
            assertEquals(1, api.calls)
            provider.resetSeed()
            provider.ensureSeeded(1)
            assertEquals(2, api.calls)
        }
}
