package org.example.project.admin.features.product.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.auth.canManageProducts
import org.example.project.admin.core.presentation.components.DeleteConfirmDialog
import org.example.project.admin.core.presentation.components.adminTouchSize
import org.example.project.admin.core.presentation.navigation.FilterPanelKeys
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.choicegroup.presentation.components.ChoiceGroupsManagerDialog
import org.example.project.admin.features.extras.presentation.components.ExtrasManagerDialog
import org.example.project.admin.features.product.presentation.components.CategoriesManagerDialog
import org.example.project.admin.features.product.presentation.components.CategoryEditDrawer
import org.example.project.admin.features.product.presentation.components.ProductEditDrawer
import org.example.project.admin.features.product.presentation.components.ProductFilterPanelFields
import org.example.project.admin.features.product.presentation.components.ProductManagerActions
import org.example.project.admin.features.product.presentation.components.sortFieldLabel
import org.example.project.admin.features.promotion.presentation.components.PromotionsManagerDialog
import org.example.project.core.auth.TokenStore
import org.example.project.core.domain.dto.product.ProductCategoryDto
import org.example.project.core.domain.dto.product.ProductDto
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.PosPatternKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.enumStrings.categoryDisplayName
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.filter.PosFilterBar
import org.example.project.ui.components.filter.PosFilterChipItem
import org.example.project.ui.components.filter.PosFilterPanel
import org.example.project.ui.components.filter.PosFilterPanelState
import org.example.project.ui.components.filter.PosFilterSearch
import org.example.project.ui.components.filter.filterResultLine
import org.example.project.ui.components.filter.posSortDirectionLabel
import org.example.project.ui.components.filter.rememberPosFilterPanelState
import org.example.project.ui.guard.LocalLeaveGuard
import org.example.project.ui.guard.rememberGuardedDismiss
import org.example.project.ui.theme.LocalPosLayout
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ProductScreen(
    viewModel: ProductViewModel = koinViewModel(),
    tokenStore: TokenStore = koinInject(),
) {
    LaunchedEffect(Unit) { viewModel.refresh() }
    val canManage = tokenStore.canManageProducts
    var pendingDelete by remember { mutableStateOf<ProductDto.ProductResponseDto?>(null) }
    var pendingDeleteCategory by remember { mutableStateOf<ProductCategoryDto.ProductCategoryResponseDto?>(null) }
    var showExtras by remember { mutableStateOf(false) }
    var showChoiceGroups by remember { mutableStateOf(false) }
    var showPromotions by remember { mutableStateOf(false) }
    var categoriesOpen by remember { mutableStateOf(false) }
    // Above every layout branch (Compact rows vs Medium/Large table), so a resize keeps an
    // open filter panel and its draft.
    val filterPanel =
        rememberPosFilterPanelState(
            (viewModel.state as? ProductState.Data)?.let(ProductPanelFilter::of) ?: ProductPanelFilter.NONE,
            FilterPanelKeys.PRODUCTS,
        )

    // Both drawers register at screen level so a leave (sidebar, Back, reload) asks first, and their scrim/x/Cancel close through the guard.
    val closeProduct = rememberGuardedDismiss(dirty = viewModel.isEditorDirty, onClose = viewModel::closeEditor)
    val closeCategory = rememberGuardedDismiss(dirty = viewModel.isCategoryEditorDirty, onClose = viewModel::closeCategoryEditor)
    val guard = LocalLeaveGuard.current
    val actions =
        ProductScreenActions(
            createProduct = { guard.requestLeave { viewModel.openCreateProduct() } },
            // Re-clicking the open product is a no-op: re-opening would reseed the editor and drop unsaved edits.
            editProduct = { p -> if (viewModel.editor?.id != p.id) guard.requestLeave { viewModel.openEditProduct(p) } },
            deleteProduct = { pendingDelete = it },
            // The category list dialog hands over to the drawer, so it closes first.
            createCategory = {
                categoriesOpen = false
                guard.requestLeave { viewModel.openCreateCategory() }
            },
            editCategory = { c ->
                categoriesOpen = false
                if (viewModel.categoryEditor?.id != c.id) guard.requestLeave { viewModel.openEditCategory(c) }
            },
            deleteCategory = { pendingDeleteCategory = it },
            managers =
                ProductManagerActions(
                    onExtras = { showExtras = true },
                    onChoiceGroups = { showChoiceGroups = true },
                    onPromotions = { showPromotions = true },
                    onCategories = { categoriesOpen = true },
                ),
        )

    val errorState = viewModel.state as? ProductState.Error
    PosErrorWatcher(
        error = errorState?.error,
        title = str(AdminStringKeys.ProductsTitle),
        onClear = viewModel::clearError,
        retryAction = viewModel::refresh,
    )

    PosErrorWatcher(
        error = viewModel.bulkError,
        title = str(AdminStringKeys.ProductsTitle),
        onClear = viewModel::clearBulkError,
    )

    Box(Modifier.fillMaxSize()) {
        when (val s = viewModel.state) {
            is ProductState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            is ProductState.Error -> Box(Modifier.fillMaxSize()) // surfaced by the global overlay
            is ProductState.Data -> {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    // On a touch-sized window (short but wide) the two row actions grow 36 -> 48dp, so the Large table needs that extra too.
                    val touchExtra = (adminTouchSize(36.dp) - 36.dp) * 2
                    when (ProductLayoutMode.of(maxWidth, maxHeight, touchExtra)) {
                        ProductLayoutMode.Large -> ProductLargeLayout(s, viewModel, canManage, actions)
                        ProductLayoutMode.Medium -> ProductMediumLayout(s, viewModel, canManage, actions)
                        ProductLayoutMode.Compact -> ProductCompactLayout(s, viewModel, canManage, actions, filterPanel)
                    }
                }

                // Outside the layout branches above, so a resize across the Compact boundary keeps
                // an open panel and its draft.
                ProductFilterPanel(s, viewModel, filterPanel)

                // Right-side drawers (product takes precedence if both somehow set)
                viewModel.editor?.let { e ->
                    ProductEditDrawer(
                        editor = e,
                        dirty = viewModel.isEditorDirty,
                        categories = s.categories,
                        groups = s.allGroups,
                        pfandTypes = s.pfandTypes,
                        onField = viewModel::onEditorField,
                        onSave = viewModel::saveEditor,
                        onCancel = closeProduct,
                        onDelete = e.id?.takeIf { canManage }?.let { id -> { pendingDelete = s.allProducts.firstOrNull { it.id == id } } },
                        onAttachGroup = viewModel::attachGroup,
                        onDetachGroup = viewModel::detachGroup,
                        onMoveGroup = viewModel::moveAttachedGroup,
                    )
                }
                viewModel.categoryEditor?.let { e ->
                    val catId = e.id
                    val categoryProductIds =
                        if (catId != null) {
                            s.allProducts
                                .filter { it.categoryId == catId }
                                .map { it.id }
                                .toSet()
                        } else {
                            emptySet()
                        }
                    val total = categoryProductIds.size
                    val coverage =
                        s.allProductGroupLinks
                            .filter { it.productId in categoryProductIds }
                            .groupingBy { it.groupId }
                            .eachCount()
                    CategoryEditDrawer(
                        editor = e,
                        dirty = viewModel.isCategoryEditorDirty,
                        onField = viewModel::onCategoryEditorField,
                        onSave = viewModel::saveCategoryEditor,
                        onCancel = closeCategory,
                        onDelete =
                            catId
                                ?.takeIf { canManage }
                                ?.let { id -> { pendingDeleteCategory = s.categories.firstOrNull { it.id == id } } },
                        groups = s.allGroups,
                        groupCoverage = coverage,
                        totalProductsInCategory = total,
                        onBulkAssignGroup = viewModel::bulkAttachGroupToCategory,
                        onBulkRemoveGroup = viewModel::bulkRemoveGroupFromCategory,
                    )
                }

                if (categoriesOpen && canManage) {
                    CategoriesManagerDialog(
                        s = s,
                        actions = actions,
                        onMoveUp = { viewModel.moveCategory(it.id, up = true) },
                        onMoveDown = { viewModel.moveCategory(it.id, up = false) },
                        onDismiss = { categoriesOpen = false },
                    )
                }
            }
        }

        pendingDelete?.let { p ->
            DeleteConfirmDialog(
                entityType = str(AdminStringKeys.DeleteEntityProduct),
                entityName = p.name,
                onConfirm = {
                    viewModel.deleteProduct(p.id)
                    // Deleting from the open form: the product is gone, so close its editor without a prompt.
                    if (viewModel.editor?.id == p.id) viewModel.closeEditor()
                    pendingDelete = null
                },
                onDismiss = { pendingDelete = null },
            )
        }

        pendingDeleteCategory?.let { c ->
            val blockingProducts = (viewModel.state as? ProductState.Data)?.activeProductCountFor(c.id) ?: 0
            DeleteConfirmDialog(
                entityType = str(AdminStringKeys.DeleteEntityCategory),
                entityName = c.name,
                blockedReason = if (blockingProducts > 0) str(AdminStringKeys.CategoryDeleteBlocked, blockingProducts) else null,
                onConfirm = {
                    viewModel.deleteCategory(c.id)
                    if (viewModel.categoryEditor?.id == c.id) viewModel.closeCategoryEditor()
                    pendingDeleteCategory = null
                },
                onDismiss = { pendingDeleteCategory = null },
            )
        }

        if (showExtras) {
            ExtrasManagerDialog(onDismiss = { showExtras = false })
        }

        if (showChoiceGroups) {
            ChoiceGroupsManagerDialog(onDismiss = { showChoiceGroups = false })
        }

        if (showPromotions) {
            PromotionsManagerDialog(onDismiss = { showPromotions = false })
        }
    }
}

/**
 * The Compact layout's Zenbon filter bar: search applies as typed; category and sort are panel
 * filters (the badge counts them), shown as removable chips on wider windows only.
 */
@Composable
internal fun ProductFilters(
    s: ProductState.Data,
    viewModel: ProductViewModel,
    filterPanel: PosFilterPanelState<ProductPanelFilter>,
) {
    val applied = ProductPanelFilter.of(s)
    PosFilterBar(
        search = PosFilterSearch(s.search, viewModel::onSearch, str(AdminStringKeys.ProductsSearch)),
        // The whole catalog is loaded at once, so the count is exact (never "N+").
        resultText = filterResultLine(s.visibleProducts.size, AdminStringKeys.ProductsResultCountOne, AdminStringKeys.ProductsResultCount),
        appliedFilters = productAppliedChips(s, viewModel),
        activeFilterCount = applied.activeCount(),
        onOpenPanel = filterPanel::open,
        labelledPhoneFilterButton = true,
        // Category and sort; never the search text, which sits in the bar (PosFilterBar contract).
        onClearAll = { applied.cleared().applyTo(applied, viewModel::selectCategory, viewModel::onSort) },
    )
}

/** One removable chip per applied panel filter, described from the APPLIED state. */
@Composable
private fun productAppliedChips(
    s: ProductState.Data,
    viewModel: ProductViewModel,
): List<PosFilterChipItem> {
    val chips = mutableListOf<PosFilterChipItem>()
    s.selectedCategoryId?.let { id ->
        val name = s.categories.firstOrNull { it.id == id }?.name
        val label =
            if (name != null) {
                str(AdminStringKeys.ProductsCategoryChip, categoryDisplayName(name, LocalAppLanguage.current))
            } else {
                str(AdminStringKeys.ProductColCategory)
            }
        chips += PosFilterChipItem(label = label, onClick = { viewModel.selectCategory(null) })
    }
    if (s.sort != ProductPanelFilter.DEFAULT_SORT) {
        val label =
            str(
                PosPatternKeys.sortChip,
                sortFieldLabel(ProductSortField.of(s.sort)),
                posSortDirectionLabel(ProductSortField.isAscending(s.sort)),
            )
        chips += PosFilterChipItem(label = label, onClick = { viewModel.onSort(ProductPanelFilter.DEFAULT_SORT) })
    }
    return chips
}

/** The panel editing [filterPanel]'s draft; only its apply button reaches the view model. */
@Composable
private fun ProductFilterPanel(
    s: ProductState.Data,
    viewModel: ProductViewModel,
    filterPanel: PosFilterPanelState<ProductPanelFilter>,
) {
    if (!filterPanel.isOpen) return
    val compact = LocalPosLayout.current.isCompact
    val applied = ProductPanelFilter.of(s)
    PosFilterPanel(
        onDismiss = filterPanel::close,
        onApply = { filterPanel.applyDraft { draft -> draft.applyTo(applied, viewModel::selectCategory, viewModel::onSort) } },
        onReset = {
            filterPanel.reset(
                compact = compact,
                clearDraft = { it.cleared() },
                applyReset = { applied.cleared().applyTo(applied, viewModel::selectCategory, viewModel::onSort) },
            )
        },
    ) {
        ProductFilterPanelFields(draft = filterPanel.draft, categories = s.categories, countFor = s::productCountFor)
    }
}
