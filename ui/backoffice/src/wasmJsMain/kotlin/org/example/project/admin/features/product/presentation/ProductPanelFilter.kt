package org.example.project.admin.features.product.presentation

/**
 * What the Produkte filter panel edits (the card layout below the sidebar+table width), held as a
 * draft until its apply button: the category and the sort. Search stays in the bar at every width,
 * so it is not part of the panel and never cleared by its reset or by clear-all.
 */
data class ProductPanelFilter(
    val categoryId: Int?,
    val sort: ProductSort,
) {
    /** The Filter button's badge: a chosen category and a sort other than the default, one each. */
    fun activeCount(): Int = (if (categoryId != null) 1 else 0) + (if (sort != DEFAULT_SORT) 1 else 0)

    /** The panel's reset and the bar's clear-all: every category, default sort. */
    fun cleared(): ProductPanelFilter = ProductPanelFilter(categoryId = null, sort = DEFAULT_SORT)

    /** Hands this draft to the view model, sending only what differs from [applied]. */
    fun applyTo(
        applied: ProductPanelFilter,
        onCategory: (Int?) -> Unit,
        onSort: (ProductSort) -> Unit,
    ) {
        if (categoryId != applied.categoryId) onCategory(categoryId)
        if (sort != applied.sort) onSort(sort)
    }

    companion object {
        /** The sort [ProductState.Data] starts with; any other counts as an applied filter. */
        val DEFAULT_SORT = ProductSort.NAME_ASC

        val NONE = ProductPanelFilter(categoryId = null, sort = DEFAULT_SORT)

        fun of(state: ProductState.Data): ProductPanelFilter = ProductPanelFilter(state.selectedCategoryId, state.sort)
    }
}
