/**
 * API client-backed implementation of [ProductExtraDataProvider].
 *
 * Stage C Batch 9: flipped to throws-style.
 *
 * @since 1.0.0
 */
package org.example.project.core.data.provider.remote

import org.example.project.core.data.models.ProductExtra
import org.example.project.core.data.provider.ProductExtraDataProvider
import org.example.project.core.data.remote.ProductExtrasApiClient

class ApiClientProductExtraDataProvider(
    private val productExtrasApiClient: ProductExtrasApiClient,
) : ProductExtraDataProvider {
    override suspend fun getAllExtras(includeInactive: Boolean): List<ProductExtra> =
        productExtrasApiClient
            .getAllProductExtras(
                isActive = if (includeInactive) null else true,
            ).map { ProductExtra.fromDto(it) }

    override suspend fun getExtraById(id: Long): ProductExtra? = ProductExtra.fromDto(productExtrasApiClient.getProductExtraById(id))
}
