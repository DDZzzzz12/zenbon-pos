package org.example.project.admin.features.settings.presentation.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.auth.canConfigureSystem
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.settings.presentation.components.SettingsStackableRow
import org.example.project.core.auth.TokenStore
import org.example.project.core.domain.dto.setup.VatDto
import org.example.project.core.domain.enums.product.ProductType
import org.example.project.core.domain.models.setup.VatMappingConfig
import org.example.project.core.domain.models.setup.VatRules
import org.example.project.core.domain.models.setup.getRulesFor
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.EnumKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosSuccessWatcher
import org.example.project.ui.components.formatSuffixedPercentForDisplay
import org.example.project.ui.guard.rememberGuardedDismiss
import org.example.project.ui.theme.LocalPosLayout
import org.example.project.ui.theme.PosSpacing
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * MwSt-Zuordnung — which VAT class each product type books under, per condition.
 *
 * Restores a surface deleted from composeApp in `498867657` before adminApp had an equivalent; the
 * per-`ProductType` to-go rule is fiscally relevant in Germany and was configurable from neither
 * client in between. Deliberately a separate section from MwSt-Sätze: rates are percentages, this is
 * rule selection, and merging them produces an oversized composite section.
 *
 * The composeApp original was a `ModalBottomSheet` with a `LazyColumn` inside — a POS-tablet shape.
 * Here the read view is flat cards and the editor is a [PosDialog], which self-scrolls, so nothing
 * nests a scroll inside it.
 *
 * Mutating controls are hidden below the server's own gate; the server enforces the same rule.
 */
@Composable
fun VatMappingSection(viewModel: VatMappingViewModel = koinViewModel()) {
    LaunchedEffect(Unit) { viewModel.refresh() }

    val tokenStore = koinInject<TokenStore>()
    val canEdit = tokenStore.canConfigureSystem
    val state = viewModel.state
    val sectionTitle = str(AdminStringKeys.SettingsSectionVatMapping)

    PosErrorWatcher(
        error = state.error,
        title = sectionTitle,
        onClear = viewModel::clearError,
        retryAction = viewModel::refresh,
    )
    PosSuccessWatcher(
        message = state.successMessage?.let { str(it) },
        title = sectionTitle,
        onClear = viewModel::clearSuccessMessage,
    )

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(LocalPosLayout.current.screenPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsStackableRow(
            hasActions = canEdit,
            leading = { rowModifier ->
                Column(modifier = rowModifier) {
                    Text(
                        text = sectionTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = str(AdminStringKeys.SettingsVatMappingDescription),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        ) {
            PosButton(
                text = str(AdminStringKeys.SettingsVatMappingEdit),
                onClick = viewModel::startEdit,
                enabled = !state.isLoading && !state.isSaving,
            )
        }

        if (!canEdit) {
            Text(
                text = str(AdminStringKeys.SettingsSystemConfigReadOnly),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val mapping = state.mapping
        if (mapping == null) {
            if (!state.isLoading) {
                Text(
                    text = str(AdminStringKeys.SettingsVatMappingEmpty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            ProductType.entries.forEach { productType ->
                VatMappingRuleCard(
                    productType = productType,
                    rules = mapping.getRulesFor(productType),
                    vatRates = state.vatRates,
                )
            }
        }
    }

    state.draft?.let { draft ->
        VatMappingEditDialog(
            draft = draft,
            vatRates = state.vatRates,
            isSaving = state.isSaving,
            isDirty = state.isDraftDirty,
            onUpdate = viewModel::updateRules,
            onDismiss = viewModel::cancelEdit,
            onSave = viewModel::save,
        )
    }
}

@Composable
private fun VatMappingRuleCard(
    productType: ProductType,
    rules: VatRules,
    vatRates: List<VatDto.VatResponseDto>,
) {
    PosCard(modifier = Modifier.fillMaxWidth()) {
        body {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = str(productType.vatMappingLabelKey()),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                VatRuleRow(str(AdminStringKeys.SettingsVatMappingRuleDefault), rules.default, vatRates)
                rules.toGo?.let { VatRuleRow(str(AdminStringKeys.SettingsVatMappingRuleToGo), it, vatRates) }
                rules.reduced?.let { VatRuleRow(str(AdminStringKeys.SettingsVatMappingRuleReduced), it, vatRates) }
            }
        }
    }
}

@Composable
private fun VatRuleRow(
    condition: String,
    vatId: String,
    vatRates: List<VatDto.VatResponseDto>,
) {
    // Matched on vatId, the mapping's primary key. The deleted composeApp row matched on vatClass,
    // which is "STANDARD_19"/"REDUCED_7"/"ZERO" and therefore never matched — the rate never showed.
    val language = LocalAppLanguage.current
    val percentage = vatRates.firstOrNull { it.vatId == vatId }?.vatPercentage?.let { formatSuffixedPercentForDisplay(it, language) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = condition,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (percentage != null) "$vatId · $percentage" else vatId,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun VatMappingEditDialog(
    draft: VatMappingConfig,
    vatRates: List<VatDto.VatResponseDto>,
    isSaving: Boolean,
    isDirty: Boolean,
    onUpdate: (ProductType, VatRules) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    val notSet = str(AdminStringKeys.SettingsVatMappingNotSet)
    val close = rememberGuardedDismiss(dirty = isDirty, onClose = onDismiss)
    PosDialog(
        onDismiss = close,
        title = str(AdminStringKeys.SettingsVatMappingEdit),
        maxWidth = 620.dp,
        kind = PosDialogKind.Form,
        primary = PosDialogAction(str(CommonKeys.save), onSave, isLoading = isSaving),
    ) {
        // No verticalScroll here: PosDialog already scrolls its content, and nesting one crashes the
        // measure pass on wasm.
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = str(AdminStringKeys.SettingsVatMappingEditHint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ProductType.entries.forEach { productType ->
                val rules = draft.getRulesFor(productType)
                Column(verticalArrangement = Arrangement.spacedBy(PosSpacing.sm)) {
                    Text(
                        text = str(productType.vatMappingLabelKey()),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    VatClassPicker(
                        label = str(AdminStringKeys.SettingsVatMappingRuleDefault),
                        selected = rules.default,
                        vatRates = vatRates,
                        notSetLabel = notSet,
                        onSelect = { picked -> picked?.let { onUpdate(productType, rules.copy(default = it)) } },
                    )
                    VatClassPicker(
                        label = str(AdminStringKeys.SettingsVatMappingRuleToGo),
                        selected = rules.toGo,
                        vatRates = vatRates,
                        notSetLabel = notSet,
                        onSelect = { picked -> onUpdate(productType, rules.copy(toGo = picked)) },
                        optional = true,
                    )
                    VatClassPicker(
                        label = str(AdminStringKeys.SettingsVatMappingRuleReduced),
                        selected = rules.reduced,
                        vatRates = vatRates,
                        notSetLabel = notSet,
                        onSelect = { picked -> onUpdate(productType, rules.copy(reduced = picked)) },
                        optional = true,
                    )
                }
            }
        }
    }
}

/**
 * Reuses the shared enum labels rather than adding adminApp copies of the product-type names — except
 * VOUCHER, whose shared label is deliberately blank (the product form never offers that type), which
 * rendered a card with no heading here.
 */
internal fun ProductType.vatMappingLabelKey() =
    when (this) {
        ProductType.FOOD -> EnumKeys.productTypeFood
        ProductType.DRINKS -> EnumKeys.productTypeDrinks
        ProductType.RETAIL -> EnumKeys.productTypeRetail
        ProductType.SERVICE -> EnumKeys.productTypeService
        ProductType.VOUCHER -> AdminStringKeys.SettingsVatMappingTypeVoucher
        ProductType.OTHERS -> EnumKeys.productTypeOthers
    }
