package org.example.project.ui.screens.settings.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.DatevSettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.formatIsoDateRange
import org.example.project.features.settings.presentation.state.DsfinvkExportState
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosDateField
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.formatMoneyForDisplay

/** [phoneLayout] stacks the two date fields and the two buttons full width (~140dp side by side is too narrow on a phone); the default is the original side-by-side layout. */
@Composable
fun PosDsfinvkExportSection(
    language: Language,
    state: DsfinvkExportState,
    onDateRangeChange: (startDate: String, endDate: String) -> Unit,
    onToggleTseTar: () -> Unit,
    onValidate: () -> Unit,
    onLoadStatistics: () -> Unit,
    onExport: () -> Unit,
    phoneLayout: Boolean = false,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Archive,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = str(DatevSettingsKeys.dsfinvkTitle),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
            }

            // Description
            Text(
                text = str(DatevSettingsKeys.dsfinvkDescription),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Date range inputs: the one date field (typed or picked, UI-language display, ISO state).
            val startField: @Composable (Modifier) -> Unit = { fieldModifier ->
                PosDateField(
                    value = state.startDate,
                    onValueChange = { onDateRangeChange(it, state.endDate) },
                    label = str(DatevSettingsKeys.startDate),
                    modifier = fieldModifier,
                )
            }
            val endField: @Composable (Modifier) -> Unit = { fieldModifier ->
                PosDateField(
                    value = state.endDate,
                    onValueChange = { onDateRangeChange(state.startDate, it) },
                    label = str(DatevSettingsKeys.endDate),
                    modifier = fieldModifier,
                )
            }
            if (phoneLayout) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    startField(Modifier.fillMaxWidth())
                    endField(Modifier.fillMaxWidth())
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    startField(Modifier.weight(1f))
                    endField(Modifier.weight(1f))
                }
            }

            // TSE TAR option
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = state.includeTseTar,
                    onCheckedChange = { onToggleTseTar() },
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = str(DatevSettingsKeys.includeTseTar),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = str(DatevSettingsKeys.includeTseTarDescription),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Action buttons
            val validateButton: @Composable (Modifier) -> Unit = { buttonModifier ->
                PosSecondaryButton(
                    text = str(DatevSettingsKeys.validate),
                    onClick = onValidate,
                    enabled = state.hasValidDateRange() && !state.isOperationInProgress(),
                    isLoading = state.isLoadingValidation,
                    icon = Icons.Default.CheckCircle,
                    modifier = buttonModifier,
                )
            }
            val previewButton: @Composable (Modifier) -> Unit = { buttonModifier ->
                PosSecondaryButton(
                    text = str(DatevSettingsKeys.preview),
                    onClick = onLoadStatistics,
                    enabled = state.hasValidDateRange() && !state.isOperationInProgress(),
                    isLoading = state.isLoadingStatistics,
                    icon = Icons.Default.Refresh,
                    modifier = buttonModifier,
                )
            }
            if (phoneLayout) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    validateButton(Modifier.fillMaxWidth())
                    previewButton(Modifier.fillMaxWidth())
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    validateButton(Modifier.weight(1f))
                    previewButton(Modifier.weight(1f))
                }
            }

            // Validation result
            state.validationResult?.let { validation ->
                DsfinvkValidationResultCard(validation)
            }

            // Statistics preview
            state.statistics?.let { stats ->
                DsfinvkStatisticsPreviewCard(stats)
            }

            // Export button
            PosButton(
                text = str(DatevSettingsKeys.exportZip),
                icon = Icons.Default.Download,
                onClick = onExport,
                enabled = state.canExport(),
                isLoading = state.isExporting,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun DsfinvkValidationResultCard(validation: DsfinvkExportDto.ValidationResultDto) {
    val containerColor =
        when {
            !validation.isValid -> MaterialTheme.colorScheme.errorContainer
            validation.warnings.isNotEmpty() -> MaterialTheme.colorScheme.tertiaryContainer
            else -> MaterialTheme.colorScheme.primaryContainer
        }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = containerColor.copy(alpha = 0.3f),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, containerColor.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Status header
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector =
                        when {
                            !validation.isValid -> Icons.Default.Error
                            validation.warnings.isNotEmpty() -> Icons.Default.Warning
                            else -> Icons.Default.CheckCircle
                        },
                    contentDescription = null,
                    tint =
                        when {
                            !validation.isValid -> MaterialTheme.colorScheme.error
                            validation.warnings.isNotEmpty() -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.primary
                        },
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    text =
                        when {
                            !validation.isValid -> str(DatevSettingsKeys.validationFailed)
                            validation.warnings.isNotEmpty() -> str(DatevSettingsKeys.validationWarnings)
                            else -> str(DatevSettingsKeys.validationPassed)
                        },
                    style = MaterialTheme.typography.titleSmall,
                )
            }

            // Date range
            Text(
                text = "${str(DatevSettingsKeys.dateRange)}: ${validation.dateRange.formatIsoDateRange(LocalAppLanguage.current)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Missing TSE count
            if (validation.missingTseCount > 0) {
                Text(
                    text = "${str(DatevSettingsKeys.missingTseCount)}: ${validation.missingTseCount}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // Unclosed Z-Bon count
            if (validation.unclosedZBonCount > 0) {
                Text(
                    text = "${str(DatevSettingsKeys.unclosedZBonCount)}: ${validation.unclosedZBonCount}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            // Errors
            validation.errors.forEach { error ->
                Text(
                    text = "- ${localizedValidationMessage(error)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // Warnings
            validation.warnings.forEach { warning ->
                Text(
                    text = "- ${localizedValidationMessage(warning)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            // Can export indicator
            if (validation.canExport && !validation.isValid) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = str(DatevSettingsKeys.canExportWithWarnings),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DsfinvkStatisticsPreviewCard(stats: DsfinvkExportDto.ExportStatisticsDto) {
    // The totals are summed in the shop's one booking currency; the payment currencies say which.
    val currency = stats.currenciesIncluded.singleOrNull() ?: DEFAULT_PREVIEW_CURRENCY
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = str(DatevSettingsKeys.exportPreview),
                style = MaterialTheme.typography.titleSmall,
            )

            // Statistics grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                DsfinvkStatisticItem(str(DatevSettingsKeys.zBons), stats.totalZBons.toString())
                DsfinvkStatisticItem(str(DatevSettingsKeys.transactions), stats.totalTransactions.toString())
                DsfinvkStatisticItem(str(DatevSettingsKeys.lineItems), stats.totalLineItems.toString())
            }

            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                DsfinvkStatisticItem(str(DatevSettingsKeys.payments), stats.totalPayments.toString())
                DsfinvkStatisticItem(str(DatevSettingsKeys.tseTransactions), stats.totalTseTransactions.toString())
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // Revenue info
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = str(DatevSettingsKeys.totalRevenue),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = formatMoneyForDisplay(stats.totalRevenue, currency),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = str(DatevSettingsKeys.totalVat),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = formatMoneyForDisplay(stats.totalVatAmount, currency),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            // Currencies
            if (stats.currenciesIncluded.isNotEmpty()) {
                Text(
                    text = "${str(DatevSettingsKeys.currencies)}: ${stats.currenciesIncluded.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DsfinvkStatisticItem(
    label: String,
    value: String,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(4.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val DEFAULT_PREVIEW_CURRENCY = "EUR"

/** The server's English validation sentence in the screen's language; unknown text stays as sent. */
@Composable
private fun localizedValidationMessage(raw: String): String =
    when (val message = DsfinvkValidationMessage.parse(raw)) {
        null -> raw
        DsfinvkValidationMessage.NoZReports -> str(DatevSettingsKeys.msgNoZReports)
        is DsfinvkValidationMessage.MissingVatDetails -> str(DatevSettingsKeys.msgMissingVatDetails, message.bills.toString())
        is DsfinvkValidationMessage.MissingTaxId -> str(DatevSettingsKeys.msgMissingTaxId, message.zNrs)
        is DsfinvkValidationMessage.MissingSignatures -> {
            val parts =
                buildList {
                    if (message.bills > 0) add(str(DatevSettingsKeys.partBills, message.bills.toString()))
                    if (message.orders > 0) add(str(DatevSettingsKeys.partOrders, message.orders.toString()))
                    if (message.cashBookEntries > 0) add(str(DatevSettingsKeys.partCashBookEntries, message.cashBookEntries.toString()))
                    if (message.cancellations > 0) add(str(DatevSettingsKeys.partCancellations, message.cancellations.toString()))
                }
            str(DatevSettingsKeys.msgMissingSignatures, message.total.toString(), parts.joinToString(", "))
        }
    }
