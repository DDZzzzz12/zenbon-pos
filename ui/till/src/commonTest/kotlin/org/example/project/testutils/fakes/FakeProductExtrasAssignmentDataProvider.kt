package org.example.project.testutils.fakes

import org.example.project.core.data.models.ProductExtrasAssignment
import org.example.project.core.data.provider.ProductExtrasAssignmentDataProvider

class FakeProductExtrasAssignmentDataProvider : ProductExtrasAssignmentDataProvider {
    var getExtrasForProductResult: List<Int> = emptyList()
    var getAllAssignmentsResult: List<ProductExtrasAssignment> = emptyList()

    var getExtrasForProductThrows: Throwable? = null
    var getAllAssignmentsThrows: Throwable? = null

    var getExtrasForProductCallCount = 0
    var getAllAssignmentsCallCount = 0

    override suspend fun getExtrasForProduct(productId: Long): List<Int> {
        getExtrasForProductCallCount++
        getExtrasForProductThrows?.let { throw it }
        return getExtrasForProductResult
    }

    override suspend fun getAllAssignments(): List<ProductExtrasAssignment> {
        getAllAssignmentsCallCount++
        getAllAssignmentsThrows?.let { throw it }
        return getAllAssignmentsResult
    }
}
