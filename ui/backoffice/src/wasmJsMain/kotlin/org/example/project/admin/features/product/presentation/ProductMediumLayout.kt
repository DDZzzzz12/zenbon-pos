package org.example.project.admin.features.product.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.example.project.admin.features.product.presentation.components.ProductCategoryChips
import org.example.project.admin.features.product.presentation.components.ProductHeader
import org.example.project.admin.features.product.presentation.components.ProductManageMenu
import org.example.project.admin.features.product.presentation.components.ProductSearchField
import org.example.project.admin.features.product.presentation.components.ProductTable
import org.example.project.ui.theme.LocalPosLayout
import org.example.project.ui.theme.PosSpacing

/** Header with "Produkt anlegen", search + Verwalten menu, one category chip row, then the table. */
@Composable
fun ProductMediumLayout(
    s: ProductState.Data,
    viewModel: ProductViewModel,
    canManage: Boolean,
    actions: ProductScreenActions,
    modifier: Modifier = Modifier,
) {
    val groupCounts = remember(s.allProductGroupLinks) { ProductMeta.groupCounts(s.allProductGroupLinks) }
    val gutter = LocalPosLayout.current.screenPadding
    Column(modifier.fillMaxSize()) {
        ProductHeader(
            summary = ProductSummary.of(s.allProducts),
            mode = ProductLayoutMode.Medium,
            canManage = canManage,
            managers = actions.managers,
            onAddProduct = actions.createProduct,
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = gutter),
            horizontalArrangement = Arrangement.spacedBy(PosSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProductSearchField(s.search, viewModel::onSearch, Modifier.weight(1f))
            if (canManage) ProductManageMenu(actions.managers)
        }
        ProductCategoryChips(
            categories = s.categories,
            selectedCategoryId = s.selectedCategoryId,
            totalCount = s.allProducts.size,
            countFor = s::productCountFor,
            canManage = canManage,
            onSelect = viewModel::selectCategory,
            onManageCategories = actions.managers.onCategories,
            modifier = Modifier.padding(vertical = PosSpacing.lg),
        )
        ProductTable(
            products = s.visibleProducts,
            sort = s.sort,
            canManage = canManage,
            mode = ProductLayoutMode.Medium,
            showCategory = false,
            groupCounts = groupCounts,
            onSort = viewModel::onSort,
            onEdit = actions.editProduct,
            onDelete = actions.deleteProduct,
            emptyState = { ProductEmptyState(s, viewModel) },
        )
    }
}
