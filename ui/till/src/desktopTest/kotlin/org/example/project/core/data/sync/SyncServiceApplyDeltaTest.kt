package org.example.project.core.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import org.example.project.core.data.cache.CacheChangeBus
import org.example.project.core.data.database.ChoiceGroupDataManager
import org.example.project.core.data.database.ChoiceGroupOptionDataManager
import org.example.project.core.data.database.ProductChoiceGroupDataManager
import org.example.project.core.data.database.RTableDataManager
import org.example.project.core.data.database.RoomElementDataManager
import org.example.project.core.data.database.SyncDataManager
import org.example.project.core.data.domain.impl.service.SyncServiceImpl
import org.example.project.core.data.domain.service.PreferenceSyncService
import org.example.project.core.data.local.DecimalColumnAdapter
import org.example.project.core.data.remote.ChangeFeedApiClient
import org.example.project.core.data.remote.RTableApiClient
import org.example.project.core.data.remote.RoomElementApiClient
import org.example.project.core.domain.dto.payment.PaymentTypeDto
import org.example.project.core.domain.dto.product.ProductCategoryDto
import org.example.project.core.domain.dto.product.ProductDto
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
import org.example.project.money.Decimal
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SyncServiceApplyDeltaTest {
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

    private fun product(id: Long) =
        ProductDto.ProductResponseDto(
            id = id,
            productNumber = "P$id",
            name = "Product $id",
            description = null,
            price = "1.00",
            categoryId = 1,
            categoryName = "Cat",
            productType = "FOOD",
            isActive = true,
        )

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
        seq: Long,
        type: ChangeEntityType,
        id: String,
        op: LogOperation,
    ) = ChangeFeedDto.EntryDto(seq = seq, entityType = type, entityId = id, operation = op)

    // applyChangeDelta tests never call performCompleteSync, so the head fetch is unused here.
    private class FakeChangeFeedApiClient : ChangeFeedApiClient {
        override suspend fun getChanges(
            since: Long,
            limit: Int,
        ) = ChangeFeedDto.ResponseDto(entries = emptyList(), highWaterSeq = 0L, minAvailableSeq = 0L)
    }

    // RTABLE/ROOM_ELEMENT paths are not exercised by these tests; everything throws if touched.
    private class NoopRTableApiClient : RTableApiClient {
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

        override suspend fun getTableById(id: Long): RTableDto.RTableResponseDto = error("unused")

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

    private fun buildService(
        database: PosDatabase,
        syncDataManager: SyncDataManager,
        productApiClient: FakeProductApiClient,
        productCategoryApiClient: FakeProductCategoryApiClient = FakeProductCategoryApiClient(),
    ) = SyncServiceImpl(
        productCategoryApiClient = productCategoryApiClient,
        productApiClient = productApiClient,
        productExtrasApiClient = FakeProductExtrasApiClient(),
        productExtrasAssignmentApiClient = FakeProductExtrasAssignmentApiClient(),
        paymentTypeApiClient = StubPaymentTypeApiClient(),
        choiceGroupApiClient = FakeChoiceGroupApiClient(),
        productChoiceGroupApiClient = FakeProductChoiceGroupApiClient(),
        syncDataManager = syncDataManager,
        choiceGroupDataManager = ChoiceGroupDataManager(database),
        choiceGroupOptionDataManager = ChoiceGroupOptionDataManager(database),
        productChoiceGroupDataManager = ProductChoiceGroupDataManager(database),
        preferenceSyncService = StubPreferenceSyncService(),
        localDatabaseManager = FakeLocalDatabaseManager.create(),
        changeFeedApiClient = FakeChangeFeedApiClient(),
        rTableApiClient = NoopRTableApiClient(),
        roomElementApiClient = NoopRoomElementApiClient(),
        rTableDataManager = RTableDataManager(database),
        roomElementDataManager = RoomElementDataManager(database),
        cacheChangeBus = CacheChangeBus(),
    )

    @Test
    fun `applyChangeDelta upserts and deletes products, ignores ERROR and non-synced entries`() =
        runTest {
            val database = newDatabase()
            val syncDataManager = SyncDataManager(database, FakeKeyValueStore())

            // Seed: a category (FK target) plus product 7 (to be deleted).
            syncDataManager.insertOrUpdateProductCategory(
                id = 1,
                name = "Cat",
                description = null,
                isActive = 1L,
                sortPosition = 0.0,
            )
            syncDataManager.insertOrUpdateProduct(
                id = 7,
                productNumber = "P7",
                name = "Product 7",
                enName = null,
                chName = null,
                description = null,
                price = Decimal.parse("1.00"),
                productType = "FOOD",
                categoryId = 1,
                isActive = 1L,
                allowCustomPrice = 0L,
            )

            val productApi =
                FakeProductApiClient().apply {
                    getProductByIdResult = Result.success(product(42))
                }

            val service = buildService(database, syncDataManager, productApi)

            service.applyChangeDelta(
                listOf(
                    entry(10, ChangeEntityType.PRODUCT, "42", LogOperation.UPDATE),
                    entry(11, ChangeEntityType.PRODUCT, "7", LogOperation.DELETE),
                    entry(12, ChangeEntityType.PRODUCT, "99", LogOperation.ERROR),
                    // audit-only (clientSynced = false) — must be ignored.
                    entry(13, ChangeEntityType.EMPLOYEE, "5", LogOperation.UPDATE),
                ),
            )

            // Product 42 upserted from the API.
            assertNotNull(syncDataManager.productManager.getProductById(42))
            // Product 7 deleted locally.
            assertNull(syncDataManager.productManager.getProductById(7))
            // ERROR entry (id 99) never fetched/stored.
            assertNull(syncDataManager.productManager.getProductById(99))
            // Audit-only employee never touches product cache (sanity: only 42 present).
            assertEquals(listOf(42L), syncDataManager.productManager.getAllProductsWithInactive().map { it.id })
        }

    @Test
    fun `performCompleteSync preserves local data when a fetch fails`() =
        runTest {
            val database = newDatabase()
            val syncDataManager = SyncDataManager(database, FakeKeyValueStore())

            // Seed a complete local cache that must NOT be wiped by a failed sync.
            syncDataManager.insertOrUpdateProductCategory(
                id = 1,
                name = "Cat",
                description = null,
                isActive = 1L,
                sortPosition = 0.0,
            )
            syncDataManager.insertOrUpdateProduct(
                id = 7,
                productNumber = "P7",
                name = "Product 7",
                enName = null,
                chName = null,
                description = null,
                price = Decimal.parse("1.00"),
                productType = "FOOD",
                categoryId = 1,
                isActive = 1L,
                allowCustomPrice = 0L,
            )

            // Categories fetch succeeds, products fetch THROWS partway through the fetch phase.
            val productApi =
                FakeProductApiClient().apply {
                    getAllProductsResult = Result.failure(RuntimeException("network down"))
                }
            val service = buildService(database, syncDataManager, productApi)

            assertFailsWith<RuntimeException> { service.performCompleteSync() }

            // Pre-existing local data is still present — the failed fetch never cleared the DB.
            assertNotNull(syncDataManager.productManager.getProductById(7))
            assertEquals(listOf(7L), syncDataManager.productManager.getAllProductsWithInactive().map { it.id })
        }

    private fun category(
        id: Int,
        name: String,
    ) = ProductCategoryDto.ProductCategoryResponseDto(
        id = id,
        name = name,
        description = null,
        isActive = true,
        sortPosition = 0.0,
        updatedAt = "2026-10-03",
    )

    // QA MD-10: the voucher category and the VOU article reached the till's grid and could be
    // ordered on a table, billing a 0,00 sale with no voucher. They must never enter the local catalogue.
    @Test
    fun `performCompleteSync keeps the voucher category and article out of the local catalogue`() =
        runTest {
            val database = newDatabase()
            val syncDataManager = SyncDataManager(database, FakeKeyValueStore())
            val voucher = product(2).copy(productNumber = "VOU", productType = "VOUCHER", categoryId = 9, price = "0.00")
            val productApi = FakeProductApiClient().apply { getAllProductsResult = Result.success(listOf(product(1), voucher)) }
            val categoryApi =
                FakeProductCategoryApiClient().apply {
                    getAllProductCategoriesResult = Result.success(listOf(category(1, "Cat"), category(9, "VOUCHER")))
                }

            buildService(database, syncDataManager, productApi, categoryApi).performCompleteSync()

            assertEquals(listOf(1L), syncDataManager.productManager.getAllProductsWithInactive().map { it.id })
            assertEquals(
                listOf(1L),
                syncDataManager.productCategoryManager.getAllProductCategories().map { it.id },
            )
        }

    @Test
    fun `a change-feed update for the voucher article does not store it`() =
        runTest {
            val database = newDatabase()
            val syncDataManager = SyncDataManager(database, FakeKeyValueStore())
            val productApi =
                FakeProductApiClient().apply {
                    getProductByIdResult = Result.success(product(42).copy(productNumber = "VOU", productType = "VOUCHER"))
                }

            buildService(database, syncDataManager, productApi)
                .applyChangeDelta(listOf(entry(1, ChangeEntityType.PRODUCT, "42", LogOperation.UPDATE)))

            assertNull(syncDataManager.productManager.getProductById(42))
        }
}
