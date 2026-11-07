package org.example.project.ui.screens.order

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.example.project.core.browser.isBrowserClient
import org.example.project.core.data.models.Product
import org.example.project.core.data.models.ProductCategory
import org.example.project.core.presentation.strings.keys.ProductGridKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.LocalWindowSizeClass
import org.example.project.core.utils.isCompact
import org.example.project.core.utils.isDesktop
import org.example.project.ui.components.PosIconButton

@Composable
fun PosProductGrid(
    categories: List<ProductCategory>,
    products: List<Product>,
    favouriteProductIds: Set<Long>,
    selectedCategoryId: Long?,
    onCategorySelected: (Long?) -> Unit,
    onProductSelected: (Product) -> Unit,
    modifier: Modifier = Modifier,
    searchResults: List<Product>? = null,
    onProductLongPress: ((Product) -> Unit)? = null,
    productIdsWithGroups: Set<Long> = emptySet(),
) {
    val windowSize = LocalWindowSizeClass.current
    val displayProducts = if (!searchResults.isNullOrEmpty()) searchResults else products

    Column(modifier = modifier) {
        CategoryChipRow(
            categories = categories,
            favouriteProductIds = favouriteProductIds,
            selectedCategoryId = selectedCategoryId,
            onCategorySelected = onCategorySelected,
        )

        HorizontalDivider()

        // Product grid
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(displayProducts) { product ->
                PosProductCard(
                    product = product,
                    onClick = { onProductSelected(product) },
                    isFavourite = product.id in favouriteProductIds,
                    isCompact = windowSize.isCompact(),
                    hasChoiceGroups = product.id in productIdsWithGroups,
                    onLongClick = onProductLongPress?.let { { it(product) } },
                )
            }
        }
    }
}

/**
 * The category chips. A touch screen swipes the row, but a mouse cannot: the wheel only scrolls
 * vertically and a pressed drag does not pan a LazyRow, so on the browser/desktop till the chips
 * past the edge were unreachable. Arrow buttons step the row there, shown only while there is
 * something to scroll to.
 */
@Composable
private fun CategoryChipRow(
    categories: List<ProductCategory>,
    favouriteProductIds: Set<Long>,
    selectedCategoryId: Long?,
    onCategorySelected: (Long?) -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val offersArrows = isBrowserClient || isDesktop()
    val step = with(LocalDensity.current) { 240.dp.toPx() }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (offersArrows && listState.canScrollBackward) {
            PosIconButton(
                icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = null,
                modifier = Modifier.focusProperties { canFocus = false },
                onClick = { scope.launch { listState.animateScrollBy(-step) } },
            )
        }
        LazyRow(
            modifier = Modifier.weight(1f),
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            // "All" chip
            item {
                FilterChip(
                    modifier = Modifier.focusProperties { canFocus = false },
                    selected = selectedCategoryId == -2L,
                    onClick = { onCategorySelected(-2L) },
                    label = { Text(str(ProductGridKeys.all)) },
                    colors =
                        FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                )
            }

            // Favourites chip
            if (favouriteProductIds.isNotEmpty()) {
                item {
                    FilterChip(
                        modifier = Modifier.focusProperties { canFocus = false },
                        selected = selectedCategoryId == -1L,
                        onClick = { onCategorySelected(-1L) },
                        label = { Text(str(ProductGridKeys.favourites)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize),
                            )
                        },
                        colors =
                            FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                    )
                }
            }

            // Category chips
            items(categories) { category ->
                FilterChip(
                    modifier = Modifier.focusProperties { canFocus = false },
                    selected = selectedCategoryId == category.id,
                    onClick = { onCategorySelected(category.id) },
                    label = { Text(category.name) },
                    colors =
                        FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                )
            }
        }
        if (offersArrows && listState.canScrollForward) {
            PosIconButton(
                icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.focusProperties { canFocus = false },
                onClick = { scope.launch { listState.animateScrollBy(step) } },
            )
        }
    }
}
