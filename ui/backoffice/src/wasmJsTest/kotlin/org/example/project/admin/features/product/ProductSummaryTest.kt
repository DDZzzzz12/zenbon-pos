package org.example.project.admin.features.product

import org.example.project.admin.features.product.presentation.ProductSummary
import org.example.project.core.domain.dto.product.ProductDto
import kotlin.test.Test
import kotlin.test.assertEquals

class ProductSummaryTest {
    private fun product(
        id: Long,
        active: Boolean,
    ) = ProductDto.ProductResponseDto(
        id = id,
        productNumber = "P-$id",
        name = "Produkt $id",
        description = null,
        price = "1.00",
        categoryId = 1,
        categoryName = "Cat",
        productType = "FOOD",
        isActive = active,
    )

    @Test
    fun anEmptyCatalogIsAllZero() {
        assertEquals(ProductSummary(0, 0, 0), ProductSummary.of(emptyList()))
    }

    @Test
    fun countsActiveAndInactiveSeparately() {
        val summary = ProductSummary.of(listOf(product(1, true), product(2, true), product(3, false)))
        assertEquals(ProductSummary(total = 3, active = 2, inactive = 1), summary)
    }
}
