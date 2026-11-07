package org.example.project.admin.features.settings.presentation.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.auth.canManageProducts
import org.example.project.admin.core.presentation.components.DeleteConfirmDialog
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.settings.presentation.components.SettingsStackableRow
import org.example.project.core.auth.TokenStore
import org.example.project.core.domain.dto.setup.VatDto
import org.example.project.core.domain.enums.setup.GermanVatRate
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.SettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.enumStrings.vatClassLabel
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosTextField
import org.example.project.ui.components.form.rememberPosFormFocus
import org.example.project.ui.components.form.requiredLabel
import org.example.project.ui.components.formatSuffixedPercentForDisplay
import org.example.project.ui.screens.settings.components.AddItemDialog
import org.example.project.ui.theme.LocalPosLayout
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.abs

/**
 * MwSt-Sätze — the shop's configured VAT rates.
 *
 * Deliberately not a free-text rate field: a rate is picked, not typed,
 * which lets an operator configure a rate the server will reject with a 422, or worse, a legal-but-dead
 * rate like 16 % (unused since 2020). This pairs the same CRUD with [VatRatePicker], which offers only
 * `GermanVatRate.configurableRates`. That is the one place in sub-project C where adminApp intentionally
 * diverges from the till rather than sharing a component.
 *
 * `vatId` is a caller-supplied primary key, so it is editable on create and fixed on edit — changing it
 * would silently orphan every product pointing at the old one.
 *
 * Mutating controls are hidden below MANAGER; the server enforces the same rule.
 */
@Composable
fun VatRatesSection(viewModel: VatViewModel = koinViewModel()) {
    LaunchedEffect(Unit) { viewModel.refresh() }

    val tokenStore = koinInject<TokenStore>()
    val canEdit = tokenStore.canManageProducts
    var pendingDelete by remember { mutableStateOf<VatDto.VatResponseDto?>(null) }

    PosErrorWatcher(
        error = viewModel.state.error,
        title = str(SettingsKeys.vatManagementSection),
        onClear = viewModel::clearError,
        retryAction = viewModel::refresh,
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
                        text = str(SettingsKeys.vatManagementSection),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = str(SettingsKeys.vatManagementDescription),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        ) {
            PosButton(
                text = str(SettingsKeys.addVatRate),
                onClick = viewModel::showAddDialog,
            )
        }

        if (viewModel.state.rates.isEmpty() && !viewModel.state.isLoading) {
            Text(
                text = str(SettingsKeys.noVatRates),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        viewModel.state.rates.forEach { rate ->
            val rateTitle = "${vatClassDisplay(rate.vatClass)} · ${formatSuffixedPercentForDisplay(rate.vatPercentage, LocalAppLanguage.current)}"
            PosCard(modifier = Modifier.fillMaxWidth()) {
                body {
                    SettingsStackableRow(
                        hasActions = canEdit,
                        leading = { rowModifier ->
                            Column(modifier = rowModifier) {
                                Text(
                                    text = rateTitle,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    text = str(AdminStringKeys.SettingsVatClassLetter, rate.vatId),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                // Flag a stored rate that is no longer statutorily current, so an
                                // operator can see why the picker will not offer it again.
                                if (rate.vatRate.toGermanVatRate()?.configurable == false) {
                                    Text(
                                        text = str(AdminStringKeys.SettingsVatHistoricalNote),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                    ) {
                        PosButton(
                            text = str(CommonKeys.edit),
                            onClick = { viewModel.showEditDialog(rate) },
                        )
                        PosButton(
                            text = str(CommonKeys.delete),
                            onClick = { pendingDelete = rate },
                        )
                    }
                }
            }
        }
    }

    if (viewModel.state.showDialog) {
        val editing = viewModel.state.editing
        VatRateForm(
            existing = editing,
            isSaving = viewModel.state.isSaving,
            onDismiss = viewModel::hideDialog,
            onConfirm = { vatId, vatClass, rate ->
                if (editing == null) {
                    viewModel.create(vatId, vatClass, rate)
                } else {
                    viewModel.update(editing.vatId, vatClass, rate)
                }
            },
        )
    }

    pendingDelete?.let { target ->
        DeleteConfirmDialog(
            entityType = str(AdminStringKeys.DeleteEntityVatRate),
            entityName = "${vatClassDisplay(target.vatClass)} · ${formatSuffixedPercentForDisplay(target.vatPercentage, LocalAppLanguage.current)}",
            onConfirm = {
                viewModel.delete(target.vatId)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun VatRateForm(
    existing: VatDto.VatResponseDto?,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (vatId: String, vatClass: String, rate: Double) -> Unit,
) {
    var vatId by remember { mutableStateOf(existing?.vatId ?: "") }
    var vatClass by remember { mutableStateOf(existing?.vatClass ?: "") }
    var selected by remember { mutableStateOf(existing?.vatRate?.toGermanVatRate()) }

    // Transient validation state (not part of `dirty`): set by a refused Save, cleared per field on edit.
    var fieldErrors by remember { mutableStateOf<Map<VatRateField, StringKey>>(emptyMap()) }
    var validationAttempt by remember { mutableStateOf(0) }
    val focus = rememberPosFormFocus<VatRateField>()
    LaunchedEffect(validationAttempt) {
        if (validationAttempt > 0) focus.revealFirst(VatRateField.entries, fieldErrors)
    }
    val errorText = fieldErrors.mapValues { (_, key) -> str(key) }

    fun anchor(field: VatRateField): Modifier = with(focus) { Modifier.fillMaxWidth().formAnchor(field) }

    AddItemDialog(
        title = str(if (existing == null) SettingsKeys.addVatRate else SettingsKeys.editVatRate),
        onDismiss = onDismiss,
        onConfirm = {
            val errors = validateVatRateInput(vatId, vatClass, selected)
            val rate = selected
            if (errors.isNotEmpty() || rate == null) {
                fieldErrors = errors
                validationAttempt++
            } else {
                onConfirm(vatId.trim(), vatClass.trim(), rate.rate)
            }
        },
        confirmText = str(CommonKeys.save),
        dismissText = str(CommonKeys.cancel),
        confirmLoading = isSaving,
        dirty =
            vatId != (existing?.vatId ?: "") ||
                vatClass != (existing?.vatClass ?: "") ||
                selected != existing?.vatRate?.toGermanVatRate(),
    ) {
        PosTextField(
            value = vatId,
            onValueChange = {
                vatId = it
                fieldErrors = fieldErrors - VatRateField.VAT_ID
            },
            label = requiredLabel(str(SettingsKeys.vatId)),
            // Fixed on edit: vatId is the key products reference, so changing it would orphan them.
            enabled = existing == null,
            modifier = anchor(VatRateField.VAT_ID),
            isError = VatRateField.VAT_ID in errorText,
            supportingText = errorText[VatRateField.VAT_ID]?.let { msg -> { Text(msg) } },
        )
        PosTextField(
            value = vatClass,
            onValueChange = {
                vatClass = it
                fieldErrors = fieldErrors - VatRateField.VAT_CLASS
            },
            label = requiredLabel(str(SettingsKeys.vatClass)),
            modifier = anchor(VatRateField.VAT_CLASS),
            isError = VatRateField.VAT_CLASS in errorText,
            supportingText = errorText[VatRateField.VAT_CLASS]?.let { msg -> { Text(msg) } },
        )
        Text(requiredLabel(str(SettingsKeys.vatRate)), style = MaterialTheme.typography.labelMedium)
        VatRatePicker(
            current = selected,
            onSelect = {
                selected = it
                fieldErrors = fieldErrors - VatRateField.RATE
            },
            historicalNote = str(AdminStringKeys.SettingsVatHistoricalNote),
            modifier = anchor(VatRateField.RATE),
        )
        errorText[VatRateField.RATE]?.let { msg ->
            Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * Maps a stored rate back onto its enum constant, tolerating float representation error — 0.055 and
 * 0.078 are not exactly representable, so `==` would miss them.
 */
private fun Double.toGermanVatRate(): GermanVatRate? = GermanVatRate.entries.firstOrNull { abs(it.rate - this) < 0.0001 }

/** A known class code gets its name; any other operator-entered class is shown as typed. */
@Composable
private fun vatClassDisplay(raw: String): String = vatClassLabel(raw, LocalAppLanguage.current)
