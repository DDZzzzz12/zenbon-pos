package org.example.project.admin.features.product.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.features.product.presentation.components.CategorySidebar
import org.example.project.admin.features.product.presentation.components.ProductHeader
import org.example.project.admin.features.product.presentation.components.ProductSearchLine
import org.example.project.admin.features.product.presentation.components.ProductTable

/** Header, then the 280dp category column beside the search line and the sortable table. */
@Composable
fun ProductLargeLayout(
    s: ProductState.Data,
    viewModel: ProductViewModel,
    canManage: Boolean,
    actions: ProductScreenActions,
    modifier: Modifier = Modifier,
) {
    val groupCounts = remember(s.allProductGroupLinks) { ProductMeta.groupCounts(s.allProductGroupLinks) }
    Column(modifier.fillMaxSize()) {
        ProductHeader(
            summary = ProductSummary.of(s.allProducts),
            mode = ProductLayoutMode.Large,
            canManage = canManage,
            managers = actions.managers,
            onAddProduct = actions.createProduct,
        )
        Row(Modifier.weight(1f)) {
            CategorySidebar(
                categories = s.categories,
                selectedCategoryId = s.selectedCategoryId,
                totalCount = s.allProducts.size,
                countFor = s::productCountFor,
                canManage = canManage,
                onSelect = viewModel::selectCategory,
                onAddCategory = actions.createCategory,
                onEditCategory = actions.editCategory,
                onDeleteCategory = actions.deleteCategory,
                onMoveUp = { viewModel.moveCategory(it.id, up = true) },
                onMoveDown = { viewModel.moveCategory(it.id, up = false) },
                modifier = Modifier.width(280.dp),
            )
            VerticalDivider()
            Column(Modifier.weight(1f)) {
                ProductSearchLine(s, viewModel::onSearch)
                ProductTable(
                    products = s.visibleProducts,
                    sort = s.sort,
                    canManage = canManage,
                    mode = ProductLayoutMode.Large,
                    showCategory = s.selectedCategoryId == null,
                    groupCounts = groupCounts,
                    onSort = viewModel::onSort,
                    onEdit = actions.editProduct,
                    onDelete = actions.deleteProduct,
                    emptyState = { ProductEmptyState(s, viewModel) },
                )
            }
        }
    }
}
