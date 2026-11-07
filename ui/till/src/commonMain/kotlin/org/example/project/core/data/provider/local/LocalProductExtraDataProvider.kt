/**
 * SQLite-backed implementation of [ProductExtraDataProvider].
 *
 * Stage C Batch 9: flipped to throws-style.
 *
 * @since 1.0.0
 */
package org.example.project.core.data.provider.local

import org.example.project.core.data.database.ProductExtraDataManager
import org.example.project.core.data.models.ProductExtra
import org.example.project.core.data.provider.ProductExtraDataProvider

class LocalProductExtraDataProvider(
    private val productExtraDataManager: ProductExtraDataManager,
) : ProductExtraDataProvider {
    override suspend fun getAllExtras(includeInactive: Boolean): List<ProductExtra> =
        productExtraDataManager
            .getAllProductExtras()
            .map { ProductExtra.fromDatabase(it) }
            .let { if (includeInactive) it else it.filter { e -> e.isActive } }

    override suspend fun getExtraById(id: Long): ProductExtra? = productExtraDataManager.getProductExtraById(id)?.let { ProductExtra.fromDatabase(it) }
}
