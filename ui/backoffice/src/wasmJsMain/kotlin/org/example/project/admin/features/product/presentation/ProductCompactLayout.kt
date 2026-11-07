package org.example.project.admin.features.product.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.example.project.admin.core.presentation.components.LocalAdminTopBarActions
import org.example.project.admin.core.presentation.components.RegisterTopBarAction
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.product.presentation.components.ProductManageMenu
import org.example.project.admin.features.product.presentation.components.ProductRowList
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.filter.PosFilterPanelState
import org.example.project.ui.theme.LocalPosLayout
import org.example.project.ui.theme.PosSpacing

/**
 * Full-width "Produkt anlegen" (the Verwalten menu is the top bar's ⋮), the search + Filter bar and one
 * tappable row per product. Category and sort live in the filter panel; Löschen is in the edit form.
 */
@Composable
fun ProductCompactLayout(
    s: ProductState.Data,
    viewModel: ProductViewModel,
    canManage: Boolean,
    actions: ProductScreenActions,
    filterPanel: PosFilterPanelState<ProductPanelFilter>,
    modifier: Modifier = Modifier,
) {
    val screenPadding = LocalPosLayout.current.screenPadding
    Column(modifier.fillMaxSize()) {
        // The manager menu is the phone top bar's ⋮ overflow (as in the design); inline beside the add
        // button only where the shell provides no bar.
        val menuInTopBar = LocalAdminTopBarActions.current != null
        if (canManage && menuInTopBar) {
            RegisterTopBarAction { ProductManageMenu(actions.managers, iconOnly = true) }
        }
        // An action line, not a filter row: only for those who may change the catalog.
        if (canManage) {
            Row(
                Modifier.fillMaxWidth().padding(start = screenPadding, end = screenPadding, top = PosSpacing.lg),
                horizontalArrangement = Arrangement.spacedBy(PosSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PosButton(
                    text = str(AdminStringKeys.ProductsAddProduct),
                    icon = Icons.Default.Add,
                    onClick = actions.createProduct,
                    modifier = Modifier.weight(1f),
                )
                if (!menuInTopBar) ProductManageMenu(actions.managers, iconOnly = true)
            }
        }
        ProductFilters(s, viewModel, filterPanel)
        ProductRowList(
            products = s.visibleProducts,
            canManage = canManage,
            onEdit = actions.editProduct,
            modifier = Modifier.weight(1f),
            emptyState = { ProductEmptyState(s, viewModel) },
        )
    }
}
