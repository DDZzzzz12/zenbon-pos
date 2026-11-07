package org.example.project.admin.features.product.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.components.AdminPageTitle
import org.example.project.admin.core.presentation.components.adminTouchSize
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.product.presentation.ProductLayoutMode
import org.example.project.admin.features.product.presentation.ProductSummary
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.filter.filterResultLine
import org.example.project.ui.theme.LocalPosColors
import org.example.project.ui.theme.LocalPosLayout
import org.example.project.ui.theme.PosSpacing

/**
 * The page header of the Large and Medium layouts: the title with the catalog summary, and the
 * actions ([canManage] only). Large shows the three manager buttons beside "Produkt anlegen";
 * Medium shows only "Produkt anlegen" (the managers sit in the Verwalten menu below).
 */
@Composable
fun ProductHeader(
    summary: ProductSummary,
    mode: ProductLayoutMode,
    canManage: Boolean,
    managers: ProductManagerActions,
    onAddProduct: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(
            start = LocalPosLayout.current.screenPadding,
            end = LocalPosLayout.current.screenPadding,
            top = LocalPosLayout.current.screenPadding,
            bottom = PosSpacing.lg,
        ),
        horizontalArrangement = Arrangement.spacedBy(PosSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            AdminPageTitle(str(AdminStringKeys.ProductsTitle))
            ProductSummaryLine(summary)
        }
        if (canManage) {
            val height = adminTouchSize(40.dp)
            if (mode == ProductLayoutMode.Large) {
                ManagerButton(AdminStringKeys.ProductsHeaderExtras, managers.onExtras, height)
                ManagerButton(AdminStringKeys.ProductsHeaderChoiceGroups, managers.onChoiceGroups, height)
                ManagerButton(AdminStringKeys.ProductsHeaderPromotions, managers.onPromotions, height)
            }
            PosButton(text = str(AdminStringKeys.ProductsAddProduct), icon = Icons.Default.Add, onClick = onAddProduct, height = height)
        }
    }
}

@Composable
private fun ManagerButton(
    key: StringKey,
    onClick: () -> Unit,
    height: Dp,
) {
    PosSecondaryButton(text = str(key), onClick = onClick, height = height)
}

/** "53 Produkte · ● 49 aktiv · ● 4 inaktiv"; the inactive part is dropped when nothing is inactive. */
@Composable
private fun ProductSummaryLine(summary: ProductSummary) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(horizontalArrangement = Arrangement.spacedBy(PosSpacing.lg), verticalAlignment = Alignment.CenterVertically) {
        Text(
            filterResultLine(summary.total, AdminStringKeys.ProductsResultCountOne, AdminStringKeys.ProductsResultCount),
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
        SummaryPart(str(AdminStringKeys.ProductsSummaryActive, summary.active), LocalPosColors.current.posAvailable)
        if (summary.inactive > 0) SummaryPart(str(AdminStringKeys.ProductsSummaryInactive, summary.inactive), muted)
    }
}

@Composable
private fun SummaryPart(
    text: String,
    dot: Color,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(PosSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
