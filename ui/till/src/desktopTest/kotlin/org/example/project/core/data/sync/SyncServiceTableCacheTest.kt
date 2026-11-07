package org.example.project.core.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import org.example.project.core.data.cache.CacheChangeBus
import org.example.project.core.data.database.ChoiceGroupDataManager
import org.example.project.core.data.database.ChoiceGroupOptionDataManager
import org.example.project.core.data.database.PaymentTypeDataManager
import org.example.project.core.data.database.ProductChoiceGroupDataManager
import org.example.project.core.data.database.RTableDataManager
import org.example.project.core.data.database.RoomElementDataManager
import org.example.project.core.data.database.SyncDataManager
import org.example.project.core.data.domain.impl.service.SyncServiceImpl
import org.example.project.core.data.domain.service.PreferenceSyncService
import org.example.project.core.data.local.DecimalColumnAdapter
import org.example.project.core.data.models.PaymentType
import org.example.project.core.data.remote.ChangeFeedApiClient
import org.example.project.core.data.remote.RTableApiClient
import org.example.project.core.data.remote.RoomElementApiClient
import org.example.project.core.domain.dto.payment.PaymentTypeDto
import org.example.project.core.domain.dto.setup.InitialSetupDto
import org.example.project.core.domain.dto.sync.ChangeFeedDto
import org.example.project.core.domain.dto.table.RTableDto
import org.example.project.core.domain.dto.table.RoomElementDto
import org.example.project.core.domain.dto.table.TableSessionDto
import org.example.project.core.domain.enums.setup.LogOperation
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.database.Local_choice_group_options
import org.example.project.database.Local_product_extras
import org.example.project.database.Local_products
import org.example.project.database.PosDatabase
import org.example.project.testutils.fakes.FakeChoiceGroupApiClient
import org.example.project.testutils.fakes.FakeKeyValueStore
import org.example.project.testutils.fakes.FakeLocalDatabaseManager
import org.example.project.testutils.fakes.FakeProductApiClient
import org.example.project.testutils.fakes.FakeProductCategoryApiClient
import org.example.project.testutils.fakes.FakeProductChoiceGroupApiClient
import org.example.project.testutils.fakes.FakeProductExtrasApiClient
import org.example.project.testutils.fakes.FakeProductExtrasAssignmentApiClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncServiceTableCacheTest {
    private class StubPreferenceSyncService : PreferenceSyncService {
        override suspend fun syncPreferences() {}
    }

    private class StubPaymentTypeApiClient : org.example.project.core.data.remote.PaymentTypeApiClient {
        override suspend fun getAllPaymentTypes(includeInactive: Boolean) =
            PaymentTypeDto.PaymentTypeListResponseDto(paymentTypes = emptyList(), total = 0, activeCount = 0, inactiveCount = 0)

        override suspend fun getPaymentTypeById(id: Long): PaymentTypeDto.PaymentTypeResponseDto = error("unused")

        override suspend fun getActivePaymentTypes(): List<PaymentTypeDto.PaymentTypeSummaryDto> = emptyList()

        override suspend fun createPaymentType(request: PaymentTypeDto.CreatePaymentTypeRequestDto) = error("unused")

        override suspend fun updatePaymentType(
            id: Long,
            request: PaymentTypeDto.UpdatePaymentTypeRequestDto,
        ) = error("unused")

        override suspend fun deletePaymentType(id: Long) {}

        override suspend fun getPaymentTypeStatistics(): PaymentTypeDto.PaymentTypeStatisticsDto = error("unused")

        override suspend fun syncPaymentTypes(lastSyncTime: String): List<PaymentTypeDto.PaymentTypeResponseDto> = emptyList()
    }

    private class FakeChangeFeedApiClient : ChangeFeedApiClient {
        override suspend fun getChanges(
            since: Long,
            limit: Int,
        ) = ChangeFeedDto.ResponseDto(entries = emptyList(), highWaterSeq = 0L, minAvailableSeq = 0L)
    }

    /** Returns a location-1 table for getTableById; everything else is unused. */
    private class FakeRTableApiClient : RTableApiClient {
        override suspend fun getTableById(id: Long): RTableDto.RTableResponseDto =
            RTableDto.RTableResponseDto(
                id = id,
                tableNumber = "T$id",
                seats = 4,
                locationTypeId = 1,
                locationTypeName = "Inside",
                locationTypeEnglishName = "Inside",
                locationTypeChineseName = "Inside",
                isVirtual = false,
                isTakeaway = false,
                tableStatus = "FREE",
                currentSessionId = null,
                hasActiveSession = false,
                positionX = null,
                positionY = null,
                rotation = null,
            )

        override suspend fun getAllTables(
            status: String?,
            locationTypeId: Long?,
            limit: Int?,
            offset: Int?,
            includeInactive: Boolean,
        ): RTableDto.RTableListResponseDto = error("unused")

        override suspend fun searchTables(
            query: String,
            limit: Int,
            includeInactive: Boolean,
        ) = error("unused")

        override suspend fun createTable(request: RTableDto.CreateRTableRequestDto) = error("unused")

        override suspend fun updateTable(
            id: Long,
            request: RTableDto.UpdateRTableRequestDto,
        ) = error("unused")

        override suspend fun batchUpdatePositions(request: RTableDto.BatchTablePositionUpdateDto) = error("unused")

        override suspend fun deleteTable(id: Long) = error("unused")

        override suspend fun syncTables(lastSyncTime: String): List<RTableDto.RTableResponseDto> = error("unused")

        override suspend fun getCurrentSession(
            tableId: Long,
            forEmployeeNumber: String?,
        ) = error("unused")

        override suspend fun transferTable(
            sourceTableId: Long,
            request: RTableDto.TransferTableRequestDto,
        ) = error("unused")

        override suspend fun deleteTableSession(
            tableId: Long,
            reason: String,
        ) = error("unused")

        override suspend fun getCustomerAndAddressBySessionId(sessionId: Long) = error("unused")

        override suspend fun assignCustomerAndAddress(
            sessionId: Long,
            request: TableSessionDto.AssignCustomerAndAddressRequestDto,
        ) = error("unused")

        override suspend fun removeCustomerAndAddress(sessionId: Long) = error("unused")

        override suspend fun requestBilling(tableId: Long) = error("unused")

        override suspend fun uploadTablesCsv(
            csvContent: ByteArray,
            fileName: String,
            overwrite: Boolean,
        ): InitialSetupDto.CsvImportResultDto = error("unused")

        override suspend fun getCombinablePartners(tableId: Long): List<Long> = error("unused")

        override suspend fun setCombinablePartners(
            tableId: Long,
            partnerIds: List<Long>,
        ): List<Long> = error("unused")

        override suspend fun getAllCombinablePairs(): List<RTableDto.CombinablePairDto> = error("unused")

        override suspend fun setAllCombinablePairs(pairs: List<RTableDto.CombinablePairDto>): List<RTableDto.CombinablePairDto> = error("unused")
    }

    private class NoopRoomElementApiClient : RoomElementApiClient {
        override suspend fun getAllRoomElements(
            type: String?,
            locationId: Long?,
            lastUpdateTime: String?,
        ) = RoomElementDto.RoomElementListResponseDto(
            elements = emptyList(),
            totalCount = 0,
            returnedCount = 0,
            appliedFilters = RoomElementDto.FilterInfo(),
        )

        override suspend fun getRoomElementById(id: Long): RoomElementDto.RoomElementResponseDto = error("unused")

        override suspend fun getRoomElementsByType(type: String) = error("unused")

        override suspend fun getRoomElementsByLocation(locationId: Long) = error("unused")

        override suspend fun createRoomElement(request: RoomElementDto.CreateRoomElementRequestDto) = error("unused")

        override suspend fun updateRoomElement(
            id: Long,
            request: RoomElementDto.UpdateRoomElementRequestDto,
        ) = error("unused")

        override suspend fun updateRoomElementPosition(
            id: Long,
            request: RoomElementDto.UpdateRoomElementPositionRequestDto,
        ) = error("unused")

        override suspend fun updateRoomElementDimensions(
            id: Long,
            request: RoomElementDto.UpdateRoomElementDimensionsRequestDto,
        ) = error("unused")

        override suspend fun deleteRoomElement(id: Long) = error("unused")

        override suspend fun syncRoomElements(lastSyncTime: String): List<RoomElementDto.RoomElementResponseDto> = error("unused")
    }

    private fun newDatabase(): PosDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        PosDatabase.Schema.create(driver)
        return PosDatabase(
            driver,
            local_choice_group_optionsAdapter =
                Local_choice_group_options.Adapter(price_deltaAdapter = DecimalColumnAdapter),
            local_productsAdapter = Local_products.Adapter(priceAdapter = DecimalColumnAdapter),
            local_product_extrasAdapter =
                Local_product_extras.Adapter(priceAdapter = DecimalColumnAdapter),
        )
    }

    private fun entry(
        type: ChangeEntityType,
        op: LogOperation,
        id: String,
    ) = ChangeFeedDto.EntryDto(seq = 1L, entityType = type, entityId = id, operation = op)

    @Test
    fun `applyChangeDelta syncs then deletes a cached table`() =
        runTest {
            val db = newDatabase()
            val rTableManager = RTableDataManager(db)
            val sync =
                SyncServiceImpl(
                    productCategoryApiClient = FakeProductCategoryApiClient(),
                    productApiClient = FakeProductApiClient(),
                    productExtrasApiClient = FakeProductExtrasApiClient(),
                    productExtrasAssignmentApiClient = FakeProductExtrasAssignmentApiClient(),
                    paymentTypeApiClient = StubPaymentTypeApiClient(),
                    choiceGroupApiClient = FakeChoiceGroupApiClient(),
                    productChoiceGroupApiClient = FakeProductChoiceGroupApiClient(),
                    syncDataManager = SyncDataManager(db, FakeKeyValueStore()),
                    choiceGroupDataManager = ChoiceGroupDataManager(db),
                    choiceGroupOptionDataManager = ChoiceGroupOptionDataManager(db),
                    productChoiceGroupDataManager = ProductChoiceGroupDataManager(db),
                    preferenceSyncService = StubPreferenceSyncService(),
                    localDatabaseManager = FakeLocalDatabaseManager.create(),
                    changeFeedApiClient = FakeChangeFeedApiClient(),
                    rTableApiClient = FakeRTableApiClient(),
                    roomElementApiClient = NoopRoomElementApiClient(),
                    rTableDataManager = rTableManager,
                    roomElementDataManager = RoomElementDataManager(db),
                    cacheChangeBus = CacheChangeBus(),
                )

            sync.applyChangeDelta(listOf(entry(ChangeEntityType.RTABLE, LogOperation.CREATE, "1")))
            assertEquals(listOf(1L), rTableManager.selectByLocationType(1).map { it.id })

            sync.applyChangeDelta(listOf(entry(ChangeEntityType.RTABLE, LogOperation.DELETE, "1")))
            assertEquals(emptyList<Long>(), rTableManager.selectByLocationType(1).map { it.id })
        }

    @Test
    fun `a receivable payment type round-trips createsReceivable through the local db`() =
        runTest {
            val db = newDatabase()
            val sync = SyncDataManager(db, FakeKeyValueStore())

            sync.insertOrUpdatePaymentType(id = 1L, name = "Ueberweisung", isActive = 1L, en = null, ch = null, createsReceivable = 1L)
            sync.insertOrUpdatePaymentType(id = 2L, name = "Bar", isActive = 1L, en = null, ch = null, createsReceivable = 0L)

            val stored = PaymentTypeDataManager(db).getAllPaymentTypes().map { PaymentType.fromDatabase(it) }.associateBy { it.id }
            assertTrue(stored.getValue(1L).createsReceivable, "the receivable flag survives the local db")
            assertTrue(!stored.getValue(2L).createsReceivable, "a taking tender stays non-receivable")
        }
}
