package org.example.project.admin.features.product.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.components.adminTouchSize
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.product.presentation.ProductPanelFilter
import org.example.project.admin.features.product.presentation.ProductSortField
import org.example.project.core.domain.dto.product.ProductCategoryDto
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.enumStrings.categoryDisplayName
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.filter.PosFilterDraft
import org.example.project.ui.components.filter.PosFilterPicker
import org.example.project.ui.components.filter.PosFilterSortField

/**
 * The Produkte filter panel body: the category (every category, with its product count as in the
 * category list) and the sort column with its direction. Both edit [draft] only.
 */
@Composable
fun ProductFilterPanelFields(
    draft: PosFilterDraft<ProductPanelFilter>,
    categories: List<ProductCategoryDto.ProductCategoryResponseDto>,
    countFor: (Int) -> Int,
) {
    CategoryField(draft, categories, countFor)
    SortField(draft)
}

@Composable
private fun CategoryField(
    draft: PosFilterDraft<ProductPanelFilter>,
    categories: List<ProductCategoryDto.ProductCategoryResponseDto>,
    countFor: (Int) -> Int,
) {
    val language = LocalAppLanguage.current
    val selectedId = draft.value.categoryId
    val ordered = categories.sortedBy { it.sortPosition }
    val label: (ProductCategoryDto.ProductCategoryResponseDto) -> String = { "${categoryDisplayName(it.name, language)} (${countFor(it.id)})" }
    val value = ordered.firstOrNull { it.id == selectedId }?.let(label) ?: str(AdminStringKeys.ProductsAll)
    PosFilterPicker(label = str(AdminStringKeys.ProductColCategory), value = value) { close ->
        PosDropdownItem(
            text = str(AdminStringKeys.ProductsAll),
            selected = selectedId == null,
            minHeight = adminTouchSize(40.dp),
            onClick = {
                close()
                draft.value = draft.value.copy(categoryId = null)
            },
        )
        ordered.forEach { category ->
            PosDropdownItem(
                text = label(category),
                selected = category.id == selectedId,
                minHeight = adminTouchSize(40.dp),
                onClick = {
                    close()
                    draft.value = draft.value.copy(categoryId = category.id)
                },
            )
        }
    }
}

@Composable
private fun SortField(draft: PosFilterDraft<ProductPanelFilter>) {
    val sort = draft.value.sort
    PosFilterSortField(
        keys = ProductSortField.entries,
        selectedKey = ProductSortField.of(sort),
        ascending = ProductSortField.isAscending(sort),
        keyLabel = { sortFieldLabel(it) },
        // Changing the key keeps the direction.
        onChange = { field, ascending -> draft.value = draft.value.copy(sort = field.sort(ascending)) },
    )
}

/** The column's own header label, as in the table and the former sort chips. */
@Composable
internal fun sortFieldLabel(field: ProductSortField): String =
    when (field) {
        ProductSortField.NAME -> str(AdminStringKeys.ProductColName)
        ProductSortField.NUMBER -> str(AdminStringKeys.ProductColNumber)
        ProductSortField.CATEGORY -> str(AdminStringKeys.ProductColCategory)
        ProductSortField.PRICE -> str(AdminStringKeys.ProductColPrice)
    }
