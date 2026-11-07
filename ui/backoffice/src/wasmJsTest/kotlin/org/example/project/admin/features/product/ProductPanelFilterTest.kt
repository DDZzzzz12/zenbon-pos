package org.example.project.admin.features.product

import org.example.project.admin.features.product.presentation.ProductPanelFilter
import org.example.project.admin.features.product.presentation.ProductSort
import org.example.project.admin.features.product.presentation.ProductSortField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProductPanelFilterTest {
    private val default = ProductPanelFilter(categoryId = null, sort = ProductPanelFilter.DEFAULT_SORT)

    @Test
    fun defaultSortIsNameAscendingAsTheStateStartsWith() {
        assertEquals(ProductSort.NAME_ASC, ProductPanelFilter.DEFAULT_SORT)
    }

    @Test
    fun nothingAppliedCountsZero() {
        assertEquals(0, default.activeCount())
    }

    @Test
    fun aCategoryCountsOne() {
        assertEquals(1, default.copy(categoryId = 7).activeCount())
    }

    @Test
    fun aNonDefaultSortCountsOne() {
        assertEquals(1, default.copy(sort = ProductSort.PRICE_DESC).activeCount())
    }

    @Test
    fun theDescendingNameSortIsNotTheDefault() {
        assertEquals(1, default.copy(sort = ProductSort.NAME_DESC).activeCount())
    }

    @Test
    fun categoryAndSortCountTwo() {
        assertEquals(2, ProductPanelFilter(categoryId = 3, sort = ProductSort.NUMBER_ASC).activeCount())
    }

    @Test
    fun clearedDropsCategoryAndSort() {
        assertEquals(default, ProductPanelFilter(categoryId = 3, sort = ProductSort.PRICE_DESC).cleared())
    }

    @Test
    fun applySendsOnlyWhatChanged() {
        val calls = mutableListOf<String>()
        val applied = ProductPanelFilter(categoryId = 3, sort = ProductSort.NAME_ASC)
        ProductPanelFilter(categoryId = 3, sort = ProductSort.PRICE_DESC)
            .applyTo(applied, onCategory = { calls += "category:$it" }, onSort = { calls += "sort:$it" })
        assertEquals(listOf("sort:PRICE_DESC"), calls)
    }

    @Test
    fun applySendsAClearedCategoryAsNull() {
        val calls = mutableListOf<String>()
        default.applyTo(
            ProductPanelFilter(categoryId = 3, sort = ProductSort.NAME_ASC),
            onCategory = { calls += "category:$it" },
            onSort = { calls += "sort:$it" },
        )
        assertEquals(listOf("category:null"), calls)
    }

    @Test
    fun applyOfAnUnchangedDraftSendsNothing() {
        val calls = mutableListOf<String>()
        default.applyTo(default, onCategory = { calls += "c" }, onSort = { calls += "s" })
        assertTrue(calls.isEmpty())
    }

    @Test
    fun sortFieldSplitsEverySortIntoFieldAndDirection() {
        ProductSort.entries.forEach { sort ->
            val field = ProductSortField.of(sort)
            assertEquals(sort, field.sort(ascending = ProductSortField.isAscending(sort)))
        }
    }

    @Test
    fun sortFieldKeepsTheDirectionWhenTheFieldChanges() {
        assertEquals(ProductSort.PRICE_DESC, ProductSortField.PRICE.sort(ascending = ProductSortField.isAscending(ProductSort.NAME_DESC)))
        assertEquals(ProductSortField.CATEGORY, ProductSortField.of(ProductSort.CATEGORY_DESC))
    }
}
