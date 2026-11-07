package org.example.project.testutils.fakes

import org.example.project.core.data.models.ProductExtra
import org.example.project.core.data.provider.ProductExtraDataProvider

class FakeProductExtraDataProvider : ProductExtraDataProvider {
    var getAllExtrasResult: List<ProductExtra> = emptyList()
    var getExtraByIdResult: ProductExtra? = null

    var getAllExtrasThrows: Throwable? = null
    var getExtraByIdThrows: Throwable? = null

    var getAllExtrasCallCount = 0
    var getExtraByIdCallCount = 0

    override suspend fun getAllExtras(includeInactive: Boolean): List<ProductExtra> {
        getAllExtrasCallCount++
        getAllExtrasThrows?.let { throw it }
        return getAllExtrasResult
    }

    override suspend fun getExtraById(id: Long): ProductExtra? {
        getExtraByIdCallCount++
        getExtraByIdThrows?.let { throw it }
        return getExtraByIdResult
    }
}
