package org.example.project.admin.features.product.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.components.SystemRecordHint
import org.example.project.admin.core.presentation.components.adminTouchSize
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.product.presentation.ProductScreenActions
import org.example.project.admin.features.product.presentation.ProductState
import org.example.project.core.domain.dto.product.ProductCategoryDto
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.SystemRecords
import org.example.project.core.utils.enumStrings.categoryDisplayName
import org.example.project.ui.components.PosDangerIconButton
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.filter.filterResultLine

/**
 * "Kategorien verwalten" for the Medium and Compact layouts (Large manages categories in its
 * sidebar): one row per category with its product count and non-default visibility, order arrows,
 * edit and delete, and "Kategorie hinzufügen" as the primary action. Edit and add hand over to the
 * category drawer, so [onDismiss] is called first by the caller.
 */
@Composable
fun CategoriesManagerDialog(
    s: ProductState.Data,
    actions: ProductScreenActions,
    onMoveUp: (ProductCategoryDto.ProductCategoryResponseDto) -> Unit,
    onMoveDown: (ProductCategoryDto.ProductCategoryResponseDto) -> Unit,
    onDismiss: () -> Unit,
) {
    val ordered = s.categories.sortedBy { it.sortPosition }
    PosDialog(
        onDismiss = onDismiss,
        title = str(AdminStringKeys.ProductsManageCategories),
        kind = PosDialogKind.Form,
        primary = PosDialogAction(str(AdminStringKeys.ProductsAddCategory), { actions.createCategory() }),
        secondary = PosDialogAction(str(CommonKeys.close), onDismiss),
    ) {
        Column(Modifier.fillMaxWidth()) {
            ordered.forEachIndexed { index, c ->
                CategoryManagerRow(
                    category = c,
                    count = s.productCountFor(c.id),
                    canMoveUp = index > 0,
                    canMoveDown = index < ordered.lastIndex,
                    onMoveUp = { onMoveUp(c) },
                    onMoveDown = { onMoveDown(c) },
                    onEdit = { actions.editCategory(c) },
                    onDelete = { actions.deleteCategory(c) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun CategoryManagerRow(
    category: ProductCategoryDto.ProductCategoryResponseDto,
    count: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val button = adminTouchSize(32.dp)
    val mayEdit = SystemRecords.mayEditCategory(category.name)
    val countText = filterResultLine(count, AdminStringKeys.ProductsResultCountOne, AdminStringKeys.ProductsResultCount)
    val subline = listOfNotNull(countText, categoryVisibilityLabel(category.visibility)).joinToString(" · ")
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(categoryDisplayName(category.name, LocalAppLanguage.current), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(subline, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!mayEdit) SystemRecordHint()
        }
        Row(horizontalArrangement = Arrangement.spacedBy(0.dp), verticalAlignment = Alignment.CenterVertically) {
            PosIconButton(Icons.Default.KeyboardArrowUp, str(AdminStringKeys.CategoryMoveUp), onMoveUp, enabled = canMoveUp, size = button, iconSize = 18.dp)
            PosIconButton(
                Icons.Default.KeyboardArrowDown,
                str(AdminStringKeys.CategoryMoveDown),
                onMoveDown,
                enabled = canMoveDown,
                size = button,
                iconSize = 18.dp,
            )
            if (mayEdit) {
                PosIconButton(Icons.Default.Edit, str(AdminStringKeys.CommonEdit), onEdit, size = button, iconSize = 18.dp)
                PosDangerIconButton(Icons.Default.Delete, str(AdminStringKeys.CommonDelete), onDelete, size = button, iconSize = 18.dp)
            }
        }
    }
}
