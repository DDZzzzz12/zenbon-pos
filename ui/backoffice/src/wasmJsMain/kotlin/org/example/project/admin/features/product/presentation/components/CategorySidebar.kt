package org.example.project.admin.features.product.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.components.SystemRecordHint
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.product.ProductCategoryDto
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.SystemRecords
import org.example.project.core.utils.enumStrings.categoryDisplayName
import org.example.project.ui.components.PosDangerIconButton
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.theme.PosSpacing

/**
 * The Large layout's category column: "KATEGORIEN" with a + button, "Alle Produkte" and one row per
 * category with its product count. A category whose visibility is not the default carries it under
 * its name; only the **selected** category expands the order/edit/delete actions (a system category
 * keeps the order arrows but is never edited or deleted).
 */
@Composable
fun CategorySidebar(
    categories: List<ProductCategoryDto.ProductCategoryResponseDto>,
    selectedCategoryId: Int?,
    totalCount: Int,
    countFor: (Int) -> Int,
    canManage: Boolean,
    onSelect: (Int?) -> Unit,
    onAddCategory: () -> Unit,
    onEditCategory: (ProductCategoryDto.ProductCategoryResponseDto) -> Unit,
    onDeleteCategory: (ProductCategoryDto.ProductCategoryResponseDto) -> Unit,
    onMoveUp: (ProductCategoryDto.ProductCategoryResponseDto) -> Unit,
    onMoveDown: (ProductCategoryDto.ProductCategoryResponseDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ordered = categories.sortedBy { it.sortPosition }
    val language = LocalAppLanguage.current
    Column(modifier.padding(horizontal = PosSpacing.md, vertical = PosSpacing.lg)) {
        Row(
            Modifier.fillMaxWidth().padding(start = PosSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = str(AdminStringKeys.ProductsCategoriesHeading).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (canManage) {
                PosIconButton(Icons.Default.Add, str(AdminStringKeys.ProductsAddCategory), onAddCategory, size = 40.dp, iconSize = 20.dp)
            }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item(key = "all") {
                SidebarRow(
                    label = str(AdminStringKeys.ProductsAll),
                    count = totalCount,
                    selected = selectedCategoryId == null,
                    onClick = { onSelect(null) },
                )
            }
            items(ordered, key = { it.id }) { c ->
                val index = ordered.indexOf(c)
                val selected = selectedCategoryId == c.id
                SidebarRow(
                    label = categoryDisplayName(c.name, language),
                    count = countFor(c.id),
                    selected = selected,
                    onClick = { onSelect(c.id) },
                    subtitle = categoryVisibilityLabel(c.visibility),
                    actions =
                        if (canManage && selected) {
                            {
                                CategoryActions(
                                    canMoveUp = index > 0,
                                    canMoveDown = index < ordered.lastIndex,
                                    mayEdit = SystemRecords.mayEditCategory(c.name),
                                    onMoveUp = { onMoveUp(c) },
                                    onMoveDown = { onMoveDown(c) },
                                    onEdit = { onEditCategory(c) },
                                    onDelete = { onDeleteCategory(c) },
                                )
                            }
                        } else {
                            null
                        },
                )
            }
        }
    }
}

@Composable
private fun SidebarRow(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
    actions: (@Composable () -> Unit)? = null,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = PosSpacing.md, vertical = PosSpacing.sm)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected) FontWeight.SemiBold else null,
                        color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    count.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = PosSpacing.md),
                )
            }
            actions?.invoke()
        }
    }
}

/** Order arrows and edit on the left, delete at the end; a system category only has the arrows plus its hint. */
@Composable
private fun CategoryActions(
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    mayEdit: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(top = PosSpacing.xs), verticalAlignment = Alignment.CenterVertically) {
        PosIconButton(Icons.Default.KeyboardArrowUp, str(AdminStringKeys.CategoryMoveUp), onMoveUp, enabled = canMoveUp, size = 40.dp, iconSize = 18.dp)
        PosIconButton(Icons.Default.KeyboardArrowDown, str(AdminStringKeys.CategoryMoveDown), onMoveDown, enabled = canMoveDown, size = 40.dp, iconSize = 18.dp)
        if (mayEdit) {
            PosIconButton(Icons.Default.Edit, str(AdminStringKeys.CommonEdit), onEdit, size = 40.dp, iconSize = 18.dp)
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                PosDangerIconButton(Icons.Default.Delete, str(AdminStringKeys.CommonDelete), onDelete, size = 40.dp, iconSize = 18.dp)
            }
        } else {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) { SystemRecordHint() }
        }
    }
}
