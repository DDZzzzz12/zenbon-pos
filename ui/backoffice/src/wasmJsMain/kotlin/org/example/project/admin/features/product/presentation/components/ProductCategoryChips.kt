package org.example.project.admin.features.product.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.components.adminTouchSize
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.product.ProductCategoryDto
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.enumStrings.categoryDisplayName
import org.example.project.ui.components.PosHorizontalScroller
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.theme.LocalPosLayout
import org.example.project.ui.theme.PosSpacing

/**
 * The Medium layout's category row: "Alle" and every category with its counter in one scrolling
 * strip, and (for [canManage]) a trailing button opening the category list.
 */
@Composable
fun ProductCategoryChips(
    categories: List<ProductCategoryDto.ProductCategoryResponseDto>,
    selectedCategoryId: Int?,
    totalCount: Int,
    countFor: (Int) -> Int,
    canManage: Boolean,
    onSelect: (Int?) -> Unit,
    onManageCategories: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val language = LocalAppLanguage.current
    Row(
        modifier.fillMaxWidth().padding(horizontal = LocalPosLayout.current.screenPadding),
        horizontalArrangement = Arrangement.spacedBy(PosSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PosHorizontalScroller(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(PosSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CategoryChip(str(AdminStringKeys.ProductsAllShort), totalCount, selectedCategoryId == null) { onSelect(null) }
            categories.sortedBy { it.sortPosition }.forEach { c ->
                CategoryChip(categoryDisplayName(c.name, language), countFor(c.id), selectedCategoryId == c.id) { onSelect(c.id) }
            }
        }
        if (canManage) {
            PosIconButton(Icons.Default.Edit, str(AdminStringKeys.ProductsManageCategories), onManageCategories, size = adminTouchSize(40.dp), iconSize = 20.dp)
        }
    }
}

@Composable
private fun CategoryChip(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = if (selected) colors.secondaryContainer else colors.surface,
        border = BorderStroke(1.dp, if (selected) colors.primary else colors.outline),
    ) {
        Row(
            Modifier.heightIn(min = adminTouchSize(36.dp)).padding(horizontal = PosSpacing.lg),
            horizontalArrangement = Arrangement.spacedBy(PosSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) colors.onSecondaryContainer else colors.onSurface,
                maxLines = 1,
                softWrap = false,
            )
            Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1, softWrap = false)
        }
    }
}
