package org.example.project.ui.components.table

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.example.project.core.presentation.strings.keys.AccessibilityKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosTableAlign
import org.example.project.ui.components.PosTableHeaderText
import org.example.project.ui.theme.PosSpacing

/**
 * Generic inline-editable data-table chrome: a sortable header, divider-separated body rows rendered
 * via the caller's [rowContent] slot, an optional persistent add-row ([newRowContent]), an optional
 * pagination footer ([pagination]), and an empty state. It is a controlled component — the caller
 * owns all row/edit state and renders each row's cells (using the PosTable*Cell helpers) matching
 * the [columns] weights. Row-coupled edit logic (e.g. dirty-detection + confirm-when-dirty) lives in
 * the caller's [rowContent], which is why cells are not a per-column lambda.
 *
 * [newRowOnTop] puts the new-row under the header instead of after the last row; [rowModifier] styles a
 * row (an inactive record at half opacity). [columns] drives the header only. Each row's cells must use the same weights (same convention the
 * settings table used before this component existed).
 */
@Composable
fun <T> PosEditableDataTable(
    columns: List<PosEditableColumn>,
    items: List<T>,
    rowKey: (T) -> Any,
    emptyText: String,
    rowContent: @Composable RowScope.(item: T, index: Int) -> Unit,
    modifier: Modifier = Modifier,
    sortState: PosSortState? = null,
    onSortChanged: ((columnId: String) -> Unit)? = null,
    newRowContent: (@Composable RowScope.() -> Unit)? = null,
    pagination: PosPaginationSpec? = null,
    newRowOnTop: Boolean = false,
    rowModifier: (T) -> Modifier = { Modifier },
) {
    Column(modifier = modifier.fillMaxSize()) {
        HeaderRow(columns, sortState, onSortChanged)
        HorizontalDivider()
        if (newRowContent != null && newRowOnTop) {
            NewRow(newRowContent)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        if (items.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                itemsIndexed(items, key = { _, item -> rowKey(item) }) { index, item ->
                    Row(
                        modifier = rowModifier(item).fillMaxWidth().padding(horizontal = 16.dp, vertical = PosSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) { rowContent(item, index) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }

        if (newRowContent != null && !newRowOnTop) {
            HorizontalDivider()
            NewRow(newRowContent)
        }

        if (pagination != null) {
            HorizontalDivider()
            PaginationFooter(pagination)
        }
    }
}

@Composable
private fun NewRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.12f))
                .padding(horizontal = 16.dp, vertical = PosSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun HeaderRow(
    columns: List<PosEditableColumn>,
    sortState: PosSortState?,
    onSortChanged: ((columnId: String) -> Unit)?,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        columns.forEach { col ->
            val base = Modifier.weight(col.weight)
            if (col.sortable && onSortChanged != null) {
                Row(
                    modifier =
                        base
                            .clip(MaterialTheme.shapes.small)
                            .clickable { onSortChanged(col.id) }
                            .padding(start = col.headerInset, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PosTableHeaderText(
                        text = col.title,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (sortState?.columnId == col.id) {
                        Icon(
                            imageVector = if (sortState.ascending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            } else {
                PosTableHeaderText(
                    text = col.title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = if (col.align == PosTableAlign.End) TextAlign.End else TextAlign.Start,
                    modifier = base.padding(start = col.headerInset),
                )
            }
        }
    }
}

@Composable
fun PaginationFooter(p: PosPaginationSpec) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = pageRangeLabel(p.currentPage, p.pageSize, p.currentPageItemCount, p.totalFilteredCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PosIconButton(
                icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = str(AccessibilityKeys.previous),
                onClick = { p.onPageChanged(p.currentPage - 1) },
                enabled = p.currentPage > 0,
                size = 32.dp,
                iconSize = 20.dp,
            )
            Text(
                text = "${p.currentPage + 1} / ${p.totalPages}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PosIconButton(
                icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = str(AccessibilityKeys.next),
                onClick = { p.onPageChanged(p.currentPage + 1) },
                enabled = p.currentPage < p.totalPages - 1,
                size = 32.dp,
                iconSize = 20.dp,
            )
        }
    }
}
