/**
 * Coordinating manager for all synchronized data operations with local database.
 *
 * This class acts as a facade that composes all specialized data managers
 * for different sync tables. It delegates operations to the appropriate
 * data manager and coordinates multi-table transactions.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.database

import co.touchlab.kermit.Logger
import org.example.project.database.PosDatabase
import org.example.project.money.Decimal

/**
 * Facade for managing all synchronized data operations.
 *
 * Composes specialized data managers and provides unified interface for
 * sync operations across all tables.
 */
class SyncDataManager(
    private val database: PosDatabase,
    keyValueStore: KeyValueStore,
) {
    private val logger = Logger.withTag("SyncDataManager")

    // Specialized data managers
    val productCategoryManager = ProductCategoryDataManager(database)
    val productManager = ProductDataManager(database)
    val productExtraManager = ProductExtraDataManager(database)
    val productExtrasAssignmentManager = ProductExtrasAssignmentDataManager(database)
    val paymentTypeManager = PaymentTypeDataManager(database)

    // The app's one KeyValueStore (secret-protecting), never a second raw store over the same table.
    val shopDataManager = ShopDataManager(keyValueStore)

    /**
     * Runs [body] inside a single SQLDelight transaction on the shared [PosDatabase].
     *
     * Use for bulk writes (sync store phases) so row-by-row inserts commit once
     * instead of once per statement. All DataManagers share the same database, so
     * calls through any of them (including ChoiceGroup/RTable managers) enlist in
     * this transaction. Nested calls join the outer transaction.
     */
    fun inTransaction(body: () -> Unit) {
        database.transaction { body() }
    }

    /**
     * Clears all sync data from local database.
     *
     * Use this during development when schema changes require resetting the database.
     * For production, implement proper migrations instead.
     */
    fun clearAllSyncData() {
        logger.i { "Clear all sync data" }
        try {
            productExtrasAssignmentManager.deleteAll()
        } catch (
            e: Exception,
        ) {
            logger.w { "clearAllSyncData: productExtrasAssignment cleanup failed (table may not exist): ${e.message}" }
        }
        try {
            productManager.clearAllProducts()
        } catch (e: Exception) {
            logger.w { "clearAllSyncData: product cleanup failed: ${e.message}" }
        }
        try {
            productExtraManager.clearAllProductExtras()
        } catch (e: Exception) {
            logger.w { "clearAllSyncData: productExtra cleanup failed: ${e.message}" }
        }
        try {
            productCategoryManager.clearAllProductCategories()
        } catch (
            e: Exception,
        ) {
            logger.w { "clearAllSyncData: productCategory cleanup failed: ${e.message}" }
        }
        try {
            paymentTypeManager.clearAllPaymentTypes()
        } catch (e: Exception) {
            logger.w { "clearAllSyncData: paymentType cleanup failed: ${e.message}" }
        }
        try {
            shopDataManager.clearShopInfo()
        } catch (e: Exception) {
            logger.w { "clearAllSyncData: shopInfo cleanup failed: ${e.message}" }
        }
    }

    // Convenience delegation methods for backward compatibility
    // Product Category operations
    fun insertOrUpdateProductCategory(
        id: Long,
        name: String,
        enName: String? = null,
        chName: String? = null,
        description: String?,
        isActive: Long,
        sortPosition: Double,
    ) = productCategoryManager.insertOrUpdateProductCategory(id, name, enName, chName, description, isActive, sortPosition)

    fun deleteProductCategory(id: Long) = productCategoryManager.deleteProductCategory(id)

    // Product operations
    fun insertOrUpdateProduct(
        id: Long,
        productNumber: String,
        name: String,
        enName: String?,
        chName: String?,
        description: String?,
        price: Decimal,
        productType: String,
        categoryId: Long,
        isActive: Long,
        imageUrl: String? = null,
        allowCustomPrice: Long,
    ) = productManager.insertOrUpdateProduct(
        id,
        productNumber,
        name,
        enName,
        chName,
        description,
        price,
        productType,
        categoryId,
        isActive,
        imageUrl = imageUrl,
        allowCustomPrice = allowCustomPrice,
    )

    fun deleteProduct(id: Long) = productManager.deleteProduct(id)

    // Product Extra operations
    fun insertOrUpdateProductExtra(
        id: Long,
        name: String,
        enName: String? = null,
        chName: String? = null,
        description: String?,
        price: Decimal,
        isActive: Long,
    ) = productExtraManager.insertOrUpdateProductExtra(id, name, enName, chName, description, price, isActive)

    fun deleteProductExtra(id: Long) = productExtraManager.deleteProductExtra(id)

    // Product-Extras Assignment operations
    fun insertOrReplaceProductExtrasAssignment(
        productId: Long,
        extrasId: Long,
        updatedAt: String = "",
    ) = productExtrasAssignmentManager.insertOrReplace(productId, extrasId, updatedAt)

    fun getProductExtrasAssignments(productId: Long) = productExtrasAssignmentManager.getByProductId(productId)

    fun deleteProductExtrasAssignment(
        productId: Long,
        extrasId: Long,
    ) = productExtrasAssignmentManager.deleteByProductAndExtra(productId, extrasId)

    fun clearAllProductExtrasAssignments() = productExtrasAssignmentManager.deleteAll()

    // Payment Type operations
    fun insertOrUpdatePaymentType(
        id: Long,
        name: String,
        isActive: Long,
        en: String?,
        ch: String?,
        createsReceivable: Long,
    ) = paymentTypeManager.insertOrUpdatePaymentType(id, name, en, ch, isActive, createsReceivable)

    fun deletePaymentType(id: Long) = paymentTypeManager.deletePaymentType(id)
}
