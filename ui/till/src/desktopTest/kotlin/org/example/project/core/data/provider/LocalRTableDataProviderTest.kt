package org.example.project.core.data.provider

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import org.example.project.core.data.cache.CacheChangeBus
import org.example.project.core.data.database.RTableDataManager
import org.example.project.core.data.local.DecimalColumnAdapter
import org.example.project.core.data.provider.local.LocalRTableDataProvider
import org.example.project.core.data.remote.RTableApiClient
import org.example.project.core.domain.dto.table.RTableDto
import org.example.project.core.domain.dto.table.TableSessionDto
import org.example.project.database.Local_choice_group_options
import org.example.project.database.Local_product_extras
import org.example.project.database.Local_products
import org.example.project.database.PosDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalRTableDataProviderTest {
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

    private fun dto(id: Long) =
        RTableDto.RTableResponseDto(
            id = id,
            tableNumber = "$id",
            seats = 4,
            locationTypeId = 1,
            locationTypeName = "Indoor",
            locationTypeEnglishName = "Indoor",
            locationTypeChineseName = "室内",
            isVirtual = false,
            isTakeaway = false,
            tableStatus = "FREE",
            currentSessionId = null,
            hasActiveSession = false,
            positionX = 0f,
            positionY = 0f,
            rotation = 0f,
            openAmount = null,
        )

    // Implement EVERY RTableApiClient method; getAllTables counts calls.
    private fun fakeApi(tables: List<RTableDto.RTableResponseDto>) =
        object : RTableApiClient {
            var calls = 0

            override suspend fun getAllTables(
                status: String?,
                locationTypeId: Long?,
                limit: Int?,
                offset: Int?,
                includeInactive: Boolean,
            ) = RTableDto.RTableListResponseDto(tables, tables.size, tables.size, RTableDto.FilterInfo()).also { calls++ }

            override suspend fun searchTables(
                query: String,
                limit: Int,
                includeInactive: Boolean,
            ) = RTableDto.RTableListResponseDto(tables, tables.size, tables.size, RTableDto.FilterInfo())

            override suspend fun getTableById(id: Long) = dto(id)

            override suspend fun createTable(request: RTableDto.CreateRTableRequestDto) = dto(0)

            override suspend fun updateTable(
                id: Long,
                request: RTableDto.UpdateRTableRequestDto,
            ) = dto(id)

            override suspend fun batchUpdatePositions(request: RTableDto.BatchTablePositionUpdateDto) =
                RTableDto.BatchUpdatePositionsResponseDto(success = true, updatedCount = 0, requestedCount = 0)

            override suspend fun deleteTable(id: Long) {}

            override suspend fun syncTables(lastSyncTime: String) = emptyList<RTableDto.RTableResponseDto>()

            override suspend fun getCurrentSession(
                tableId: Long,
                forEmployeeNumber: String?,
            ): Nothing = throw NotImplementedError()

            override suspend fun transferTable(
                sourceTableId: Long,
                request: RTableDto.TransferTableRequestDto,
            ): Nothing = throw NotImplementedError()

            override suspend fun deleteTableSession(
                tableId: Long,
                reason: String,
            ) {}

            override suspend fun getCustomerAndAddressBySessionId(sessionId: Long): Nothing = throw NotImplementedError()

            override suspend fun assignCustomerAndAddress(
                sessionId: Long,
                request: TableSessionDto.AssignCustomerAndAddressRequestDto,
            ): Nothing = throw NotImplementedError()

            override suspend fun removeCustomerAndAddress(sessionId: Long): Nothing = throw NotImplementedError()

            override suspend fun requestBilling(tableId: Long) {}

            override suspend fun uploadTablesCsv(
                csvContent: ByteArray,
                fileName: String,
                overwrite: Boolean,
            ): Nothing = throw NotImplementedError()

            override suspend fun getCombinablePartners(tableId: Long) = emptyList<Long>()

            override suspend fun setCombinablePartners(
                tableId: Long,
                partnerIds: List<Long>,
            ) = emptyList<Long>()

            override suspend fun getAllCombinablePairs() = emptyList<RTableDto.CombinablePairDto>()

            override suspend fun setAllCombinablePairs(pairs: List<RTableDto.CombinablePairDto>) = emptyList<RTableDto.CombinablePairDto>()
        }

    @Test
    fun `refresh fetches and caches - reads come from cache`() =
        runTest {
            val manager = RTableDataManager(db())
            val api = fakeApi(listOf(dto(1), dto(2)))
            val provider = LocalRTableDataProvider(manager, api, CacheChangeBus())
            provider.refresh(locationTypeId = 1)
            assertEquals(listOf(1L, 2L), provider.getRTablesByLocationTypeId(1).map { it.id })
        }

    @Test
    fun `ensureSeeded fetches once, second call is a no-op`() =
        runTest {
            val api = fakeApi(listOf(dto(1)))
            val provider = LocalRTableDataProvider(RTableDataManager(db()), api, CacheChangeBus())
            provider.ensureSeeded(1)
            provider.ensureSeeded(1)
            assertEquals(1, api.calls)
        }

    @Test
    fun `resetSeed re-enables seeding so ensureSeeded re-fetches`() =
        runTest {
            val api = fakeApi(listOf(dto(1)))
            val provider = LocalRTableDataProvider(RTableDataManager(db()), api, CacheChangeBus())
            provider.ensureSeeded(1)
            assertEquals(1, api.calls)
            provider.resetSeed()
            provider.ensureSeeded(1)
            assertEquals(2, api.calls)
        }
}
