package org.example.project.admin.features.promotion.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.components.DeleteConfirmDialog
import org.example.project.admin.core.presentation.components.adminTouchSize
import org.example.project.admin.core.presentation.format.asDisplayDay
import org.example.project.admin.core.presentation.strings.AdminLocale
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.product.presentation.components.ProductStatusLabel
import org.example.project.admin.features.promotion.presentation.PromotionsState
import org.example.project.admin.features.promotion.presentation.PromotionsViewModel
import org.example.project.admin.features.promotion.presentation.promotionValidityText
import org.example.project.core.domain.dto.promotion.PromotionDto
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.PromotionKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDangerIconButton
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosMoneyText
import org.example.project.ui.components.PosSecondaryButton
import org.koin.compose.viewmodel.koinViewModel

/**
 * The promotion manager, opened from the Products toolbar.
 *
 * Deactivation, never deletion: promotions are referenced by `bill_promotion`, so the server has
 * no hard delete. The HTTP verb is `DELETE` but the semantics are "stop offering this" — the copy
 * here must say so, and must not look destructive.
 */
@Composable
fun PromotionsManagerDialog(
    onDismiss: () -> Unit,
    viewModel: PromotionsViewModel = koinViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.refresh() }
    var pendingDeactivate by remember { mutableStateOf<PromotionDto.PromotionResponseDto?>(null) }

    PosErrorWatcher(
        error = (viewModel.state as? PromotionsState.Data)?.actionError,
        title = str(AdminStringKeys.PromotionsManagerTitle),
        onClear = viewModel::clearActionError,
    )

    PosDialog(
        onDismiss = onDismiss,
        title = str(AdminStringKeys.PromotionsManagerTitle),
        kind = PosDialogKind.Form,
        primary = PosDialogAction(str(PromotionKeys.createPromotion), viewModel::openCreate),
        secondary = PosDialogAction(str(CommonKeys.close), onDismiss),
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            when (val s = viewModel.state) {
                is PromotionsState.Loading ->
                    Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { CircularProgressIndicator() }
                is PromotionsState.Error ->
                    Text(
                        text = str(s.error.messageKey, *s.error.messageArgs.toTypedArray()),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                is PromotionsState.Data -> {
                    if (s.promotions.isEmpty()) {
                        Text(
                            text = str(AdminStringKeys.PromotionsEmpty),
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        s.promotions.forEach { promotion ->
                            PromotionRow(
                                promotion = promotion,
                                isSelected = s.selectedId == promotion.id,
                                usage = if (s.selectedId == promotion.id) s.usage else null,
                                onSelect = { viewModel.select(if (s.selectedId == promotion.id) null else promotion.id) },
                                onEdit = { viewModel.openEdit(promotion) },
                                onDeactivate = { pendingDeactivate = promotion },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    viewModel.editor?.let { e ->
        PromotionEditorDialog(
            editor = e,
            products = (viewModel.state as? PromotionsState.Data)?.products.orEmpty(),
            onField = viewModel::onField,
            onSave = viewModel::save,
            onCancel = viewModel::closeEditor,
            dirty = viewModel.isEditorDirty,
        )
    }

    pendingDeactivate?.let { promotion ->
        DeleteConfirmDialog(
            entityType = str(AdminStringKeys.DeleteEntityPromotion),
            entityName = promotion.name,
            consequence = str(AdminStringKeys.PromotionDeactivateMessage),
            confirmText = str(AdminStringKeys.PromotionDeactivateAction),
            irreversible = false,
            titleKey = AdminStringKeys.DeactivateConfirmNamedTitle,
            onConfirm = {
                viewModel.deactivate(promotion.id)
                pendingDeactivate = null
            },
            onDismiss = { pendingDeactivate = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PromotionRow(
    promotion: PromotionDto.PromotionResponseDto,
    isSelected: Boolean,
    usage: PromotionDto.PromotionUsageDto?,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDeactivate: () -> Unit,
) {
    val language = AdminLocale.language
    val validity =
        promotionValidityText(
            fromDay = promotion.validFrom.asDisplayDay(language),
            untilDay = promotion.validUntil.asDisplayDay(language),
            unlimited = str(AdminStringKeys.PromotionValidityUnlimited),
        )
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(promotion.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "${discountTypeLabel(promotion.discountType)} · ${scopeLabel(promotion.scope)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(text = validity, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ProductStatusLabel(promotion.isActive)
            PosIconButton(
                Icons.Default.Edit,
                str(CommonKeys.edit),
                onEdit,
                size = adminTouchSize(32.dp),
                iconSize = 18.dp,
            )
            if (promotion.isActive) {
                PosDangerIconButton(
                    Icons.Default.Block,
                    str(AdminStringKeys.PromotionDeactivateAction),
                    onDeactivate,
                    size = adminTouchSize(32.dp),
                    iconSize = 18.dp,
                )
            }
        }
        if (isSelected && usage != null) {
            UsageBand(usage, onCollapse = onSelect)
        } else {
            PosSecondaryButton(
                text = str(AdminStringKeys.PromotionShowUsage),
                onClick = onSelect,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** "Nutzung · Genutzt: 38× · Rabatt gesamt: 142,60 €" on a grey band; a tap collapses it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UsageBand(
    usage: PromotionDto.PromotionUsageDto,
    onCollapse: () -> Unit,
) {
    FlowRow(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onCollapse)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(str(AdminStringKeys.PromotionUsageTitle), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        Text("${str(PromotionKeys.usageCount)}: ${usage.timesUsed}", style = MaterialTheme.typography.bodySmall)
        Text("${str(PromotionKeys.totalDiscountGiven)}:", style = MaterialTheme.typography.bodySmall)
        // PromotionUsageDto carries no currency field; "EUR" matches the existing precedent
        // in VatBreakdownSection/MoneyField for DTOs that predate the currency column.
        PosMoneyText(usage.totalDiscountGiven, "EUR", style = MaterialTheme.typography.bodySmall)
    }
}
