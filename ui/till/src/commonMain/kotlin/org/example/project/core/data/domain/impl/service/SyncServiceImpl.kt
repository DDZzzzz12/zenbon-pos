package org.example.project.core.data.domain.impl.service

import org.example.project.core.data.cache.CacheChange
import org.example.project.core.data.cache.CacheChangeBus
import org.example.project.core.data.database.ChoiceGroupDataManager
import org.example.project.core.data.database.ChoiceGroupOptionDataManager
import org.example.project.core.data.database.LocalDatabaseManager
import org.example.project.core.data.database.ProductChoiceGroupDataManager
import org.example.project.core.data.database.RTableDataManager
import org.example.project.core.data.database.RoomElementDataManager
import org.example.project.core.data.database.SyncDataManager
import org.example.project.core.data.domain.service.PreferenceSyncService
import org.example.project.core.data.domain.service.SyncService
import org.example.project.core.data.domain.service.dedupeByKey
import org.example.project.core.data.models.ChoiceGroup
import org.example.project.core.data.models.PaymentType
import org.example.project.core.data.models.Product
import org.example.project.core.data.models.ProductCategory
import org.example.project.core.data.models.ProductExtra
import org.example.project.core.data.models.ProductExtrasAssignment
import org.example.project.core.data.models.RTable
import org.example.project.core.data.models.RoomElement
import org.example.project.core.data.remote.ChangeFeedApiClient
import org.example.project.core.data.remote.ChoiceGroupApiClient
import org.example.project.core.data.remote.PaymentTypeApiClient
import org.example.project.core.data.remote.ProductApiClient
import org.example.project.core.data.remote.ProductCategoryApiClient
import org.example.project.core.data.remote.ProductChoiceGroupApiClient
import org.example.project.core.data.remote.ProductExtrasApiClient
import org.example.project.core.data.remote.ProductExtrasAssignmentApiClient
import org.example.project.core.data.remote.RTableApiClient
import org.example.project.core.data.remote.RoomElementApiClient
import org.example.project.core.domain.dto.sync.ChangeFeedDto
import org.example.project.core.domain.enums.setup.LogOperation
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.core.utils.VoucherCatalog

/**
 * Change-feed-driven implementation of [SyncService].
 *
 * Single layer: owns both the per-type remote fetch and the local SQLite write,
 * lifted verbatim from the retired SyncRepository. The cursor-driven sync loop
 * (Phase 8) feeds change-feed batches through [applyChangeDelta]; admin edits and
 * gap-recovery use [syncEntityByType] / [performCompleteSync].
 *
 * Throws PosApiException/NetworkApiException on remote failure; callers wrap in
 * runCatchingApi.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
class SyncServiceImpl(
    private val productCategoryApiClient: ProductCategoryApiClient,
    private val productApiClient: ProductApiClient,
    private val productExtrasApiClient: ProductExtrasApiClient,
    private val productExtrasAssignmentApiClient: ProductExtrasAssignmentApiClient,
    private val paymentTypeApiClient: PaymentTypeApiClient,
    private val choiceGroupApiClient: ChoiceGroupApiClient,
    private val productChoiceGroupApiClient: ProductChoiceGroupApiClient,
    private val syncDataManager: SyncDataManager,
    private val choiceGroupDataManager: ChoiceGroupDataManager,
    private val choiceGroupOptionDataManager: ChoiceGroupOptionDataManager,
    private val productChoiceGroupDataManager: ProductChoiceGroupDataManager,
    private val preferenceSyncService: PreferenceSyncService,
    private val localDatabaseManager: LocalDatabaseManager,
    private val changeFeedApiClient: ChangeFeedApiClient,
    private val rTableApiClient: RTableApiClient,
    private val roomElementApiClient: RoomElementApiClient,
    private val rTableDataManager: RTableDataManager,
    private val roomElementDataManager: RoomElementDataManager,
    private val cacheChangeBus: CacheChangeBus,
) : SyncService {
    // ---------------------------------------------------------------------
    // Public surface
    // ---------------------------------------------------------------------

    override suspend fun applyChangeDelta(entries: List<ChangeFeedDto.EntryDto>) {
        for (e in dedupeByKey(entries).filter { it.entityType.clientSynced }) {
            when (e.operation) {
                LogOperation.DELETE -> deleteLocalEntity(e.entityType, e.entityId)
                LogOperation.CREATE, LogOperation.UPDATE -> {
                    val id = e.entityId.toLongOrNull()
                    if (id != null) syncEntityById(e.entityType, id) else syncEntityByType(e.entityType)
                }
                LogOperation.ERROR -> { /* never synced */ }
            }
        }
    }

    override suspend fun syncEntityByType(
        entityType: ChangeEntityType,
        reset: Boolean,
    ) {
        when (entityType) {
            ChangeEntityType.CATEGORY -> fetchAndStoreProductCategories()
            ChangeEntityType.PRODUCT -> fetchAndStoreProducts()
            ChangeEntityType.PRODUCT_EXTRA -> fetchAndStoreProductExtras()
            ChangeEntityType.PRODUCT_EXTRAS_ASSIGNMENT -> fetchAndStoreProductExtrasAssignments()
            ChangeEntityType.CHOICE_GROUP -> fetchAndStoreChoiceGroups()
            ChangeEntityType.PRODUCT_CHOICE_GROUP -> fetchAndStoreProductChoiceGroups()
            ChangeEntityType.PAYMENT_TYPE -> fetchAndStorePaymentTypes()
            ChangeEntityType.SHOP_INFO,
            ChangeEntityType.SHOP_CONFIGURATION,
            -> preferenceSyncService.syncPreferences()
            ChangeEntityType.RTABLE -> {
                val all =
                    rTableApiClient
                        .getAllTables(locationTypeId = null, includeInactive = true)
                        .tables
                        .map { RTable.fromDto(it) }
                rTableDataManager.replaceAll(all)
                cacheChangeBus.emit(CacheChange(ChangeEntityType.RTABLE, null))
            }
            ChangeEntityType.ROOM_ELEMENT -> {
                val all = roomElementApiClient.getAllRoomElements().elements.map { RoomElement.fromDto(it) }
                roomElementDataManager.replaceAll(all)
                cacheChangeBus.emit(CacheChange(ChangeEntityType.ROOM_ELEMENT, null))
            }
            ChangeEntityType.OPENING_HOURS -> {
                // not cached locally (in-memory only)
            }
            else -> {} // audit-only types (EMPLOYEE, VAT, SETUP, TERMINAL, PRINTER_LOCATION, TIME_TRACKING) — no client sync
        }
    }

    override suspend fun syncEntityById(
        entityType: ChangeEntityType,
        entityId: Long,
    ) {
        when (entityType) {
            ChangeEntityType.PRODUCT -> {
                val product = Product.fromDto(productApiClient.getProductById(entityId))
                storeProductsLocally(listOf(product))
            }
            ChangeEntityType.CATEGORY -> {
                val category = ProductCategory.fromDto(productCategoryApiClient.getProductCategoryById(entityId))
                storeProductCategoriesLocally(listOf(category))
            }
            ChangeEntityType.PRODUCT_EXTRA -> {
                val extra = ProductExtra.fromDto(productExtrasApiClient.getProductExtraById(entityId))
                storeProductExtrasLocally(listOf(extra))
            }
            ChangeEntityType.PRODUCT_EXTRAS_ASSIGNMENT -> {
                // entityId is the PRODUCT id: refresh that product's extras assignments.
                val data =
                    productExtrasAssignmentApiClient
                        .getForProduct(entityId)
                        .map { ProductExtrasAssignment.fromDto(it) }
                storeProductExtrasAssignmentsLocally(data)
            }
            ChangeEntityType.CHOICE_GROUP -> {
                val group = ChoiceGroup.fromDto(choiceGroupApiClient.getById(entityId.toInt()))
                storeChoiceGroupsLocally(listOf(group))
            }
            ChangeEntityType.PRODUCT_CHOICE_GROUP -> {
                // entityId is the PRODUCT id: refresh that product's group attachments.
                val resp = productChoiceGroupApiClient.getForProduct(entityId)
                productChoiceGroupDataManager.deleteByProduct(entityId)
                resp.groups.forEachIndexed { index, dto ->
                    productChoiceGroupDataManager.insertOrReplace(entityId, dto.id.toLong(), index.toLong())
                }
                // ...and the group definitions they point at.
                storeChoiceGroupsLocally(resp.groups.map { ChoiceGroup.fromDto(it) })
            }
            ChangeEntityType.PAYMENT_TYPE -> {
                val paymentType = PaymentType.fromDto(paymentTypeApiClient.getPaymentTypeById(entityId))
                storePaymentTypesLocally(listOf(paymentType))
            }
            ChangeEntityType.SHOP_INFO,
            ChangeEntityType.SHOP_CONFIGURATION,
            -> preferenceSyncService.syncPreferences()
            ChangeEntityType.RTABLE -> {
                val t = RTable.fromDto(rTableApiClient.getTableById(entityId))
                rTableDataManager.upsertAll(listOf(t))
                cacheChangeBus.emit(CacheChange(ChangeEntityType.RTABLE, t.locationTypeId))
            }
            ChangeEntityType.ROOM_ELEMENT -> {
                val e = RoomElement.fromDto(roomElementApiClient.getRoomElementById(entityId))
                roomElementDataManager.upsertAll(listOf(e))
                cacheChangeBus.emit(CacheChange(ChangeEntityType.ROOM_ELEMENT, e.locationTypeId))
            }
            ChangeEntityType.OPENING_HOURS -> {
                // not cached locally (in-memory only)
            }
            else -> {} // audit-only types — no client sync
        }
    }

    override suspend fun deleteLocalEntity(
        entityType: ChangeEntityType,
        entityId: String,
    ) {
        val id = entityId.toLongOrNull() ?: return
        when (entityType) {
            ChangeEntityType.PRODUCT -> syncDataManager.deleteProduct(id)
            ChangeEntityType.CATEGORY -> syncDataManager.deleteProductCategory(id)
            ChangeEntityType.PRODUCT_EXTRA -> syncDataManager.deleteProductExtra(id)
            ChangeEntityType.PRODUCT_EXTRAS_ASSIGNMENT -> {
                // entityId is the PRODUCT id: re-resolve its remaining assignments.
                syncEntityById(ChangeEntityType.PRODUCT_EXTRAS_ASSIGNMENT, id)
            }
            ChangeEntityType.CHOICE_GROUP -> {
                // A deleted group takes its product attachments with it, or the till keeps offering an empty group.
                choiceGroupOptionDataManager.deleteByGroup(id)
                productChoiceGroupDataManager.deleteByGroup(id)
                choiceGroupDataManager.deleteById(id)
            }
            ChangeEntityType.PRODUCT_CHOICE_GROUP -> productChoiceGroupDataManager.deleteByProduct(id)
            ChangeEntityType.PAYMENT_TYPE -> syncDataManager.deletePaymentType(id)
            ChangeEntityType.RTABLE -> {
                rTableDataManager.deleteById(id)
                cacheChangeBus.emit(CacheChange(ChangeEntityType.RTABLE, null))
            }
            ChangeEntityType.ROOM_ELEMENT -> {
                roomElementDataManager.deleteById(id)
                cacheChangeBus.emit(CacheChange(ChangeEntityType.ROOM_ELEMENT, null))
            }
            ChangeEntityType.SHOP_INFO -> {}
            ChangeEntityType.SHOP_CONFIGURATION -> {}
            ChangeEntityType.OPENING_HOURS -> {}
            else -> {} // audit-only types (EMPLOYEE, VAT, SETUP, TERMINAL, PRINTER_LOCATION, TIME_TRACKING) — no local data to delete
        }
    }

    override fun clearLocalEntityData(entityType: ChangeEntityType) {
        when (entityType) {
            ChangeEntityType.PRODUCT -> syncDataManager.productManager.clearAllProducts()
            ChangeEntityType.CATEGORY -> syncDataManager.productCategoryManager.clearAllProductCategories()
            ChangeEntityType.PRODUCT_EXTRA -> syncDataManager.productExtraManager.clearAllProductExtras()
            ChangeEntityType.PRODUCT_EXTRAS_ASSIGNMENT -> syncDataManager.productExtrasAssignmentManager.deleteAll()
            ChangeEntityType.CHOICE_GROUP -> {
                choiceGroupDataManager.clearAll()
                choiceGroupOptionDataManager.clearAll()
            }
            ChangeEntityType.PRODUCT_CHOICE_GROUP -> productChoiceGroupDataManager.clearAll()
            ChangeEntityType.PAYMENT_TYPE -> syncDataManager.paymentTypeManager.clearAllPaymentTypes()
            ChangeEntityType.RTABLE -> {
                rTableDataManager.clear()
                cacheChangeBus.tryEmit(CacheChange(ChangeEntityType.RTABLE, null))
            }
            ChangeEntityType.ROOM_ELEMENT -> {
                roomElementDataManager.clear()
                cacheChangeBus.tryEmit(CacheChange(ChangeEntityType.ROOM_ELEMENT, null))
            }
            ChangeEntityType.SHOP_INFO -> syncDataManager.shopDataManager.clearShopInfo()
            ChangeEntityType.SHOP_CONFIGURATION -> {}
            ChangeEntityType.OPENING_HOURS -> {}
            else -> {} // audit-only types (EMPLOYEE, VAT, SETUP, TERMINAL, PRINTER_LOCATION, TIME_TRACKING) — no local data to clear
        }
    }

    override suspend fun performCompleteSync() {
        // Fetch-then-swap full reconcile. The retired tombstone pass
        // (checkLogForDeletingData) used to prune server-deleted rows; now the full
        // sync itself drops stale rows by clearing each cached type before re-storing.
        //
        // CRITICAL: fetch ALL types from the network FIRST, into in-memory lists, before
        // touching the DB. If any fetch throws, the exception propagates and the local DB
        // is left untouched (stale-but-complete) — never wiped. Only once every fetch has
        // succeeded do we clear+store, so a mid-sync network failure can no longer leave
        // the POS showing an empty/partial menu (a regression vs the old upsert sync).
        //
        // The clear+store swap runs inside a single SQLDelight transaction
        // (syncDataManager.inTransaction), so it commits once — no per-row commit
        // overhead and no empty-cache window between clear and store.

        // Capture the change-feed head BEFORE fetching, so the cursor we persist after the swap
        // reflects "current as of the start of this sync". A full sync that didn't advance the
        // cursor left it stale, so the reconcile-on-connect then re-applied every change since the
        // old cursor — redundant with the getAll we just did. Changes that land mid-sync (seq >
        // head) are still caught by the next reconcile, so capturing before (not after) never skips
        // anything. Best-effort: if the head can't be read, skip the cursor advance (don't make the
        // whole sync depend on the change-feed endpoint).
        val headSeq: Long? =
            try {
                changeFeedApiClient.getChanges(localDatabaseManager.getLastSeenSeq(), limit = 1).highWaterSeq
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }

        // Step 1 — fetch everything (network only, no DB mutation). Any throw aborts here
        // with the DB untouched.
        val categories = productCategoryApiClient.getAllProductCategories().map { ProductCategory.fromDto(it) }
        val products = productApiClient.getAllProducts().map { Product.fromDto(it) }
        val extras = productExtrasApiClient.getAllProductExtras().map { ProductExtra.fromDto(it) }
        val assignments = productExtrasAssignmentApiClient.getAll().items.map { ProductExtrasAssignment.fromDto(it) }
        val choiceGroups = choiceGroupApiClient.getAll().items.map { ChoiceGroup.fromDto(it) }
        val productChoiceGroups = productChoiceGroupApiClient.getAll().items
        val paymentTypes =
            paymentTypeApiClient
                .getAllPaymentTypes(includeInactive = true)
                .paymentTypes
                .map { PaymentType.fromDto(it) }

        // Step 2 — swap into the DB (all fetches succeeded), as ONE transaction.
        // FK-safe ordering: the only client FK is local_products.category_id ->
        // local_product_categories.id. Clear children before parents, store parents
        // before children.
        syncDataManager.inTransaction {
            // Clear (children -> parents):
            productChoiceGroupDataManager.clearAll()
            syncDataManager.productExtrasAssignmentManager.deleteAll()
            syncDataManager.productManager.clearAllProducts()
            choiceGroupOptionDataManager.clearAll()
            choiceGroupDataManager.clearAll()
            syncDataManager.productExtraManager.clearAllProductExtras()
            syncDataManager.productCategoryManager.clearAllProductCategories()
            syncDataManager.paymentTypeManager.clearAllPaymentTypes()

            // Store (parents -> children):
            storeProductCategoriesLocally(categories)
            storeProductsLocally(products)
            storeProductExtrasLocally(extras)
            storeProductExtrasAssignmentsLocally(assignments)
            storeChoiceGroupsLocally(choiceGroups)
            productChoiceGroups.forEach {
                productChoiceGroupDataManager.insertOrReplace(it.productId, it.groupId.toLong(), it.sortPosition.toLong())
            }
            storePaymentTypesLocally(paymentTypes)
        }

        // Preferences are a separate KeyValueStore-backed sync, not part of the table swap.
        preferenceSyncService.syncPreferences()

        // Advance the change-feed cursor to the head captured at the start. The local cache is now
        // current up to that point, so the next reconcile-on-connect pulls from here and re-applies
        // nothing it already covered. (Reconcile still drains anything with seq > head.)
        if (headSeq != null) {
            localDatabaseManager.setLastSeenSeq(headSeq)
        }
    }

    // ---------------------------------------------------------------------
    // Per-type full fetch + store (lifted from SyncRepositoryImpl)
    // ---------------------------------------------------------------------

    private suspend fun fetchAndStoreProductCategories() {
        val categories = productCategoryApiClient.getAllProductCategories().map { ProductCategory.fromDto(it) }
        storeProductCategoriesLocally(categories)
    }

    private suspend fun fetchAndStoreProducts() {
        val products = productApiClient.getAllProducts().map { Product.fromDto(it) }
        storeProductsLocally(products)
    }

    private suspend fun fetchAndStoreProductExtras() {
        val extras = productExtrasApiClient.getAllProductExtras().map { ProductExtra.fromDto(it) }
        storeProductExtrasLocally(extras)
    }

    private suspend fun fetchAndStoreProductExtrasAssignments() {
        val data = productExtrasAssignmentApiClient.getAll().items.map { ProductExtrasAssignment.fromDto(it) }
        storeProductExtrasAssignmentsLocally(data)
    }

    private suspend fun fetchAndStorePaymentTypes() {
        val paymentTypes =
            paymentTypeApiClient
                .getAllPaymentTypes(includeInactive = true)
                .paymentTypes
                .map { PaymentType.fromDto(it) }
        storePaymentTypesLocally(paymentTypes)
    }

    private suspend fun fetchAndStoreChoiceGroups() {
        val groups = choiceGroupApiClient.getAll().items.map { ChoiceGroup.fromDto(it) }
        storeChoiceGroupsLocally(groups)
    }

    private suspend fun fetchAndStoreProductChoiceGroups() {
        val items = productChoiceGroupApiClient.getAll().items
        syncDataManager.inTransaction {
            items.forEach {
                productChoiceGroupDataManager.insertOrReplace(it.productId, it.groupId.toLong(), it.sortPosition.toLong())
            }
        }
    }

    // ---------------------------------------------------------------------
    // Local store helpers (lifted from SyncRepositoryImpl)
    // ---------------------------------------------------------------------

    // The voucher category and article never enter the local catalogue, so no order grid (till or
    // order station) can offer them: a voucher is sold through the voucher dialog (QA MD-10). Filtered
    // here, at the one place every sync path stores through, rather than in each grid.
    private fun storeProductCategoriesLocally(data: List<ProductCategory>) =
        syncDataManager.inTransaction {
            data.filterNot { VoucherCatalog.isVoucherCategory(it.name) }.forEach { category ->
                syncDataManager.insertOrUpdateProductCategory(
                    id = category.id,
                    name = category.name,
                    enName = category.enName,
                    chName = category.chName,
                    description = category.description,
                    isActive = if (category.isActive) 1L else 0L,
                    sortPosition = category.sortPosition,
                )
            }
        }

    private fun storeProductsLocally(data: List<Product>) =
        syncDataManager.inTransaction {
            data.filterNot { VoucherCatalog.isVoucherProduct(it.productType) }.forEach { product ->
                syncDataManager.insertOrUpdateProduct(
                    id = product.id,
                    productNumber = product.productNumber,
                    name = product.name,
                    enName = product.enName,
                    chName = product.chName,
                    description = product.description,
                    price = product.price,
                    productType = product.productType,
                    categoryId = product.categoryId,
                    isActive = if (product.isActive) 1L else 0L,
                    allowCustomPrice = if (product.allowCustomPrice) 1L else 0L,
                    imageUrl = product.imageUrl,
                )
            }
        }

    private fun storeProductExtrasLocally(data: List<ProductExtra>) =
        syncDataManager.inTransaction {
            data.forEach { extra ->
                syncDataManager.insertOrUpdateProductExtra(
                    id = extra.id,
                    name = extra.name,
                    enName = extra.enName,
                    chName = extra.chName,
                    description = extra.description,
                    price = extra.price,
                    isActive = if (extra.isActive) 1L else 0L,
                )
            }
        }

    private fun storeProductExtrasAssignmentsLocally(data: List<ProductExtrasAssignment>) =
        syncDataManager.inTransaction {
            data.forEach {
                syncDataManager.insertOrReplaceProductExtrasAssignment(it.productId, it.extrasId.toLong())
            }
        }

    private fun storePaymentTypesLocally(data: List<PaymentType>) =
        syncDataManager.inTransaction {
            data.forEach { paymentType ->
                syncDataManager.insertOrUpdatePaymentType(
                    id = paymentType.id,
                    name = paymentType.name,
                    en = paymentType.enName,
                    ch = paymentType.chName,
                    isActive = if (paymentType.isActive) 1L else 0L,
                    createsReceivable = if (paymentType.createsReceivable) 1L else 0L,
                )
            }
        }

    private fun storeChoiceGroupsLocally(data: List<ChoiceGroup>) =
        syncDataManager.inTransaction {
            data.forEach { group ->
                choiceGroupDataManager.insertOrUpdate(
                    id = group.id.toLong(),
                    name = group.name,
                    enName = group.enName,
                    chName = group.chName,
                    minSelections = group.minSelections.toLong(),
                    maxSelections = group.maxSelections.toLong(),
                    isActive = 1L,
                )
                choiceGroupOptionDataManager.deleteByGroup(group.id.toLong())
                group.options.forEach { option ->
                    choiceGroupOptionDataManager.insertOrReplace(
                        groupId = group.id.toLong(),
                        optionId = option.optionId.toLong(),
                        priceDelta = option.priceDelta,
                        sortPosition = option.sortPosition.toLong(),
                        isDefault = if (option.isDefault) 1L else 0L,
                    )
                }
            }
        }
}
