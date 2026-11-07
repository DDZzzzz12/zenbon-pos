package org.example.project.core.domain.dto.product

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * ProductExtras price validation must be exact decimal, not Double.
 *
 * The current implementation routes `price` through `String.toDouble()`, which silently
 * accepts scientific notation ("1e5") — a format the exact `Decimal` kernel rejects. The
 * "1e5" case is the red wedge: it passes today and must fail once validate() uses
 * `Decimal.parse`.
 */
class ProductExtrasDtoValidateTest {
    private val acceptedPrices = listOf("0.00", "16.89", "16.895", "0.004", "0")

    private fun createDto(price: String) =
        ProductExtrasDto.CreateProductExtrasRequestDto(
            name = "Extra",
            price = price,
        )

    private fun updateDto(price: String) =
        ProductExtrasDto.UpdateProductExtrasRequestDto(
            name = "Extra",
            price = price,
            isActive = true,
        )

    @Test
    fun `create accepts exact decimal prices`() {
        // Direct invocation: kotlin-test 2.4.0 has no assertDoesNotThrow; an unexpected
        // exception fails the test, which is exactly what assertDoesNotThrow guarantees.
        acceptedPrices.forEach { price ->
            createDto(price).validate()
        }
    }

    @Test
    fun `update accepts exact decimal prices`() {
        acceptedPrices.forEach { price ->
            updateDto(price).validate()
        }
    }

    @Test
    fun `create rejects negative price`() {
        val e =
            assertFailsWith<IllegalArgumentException> {
                createDto("-0.01").validate()
            }
        assertEquals("Price cannot be negative", e.message)
    }

    @Test
    fun `update rejects negative price`() {
        val e =
            assertFailsWith<IllegalArgumentException> {
                updateDto("-0.01").validate()
            }
        assertEquals("Price cannot be negative", e.message)
    }

    @Test
    fun `create rejects malformed price`() {
        val e =
            assertFailsWith<IllegalArgumentException> {
                createDto("abc").validate()
            }
        assertEquals("Price must be a valid decimal number", e.message)
    }

    @Test
    fun `update rejects malformed price`() {
        val e =
            assertFailsWith<IllegalArgumentException> {
                updateDto("abc").validate()
            }
        assertEquals("Price must be a valid decimal number", e.message)
    }

    @Test
    fun `create rejects scientific notation wedge`() {
        val e =
            assertFailsWith<IllegalArgumentException> {
                createDto("1e5").validate()
            }
        assertEquals("Price must be a valid decimal number", e.message)
    }

    @Test
    fun `update rejects scientific notation wedge`() {
        val e =
            assertFailsWith<IllegalArgumentException> {
                updateDto("1e5").validate()
            }
        assertEquals("Price must be a valid decimal number", e.message)
    }
}
