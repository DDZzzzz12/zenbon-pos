package org.example.project.admin.features.product.presentation

import androidx.compose.runtime.Composable
import org.example.project.admin.core.presentation.components.AdminListEmptyState
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.keys.PosPatternKeys
import org.example.project.core.presentation.strings.str

/**
 * No rows to show: an empty catalog says so; otherwise nothing matches the filters, with a reset
 * of the panel filters when a category narrows the list (search is cleared in its own field).
 */
@Composable
fun ProductEmptyState(
    s: ProductState.Data,
    viewModel: ProductViewModel,
) {
    if (s.allProducts.isEmpty()) {
        AdminListEmptyState(message = str(AdminStringKeys.ProductsEmpty))
        return
    }
    val applied = ProductPanelFilter.of(s)
    val resettable = s.selectedCategoryId != null
    AdminListEmptyState(
        message = str(PosPatternKeys.filterNoResults),
        actionText = if (resettable) str(AdminStringKeys.CommonResetFilters) else null,
        onAction = if (resettable) ({ applied.cleared().applyTo(applied, viewModel::selectCategory, viewModel::onSort) }) else null,
    )
}
