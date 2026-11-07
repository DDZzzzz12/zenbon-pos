package org.example.project.admin

import org.example.project.admin.features.product.presentation.ProductSort
import org.example.project.admin.features.product.presentation.ProductState
import org.example.project.core.domain.dto.product.ProductDto
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Drives the real `ProductState.Data.visibleProducts` sort rather than a local copy of it, so
 * reverting the production selector actually breaks these tests.
 *
 * Honest scope note: at realistic money magnitudes a `Double` sort and a `Decimal` sort agree —
 * `toDoubleOrNull()` orders "9.00" before "100.00" perfectly well. Only
 * [distinguishesValuesADoubleWouldBlur]'s second assertion separates the two implementations. The
 * other cases are characterization: they pin the behaviour the screen relies on, they do not
 * justify the change on their own. The change's real justification is exactness and staying on
 * the same `:platform-money` parse as every other money path, not a visibly different ordering.
 */
class ProductPriceSortTest {
    private fun product(
        id: Long,
        price: String,
    ) = ProductDto.ProductResponseDto(
        id = id,
        productNumber = "P$id",
        name = "Product $id",
        description = null,
        price = price,
        categoryId = 1,
        categoryName = "cat",
        productType = "FOOD",
        isActive = true,
    )

    private fun sortedPrices(
        prices: List<String>,
        sort: ProductSort,
    ): List<String> =
        ProductState
            .Data(
                categories = emptyList(),
                allProducts = prices.mapIndexed { i, p -> product(id = i.toLong(), price = p) },
                sort = sort,
            ).visibleProducts
            .map { it.price }

    private fun sortAscending(prices: List<String>): List<String> = sortedPrices(prices, ProductSort.PRICE_ASC)

    @Test
    fun sortsNumericallyNotLexicographically() {
        // "9.00" > "10.00" as text; as decimals it is the other way round.
        assertEquals(listOf("9.00", "10.00", "100.00"), sortAscending(listOf("100.00", "9.00", "10.00")))
        // PRICE_DESC uses the same selector and would otherwise go untested.
        assertEquals(
            listOf("100.00", "10.00", "9.00"),
            sortedPrices(listOf("100.00", "9.00", "10.00"), ProductSort.PRICE_DESC),
        )
    }

    @Test
    fun distinguishesValuesADoubleWouldBlur() {
        // Characterization: a Double orders these correctly too.
        assertEquals(listOf("0.10", "0.20", "0.30"), sortAscending(listOf("0.30", "0.10", "0.20")))

        // The one assertion that actually separates Decimal from Double. IEEE-754 carries about
        // 15.95 significant decimal digits, and the gap between representable doubles at 10^15 is
        // 0.125 — so both of these collapse onto the same double and compare equal. sortedBy is
        // stable, so the old selector returned them in input order, unsorted. Decimal compares
        // them exactly. The magnitude is synthetic: no product costs 10^15, and that is the point
        // — it is the only place the two orderings differ at all.
        assertEquals(
            listOf("1000000000000000.01", "1000000000000000.02"),
            sortAscending(listOf("1000000000000000.02", "1000000000000000.01")),
        )
    }

    @Test
    fun handlesNegativesAndUnparseableWithoutCrashing() {
        assertEquals(listOf("-5.00", "0.00", "5.00"), sortAscending(listOf("5.00", "-5.00", "0.00")))
        // An unparseable price sorts as zero rather than throwing.
        assertEquals(listOf("bad", "1.00"), sortAscending(listOf("1.00", "bad")))
    }
}
