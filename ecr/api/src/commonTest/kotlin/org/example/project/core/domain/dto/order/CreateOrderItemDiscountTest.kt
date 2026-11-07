package org.example.project.core.domain.dto.order

import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * `discount` is a MULTIPLIER (1.00 = full price, 0.85 = 15 % off), and it was previously unbounded:
 * `validate()` checked `productId`, `unitPrice`, `sideNotes` and `selections`, but never `discount`.
 *
 * `OrderServiceImpl` multiplies the line by it directly
 * (`((unitPrice + extrasDelta) * discount)`), so a request could book a line at zero or at a
 * NEGATIVE price — and the resulting order is TSE-signed and exported to DSFinV-K, making it a
 * fiscal-record problem rather than only a revenue one.
 *
 * These bounds are a pure bug fix: no multiplier outside 0..1 is ever legitimate. **Authorization is
 * a separate matter** — the server still trusts any caller who sends a legal discount, and requiring
 * `DISCOUNT_GRANT` / `PRICE_OVERRIDE` is deferred to Stage 3, where group definitions decide who
 * holds them and the narrowing is reviewable as a group diff.
 */
class CreateOrderItemDiscountTest {
    private fun request(discount: Double) =
        OrderItemDto.CreateOrderItemRequestDto(
            productId = 1L,
            unitPrice = "10.00",
            discount = discount,
        )

    @Test
    fun `rejects a negative discount multiplier`() {
        // Would produce a negative line price and a signed order that owes the customer money.
        assertFailsWith<IllegalArgumentException> { request(-1.0).validate() }
        Unit
    }

    @Test
    fun `rejects a discount multiplier above one`() {
        // A multiplier over 1 silently charges MORE than the product price.
        assertFailsWith<IllegalArgumentException> { request(1.5).validate() }
        Unit
    }

    @Test
    fun `rejects a non-finite discount multiplier`() {
        assertFailsWith<IllegalArgumentException> { request(Double.NaN).validate() }
        assertFailsWith<IllegalArgumentException> { request(Double.POSITIVE_INFINITY).validate() }
        Unit
    }

    @Test
    fun `accepts the legitimate range`() {
        // 0.0 is a full comp — legitimate, and distinct from a negative multiplier.
        request(0.0).validate()
        request(0.85).validate()
        request(1.0).validate()
    }
}
