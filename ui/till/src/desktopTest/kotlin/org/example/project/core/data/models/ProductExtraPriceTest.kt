package org.example.project.core.data.models

import org.example.project.core.domain.dto.product.ProductExtrasDto
import org.example.project.money.DecimalParseException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pins the precision `ProductExtra.price` gained by becoming a [Decimal].
 *
 * The old field was a `Long` of minor units that pinned the scale at 2 and discarded the sub-cent
 * precision the server holds in DECIMAL(19,4). These assertions pin that the [Decimal] path now
 * preserves it.
 */
class ProductExtraPriceTest {
    private fun dtoWithPrice(price: String) =
        ProductExtrasDto.ProductExtrasResponseDto(
            id = 1,
            name = "Test Extra",
            en = null,
            ch = null,
            description = null,
            price = price,
            isActive = true,
        )

    @Test
    fun `a scale-4 server price survives the DTO to model path intact`() {
        val extra = ProductExtra.fromDto(dtoWithPrice("16.8950"))

        assertEquals("16.8950", extra.price.toPlainString())
        assertEquals(4, extra.price.scale)
    }

    @Test
    fun `sub-cent precision is preserved, not truncated to zero`() {
        val extra = ProductExtra.fromDto(dtoWithPrice("0.004"))

        assertEquals("0.004", extra.price.toPlainString())
    }

    @Test
    fun `a malformed price raises a typed parse failure instead of a null dereference`() {
        // The old `!!` on the nullable parse turned bad server data into a bare NullPointerException.
        assertFailsWith<DecimalParseException> {
            ProductExtra.fromDto(dtoWithPrice("not-a-price"))
        }
        // Trailing Unit: an expression-body test ending in assertFailsWith is silently skipped
        // by this project's JUnit 5 setup.
        Unit
    }

    @Test
    fun `a negative price parses as negative Decimal`() {
        val extra = ProductExtra.fromDto(dtoWithPrice("-0.01"))

        assertTrue(extra.price.isNegative)
        assertEquals("-0.01", extra.price.toPlainString())
    }
}
