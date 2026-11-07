package org.example.project.admin.features.product

import org.example.project.admin.features.product.presentation.ProductMeta
import org.example.project.core.domain.dto.product.ProductChoiceGroupDto
import org.example.project.core.domain.dto.product.ProductDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProductMetaTest {
    private fun product(
        id: Long,
        pfandTypeId: Long? = null,
    ) = ProductDto.ProductResponseDto(
        id = id,
        productNumber = "P-$id",
        name = "Produkt $id",
        description = null,
        price = "1.00",
        categoryId = 1,
        categoryName = "Cat",
        productType = "DRINK",
        isActive = true,
        pfandTypeId = pfandTypeId,
    )

    private fun link(
        productId: Long,
        groupId: Int,
    ) = ProductChoiceGroupDto.ResponseDto(productId = productId, groupId = groupId, sortPosition = 0, updatedAt = "")

    @Test
    fun groupCountsAreCountedPerProduct() {
        val counts = ProductMeta.groupCounts(listOf(link(1, 10), link(1, 11), link(2, 10)))
        assertEquals(mapOf(1L to 2, 2L to 1), counts)
    }

    @Test
    fun aProductWithNeitherPfandNorGroupsHasNoMeta() {
        assertTrue(ProductMeta.of(product(1), emptyMap()).isEmpty)
    }

    @Test
    fun aLinkedPfandTypeShowsPfand() {
        val meta = ProductMeta.of(product(1, pfandTypeId = 5), emptyMap())
        assertTrue(meta.hasPfand)
        assertFalse(meta.isEmpty)
    }

    @Test
    fun attachedGroupsAreTakenFromTheCounts() {
        assertEquals(ProductMeta(hasPfand = false, groupCount = 3), ProductMeta.of(product(7), mapOf(7L to 3)))
    }
}
