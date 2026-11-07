package org.example.project.admin.features.product.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.components.SystemRecordHint
import org.example.project.core.domain.dto.product.ProductDto
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.utils.SystemRecords
import org.example.project.core.utils.enumStrings.categoryDisplayName
import org.example.project.ui.components.PosMoneyText
import org.example.project.ui.theme.LocalPosLayout
import org.example.project.ui.theme.PosSpacing

/**
 * The Compact layout's product list: one tappable row per product (image, name, "Nr. · Kategorie",
 * status, price at the end). A tap opens the edit form for [canManage]; Löschen lives in that form.
 * [emptyState] stands in for the rows when [products] is empty.
 */
@Composable
fun ProductRowList(
    products: List<ProductDto.ProductResponseDto>,
    canManage: Boolean,
    onEdit: (ProductDto.ProductResponseDto) -> Unit,
    modifier: Modifier = Modifier,
    emptyState: @Composable () -> Unit = {},
) {
    val padding = LocalPosLayout.current.screenPadding
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = PosSpacing.xl),
    ) {
        if (products.isEmpty()) item { Column(Modifier.padding(horizontal = padding)) { emptyState() } }
        items(products, key = { it.id }) { p ->
            val editable = canManage && SystemRecords.mayEditProduct(p.productType)
            ProductListRow(p, editable, showSystemHint = canManage && !editable, onClick = { onEdit(p) })
            HorizontalDivider()
        }
    }
}

@Composable
private fun ProductListRow(
    p: ProductDto.ProductResponseDto,
    editable: Boolean,
    showSystemHint: Boolean,
    onClick: () -> Unit,
) {
    val padding = LocalPosLayout.current.screenPadding
    val tint = if (p.isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (editable) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 72.dp)
            .padding(horizontal = padding, vertical = PosSpacing.lg),
        horizontalArrangement = Arrangement.spacedBy(PosSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProductThumbnail(p.imageUrl, 48.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                p.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = tint,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${p.productNumber} · ${categoryDisplayName(p.categoryName, LocalAppLanguage.current)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ProductStatusLabel(p.isActive)
            if (showSystemHint) SystemRecordHint()
        }
        PosMoneyText(p.price, p.currency, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), maxLines = 1)
    }
}
