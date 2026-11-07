package org.example.project.admin.features.product.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.components.SystemRecordHint
import org.example.project.admin.core.presentation.components.adminTouchSize
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.product.presentation.ProductLayoutMode
import org.example.project.admin.features.product.presentation.ProductMeta
import org.example.project.admin.features.product.presentation.ProductSort
import org.example.project.admin.features.product.presentation.ProductSortField
import org.example.project.core.domain.dto.product.ProductDto
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.SystemRecords
import org.example.project.core.utils.enumStrings.categoryDisplayName
import org.example.project.ui.components.PosDangerIconButton
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosMoneyText

/**
 * The product table of the [ProductLayoutMode.Large] (Produkt, Nr., Preis, Status, [Kategorie]) and
 * [ProductLayoutMode.Medium] (Produkt with "Nr. · Kategorie" sub-line, Preis, Status) layouts.
 * [showCategory] only applies to Large: with one category selected the column says nothing.
 * Medium has no Nr./Kategorie header, so its Produkt header opens a sort menu for them.
 */
@Composable
fun ProductTable(
    products: List<ProductDto.ProductResponseDto>,
    sort: ProductSort,
    canManage: Boolean,
    mode: ProductLayoutMode,
    showCategory: Boolean,
    groupCounts: Map<Long, Int>,
    onSort: (ProductSort) -> Unit,
    onEdit: (ProductDto.ProductResponseDto) -> Unit,
    onDelete: (ProductDto.ProductResponseDto) -> Unit,
    modifier: Modifier = Modifier,
    emptyState: @Composable () -> Unit = {},
) {
    val large = mode == ProductLayoutMode.Large
    val columns = ProductTableColumns(large = large, category = large && showCategory)
    Column(modifier.fillMaxSize()) {
        ProductTableHeader(columns, sort, canManage, onSort)
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            if (products.isEmpty()) item { emptyState() }
            items(products, key = { it.id }) { p ->
                ProductRow(p, columns, ProductMeta.of(p, groupCounts), canManage, { onEdit(p) }, { onDelete(p) })
                HorizontalDivider()
            }
        }
    }
}

/** Which optional columns a table form carries; the weights follow from it. */
private class ProductTableColumns(
    val large: Boolean,
    val category: Boolean,
) {
    val name: Float = if (large) 2.6f else 3.4f
    val number: Float = 1.4f
    val categoryWeight: Float = 1.8f
    val price: Float = 1.4f
    val status: Float = 1.3f
}

/** Image slot (40dp) + gap (12dp) of a row's name cell; the Produkt header indents by their sum. */
private val PRODUCT_IMAGE_SLOT = 40.dp
private val PRODUCT_IMAGE_GAP = 12.dp

/** Edit + delete buttons (36dp each, 48dp on a touch-sized window) and the 4dp between them. */
private val PRODUCT_ACTION_BUTTON = 36.dp
private val PRODUCT_ACTION_GAP = 4.dp

/** Space between the right-aligned price and the status. */
private val PRODUCT_STATUS_GUTTER = 12.dp

/** Width of the actions cell; header spacer and row share it so the columns stay aligned at either touch size. */
@Composable
private fun productActionsWidth(): Dp = adminTouchSize(PRODUCT_ACTION_BUTTON) * 2 + PRODUCT_ACTION_GAP

@Composable
private fun ProductTableHeader(
    columns: ProductTableColumns,
    sort: ProductSort,
    canManage: Boolean,
    onSort: (ProductSort) -> Unit,
) {
    val toggle: (
        ProductSortField,
    ) -> Unit = { field -> onSort(field.sort(ascending = !(ProductSortField.of(sort) == field && ProductSortField.isAscending(sort)))) }
    val field = ProductSortField.of(sort)
    val ascending = ProductSortField.isAscending(sort)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (columns.large) {
            HeaderCell(
                text = str(AdminStringKeys.ProductColName),
                modifier = Modifier.weight(columns.name),
                startPadding = PRODUCT_IMAGE_SLOT + PRODUCT_IMAGE_GAP,
                activeAscending = ascending.takeIf { field == ProductSortField.NAME },
                onClick = { toggle(ProductSortField.NAME) },
            )
            HeaderCell(
                text = str(AdminStringKeys.ProductColNumber),
                modifier = Modifier.weight(columns.number),
                activeAscending = ascending.takeIf { field == ProductSortField.NUMBER },
                onClick = { toggle(ProductSortField.NUMBER) },
            )
            if (columns.category) {
                HeaderCell(
                    text = str(AdminStringKeys.ProductColCategory),
                    modifier = Modifier.weight(columns.categoryWeight),
                    activeAscending = ascending.takeIf { field == ProductSortField.CATEGORY },
                    onClick = { toggle(ProductSortField.CATEGORY) },
                )
            }
        } else {
            MediumNameHeader(sort, onSort, Modifier.weight(columns.name))
        }
        HeaderCell(
            text = str(AdminStringKeys.ProductColPrice),
            modifier = Modifier.weight(columns.price),
            textAlign = TextAlign.End,
            activeAscending = ascending.takeIf { field == ProductSortField.PRICE },
            onClick = { toggle(ProductSortField.PRICE) },
        )
        HeaderCell(
            text = str(AdminStringKeys.ProductColStatus),
            modifier = Modifier.weight(columns.status).padding(start = PRODUCT_STATUS_GUTTER),
            onClick = null,
        )
        if (canManage) Box(Modifier.size(productActionsWidth(), 1.dp))
    }
}

/** The Medium table's Produkt header: sorts by name, or by number/category through its menu. */
@Composable
private fun MediumNameHeader(
    sort: ProductSort,
    onSort: (ProductSort) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val field = ProductSortField.of(sort)
    val ascending = ProductSortField.isAscending(sort)
    val sortable = listOf(ProductSortField.NAME, ProductSortField.NUMBER, ProductSortField.CATEGORY)
    Box(modifier) {
        HeaderCell(
            text = str(AdminStringKeys.ProductColName),
            modifier = Modifier.fillMaxWidth(),
            startPadding = PRODUCT_IMAGE_SLOT + PRODUCT_IMAGE_GAP,
            activeAscending = ascending.takeIf { field in sortable },
            onClick = { open = true },
        )
        PosDropdown(expanded = open, onDismissRequest = { open = false }) {
            sortable.forEach { candidate ->
                PosDropdownItem(
                    text = sortFieldLabel(candidate),
                    selected = candidate == field,
                    minHeight = adminTouchSize(40.dp),
                    onClick = {
                        open = false
                        // Picking the active column again flips its direction.
                        onSort(candidate.sort(ascending = if (candidate == field) !ascending else true))
                    },
                )
            }
        }
    }
}

@Composable
private fun HeaderCell(
    text: String,
    modifier: Modifier,
    textAlign: TextAlign = TextAlign.Start,
    startPadding: Dp = 0.dp,
    activeAscending: Boolean? = null,
    onClick: (() -> Unit)? = null,
) {
    val arrangement = if (textAlign == TextAlign.End) Arrangement.End else Arrangement.Start
    Row(
        modifier = (if (onClick != null) modifier.clickable { onClick() } else modifier).padding(start = startPadding),
        horizontalArrangement = arrangement,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (activeAscending != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (activeAscending != null) FontWeight.SemiBold else null,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            textAlign = textAlign,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (activeAscending != null) {
            Icon(
                imageVector = if (activeAscending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                contentDescription = null,
                modifier = Modifier.padding(start = 2.dp).size(14.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ProductRow(
    p: ProductDto.ProductResponseDto,
    columns: ProductTableColumns,
    meta: ProductMeta,
    canManage: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val language = LocalAppLanguage.current
    val inactiveTint = if (p.isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(columns.name),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PRODUCT_IMAGE_GAP),
        ) {
            ProductThumbnail(p.imageUrl, PRODUCT_IMAGE_SLOT)
            Column(Modifier.weight(1f)) {
                Text(
                    p.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = inactiveTint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Large carries Nr./Kategorie in columns, so its sub-line is the Pfand / choice-group meta; Medium has no such columns.
                val subline =
                    if (columns.large) {
                        productMetaText(meta)
                    } else {
                        "${p.productNumber} · ${categoryDisplayName(p.categoryName, language)}"
                    }
                if (subline != null) {
                    Text(
                        subline,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (columns.large) {
            Text(
                p.productNumber,
                Modifier.weight(columns.number),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (columns.category) {
            Text(
                categoryDisplayName(p.categoryName, language),
                Modifier.weight(columns.categoryWeight),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        PosMoneyText(p.price, p.currency, Modifier.weight(columns.price), MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
        Box(Modifier.weight(columns.status).padding(start = PRODUCT_STATUS_GUTTER)) { ProductStatusLabel(p.isActive) }
        if (canManage) {
            if (SystemRecords.mayEditProduct(p.productType)) {
                val button = adminTouchSize(PRODUCT_ACTION_BUTTON)
                Row(Modifier.width(productActionsWidth()), horizontalArrangement = Arrangement.spacedBy(PRODUCT_ACTION_GAP)) {
                    PosIconButton(Icons.Default.Edit, str(AdminStringKeys.CommonEdit), onEdit, size = button, iconSize = 18.dp)
                    PosDangerIconButton(Icons.Default.Delete, str(AdminStringKeys.CommonDelete), onDelete, size = button, iconSize = 18.dp)
                }
            } else {
                // Same width as the action pair, so the column stays aligned with the header spacer.
                SystemRecordHint(Modifier.width(productActionsWidth()))
            }
        }
    }
}
