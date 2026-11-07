package org.example.project.core.data.models

import org.example.project.core.domain.dto.product.ProductDto
import org.example.project.money.DecimalParseException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Pins the precision `Product.price` gained by becoming a [Decimal].
 *
 * The old field was a `Long` of minor units, parsed String -> Double -> Long. That route pushed
 * every price through a float AND pinned the scale at 2, discarding the sub-cent precision the
 * server holds in DECIMAL(19,4). These assertions pin that the [Decimal] path now preserves it.
 */
class ProductPriceTest {
    private fun dtoWithPrice(price: String) =
        ProductDto.ProductResponseDto(
            id = 1,
            productNumber = "P001",
            name = "Test Product",
            description = null,
            price = price,
            categoryId = 1,
            categoryName = "Test Category",
            productType = "FOOD",
            isActive = true,
        )

    @Test
    fun `a scale-4 server price survives the DTO to model path intact`() {
        val product = Product.fromDto(dtoWithPrice("16.9000"))

        assertEquals("16.9000", product.price.toPlainString())
        assertEquals(4, product.price.scale)
    }

    @Test
    fun `a third decimal digit survives instead of being truncated`() {
        val product = Product.fromDto(dtoWithPrice("16.895"))

        assertEquals("16.895", product.price.toPlainString())
        assertEquals(3, product.price.scale)
    }

    @Test
    fun `an exact two-decimal price keeps its trailing zero rather than being renormalised`() {
        val product = Product.fromDto(dtoWithPrice("16.90"))

        assertEquals("16.90", product.price.toPlainString())
        assertEquals(2, product.price.scale)
    }

    @Test
    fun `a malformed price raises a typed parse failure instead of a null dereference`() {
        // The old `!!` on the nullable parse turned bad server data into a bare NullPointerException.
        assertFailsWith<DecimalParseException> {
            Product.fromDto(dtoWithPrice("not-a-price"))
        }
        // Trailing Unit: an expression-body test ending in assertFailsWith is silently skipped
        // by this project's JUnit 5 setup.
        Unit
    }
}
