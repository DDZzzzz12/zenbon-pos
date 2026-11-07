package org.example.project.admin.features.documents.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.datetime.LocalDate
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.documents.presentation.DocumentSearchFilter
import org.example.project.admin.features.documents.presentation.periodLabel
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.PosPatternKeys
import org.example.project.core.presentation.strings.str
import org.example.project.features.reports.presentation.ReportsGranularity
import org.example.project.features.reports.presentation.components.PeriodStepperCompact
import org.example.project.features.reports.presentation.isCurrentPeriod
import org.example.project.features.reports.presentation.stepped
import org.example.project.ui.components.PosSegmentedButtonRow
import org.example.project.ui.components.PosTextField
import org.example.project.ui.components.PosTextFieldType
import org.example.project.ui.components.filter.PosFilterDraft
import org.example.project.ui.components.filter.PosFilterFieldGroup
import org.example.project.ui.components.filter.PosFilterSwitchRow
import org.example.project.ui.theme.PosSpacing

/**
 * The Belege filter panel body, editing [draft] only — nothing reaches a request before the panel's
 * apply button. Zenbon order: period ([showPeriod], wherever the bar has no room for the stepper) →
 * document type ([showType], a phone; wider it is the bar's quick chips) → the seven advanced fields
 * (amount range on one row) → processing status → include-inactive last.
 *
 * Money fields use [PosTextFieldType.Price], so the value is normalized through `:platform-money` on blur and
 * still leaves the panel as a plain [String], never a `Double`.
 */
@Composable
fun DocumentFilterPanelFields(
    draft: PosFilterDraft<DocumentSearchFilter>,
    availableTypes: List<String>,
    today: LocalDate,
    showPeriod: Boolean,
    showType: Boolean,
) {
    if (showPeriod) PeriodField(draft, today)
    if (showType) TypeField(draft, availableTypes)
    AdvancedFields(draft)
    StatusField(draft)
    PosFilterSwitchRow(
        label = str(AdminStringKeys.DocumentsIncludeInactive),
        checked = draft.value.includeInactive,
        onCheckedChange = { draft.value = draft.value.copy(includeInactive = it) },
    )
}

/**
 * The five granularity segments (Tag … Zeitraum) and the `‹ label ›` stepper under them. In the
 * Zeitraum segment the label opens the range picker, so a custom range stays reachable on a phone.
 */
@Composable
private fun PeriodField(
    draft: PosFilterDraft<DocumentSearchFilter>,
    today: LocalDate,
) {
    val filter = draft.value
    PosFilterFieldGroup(str(PosPatternKeys.filterPeriod)) {
        PosSegmentedButtonRow(
            options = ReportsGranularity.entries,
            selectedOption = filter.granularity,
            onOptionSelected = { g -> draft.value = draft.value.copy(granularity = g, anchor = minOf(draft.value.anchor, today)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = PosSpacing.xxxxl),
            optionLabel = { str(it.labelKey) },
        )
        PeriodStepperCompact(
            label = filter.periodLabel(LocalAppLanguage.current),
            canStepForward = !filter.granularity.isCurrentPeriod(filter.anchor, today),
            onStepBack = { draft.value = draft.value.copy(anchor = draft.value.granularity.stepped(draft.value.anchor, -1)) },
            onStepForward = { draft.value = draft.value.copy(anchor = draft.value.granularity.stepped(draft.value.anchor, 1)) },
            onPickPeriod = { picked -> draft.value = draft.value.copy(anchor = picked) },
            anchor = filter.anchor,
            granularity = filter.granularity,
            today = today,
            onPickRange = { from, to ->
                draft.value = draft.value.copy(granularity = ReportsGranularity.CUSTOM, customFrom = from, customTo = to)
            },
        )
    }
}

/** Single select: "Alle" plus one chip per type, as the bar had it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypeField(
    draft: PosFilterDraft<DocumentSearchFilter>,
    availableTypes: List<String>,
) {
    val selected = draft.value.fileType
    val language = LocalAppLanguage.current
    PosFilterFieldGroup(str(AdminStringKeys.DocumentsFilterType)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(PosSpacing.md),
            verticalArrangement = Arrangement.spacedBy(PosSpacing.md),
        ) {
            FilterChip(
                selected = selected == null,
                onClick = { draft.value = draft.value.copy(fileType = null) },
                label = { Text(str(AdminStringKeys.DocumentsTypeAll)) },
            )
            availableTypes.forEach { type ->
                FilterChip(
                    selected = selected == type,
                    onClick = { draft.value = draft.value.copy(fileType = type) },
                    label = { Text(documentTypeLabel(type, language)) },
                )
            }
        }
    }
}

@Composable
private fun AdvancedFields(draft: PosFilterDraft<DocumentSearchFilter>) {
    val field: @Composable (DocumentSearchFilter.AdvancedField, Modifier) -> Unit = { advanced, modifier ->
        val isAmount =
            advanced == DocumentSearchFilter.AdvancedField.MIN_AMOUNT ||
                advanced == DocumentSearchFilter.AdvancedField.MAX_AMOUNT
        PosTextField(
            modifier = modifier,
            value = draft.value.fieldValue(advanced).orEmpty(),
            onValueChange = { draft.value = draft.value.withAdvanced(advanced, it) },
            label = str(advancedFieldLabelKey(advanced)),
            textFieldType = if (isAmount) PosTextFieldType.Price else PosTextFieldType.Default,
        )
    }
    DocumentSearchFilter.AdvancedField.entries.forEach { advanced ->
        when (advanced) {
            // A range shares one row (Zenbon FilterPanel rule): Betrag min · Betrag max.
            DocumentSearchFilter.AdvancedField.MIN_AMOUNT ->
                Row(horizontalArrangement = Arrangement.spacedBy(PosSpacing.md)) {
                    field(advanced, Modifier.weight(1f))
                    field(DocumentSearchFilter.AdvancedField.MAX_AMOUNT, Modifier.weight(1f))
                }
            DocumentSearchFilter.AdvancedField.MAX_AMOUNT -> Unit
            else -> field(advanced, Modifier.fillMaxWidth())
        }
    }
}

/** Several statuses may be ticked (E2E 16.9); none means every status. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusField(draft: PosFilterDraft<DocumentSearchFilter>) {
    PosFilterFieldGroup(str(AdminStringKeys.DocumentsFieldProcessingStatus)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(PosSpacing.md),
            verticalArrangement = Arrangement.spacedBy(PosSpacing.md),
        ) {
            DocumentSearchFilter.FILTERABLE_STATUSES.forEach { status ->
                FilterChip(
                    selected = status in draft.value.processingStatuses,
                    onClick = { draft.value = draft.value.withStatusToggled(status) },
                    label = { Text(str(processingStatusLabelKey(status))) },
                )
            }
        }
    }
}
