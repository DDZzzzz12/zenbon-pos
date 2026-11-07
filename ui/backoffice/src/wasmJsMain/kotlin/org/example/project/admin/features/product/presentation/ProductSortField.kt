package org.example.project.admin.features.product.presentation

/**
 * The column a [ProductSort] orders by, so the filter panel can offer the column and the direction
 * as two controls (Sortierung · Aufsteigend/Absteigend) over the one eight-value [ProductSort].
 */
enum class ProductSortField(
    private val ascending: ProductSort,
    private val descending: ProductSort,
) {
    NAME(ProductSort.NAME_ASC, ProductSort.NAME_DESC),
    NUMBER(ProductSort.NUMBER_ASC, ProductSort.NUMBER_DESC),
    CATEGORY(ProductSort.CATEGORY_ASC, ProductSort.CATEGORY_DESC),
    PRICE(ProductSort.PRICE_ASC, ProductSort.PRICE_DESC),
    ;

    /** This column's sort in the given direction. */
    fun sort(ascending: Boolean): ProductSort = if (ascending) this.ascending else descending

    companion object {
        fun of(sort: ProductSort): ProductSortField = entries.first { it.ascending == sort || it.descending == sort }

        fun isAscending(sort: ProductSort): Boolean = entries.any { it.ascending == sort }
    }
}
