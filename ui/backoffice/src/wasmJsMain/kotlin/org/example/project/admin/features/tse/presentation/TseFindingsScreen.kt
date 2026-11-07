package org.example.project.admin.features.tse.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.components.AdminListEmptyState
import org.example.project.admin.core.presentation.components.AdminListScaffold
import org.example.project.admin.core.presentation.components.AdminListScaffoldPadding
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.tse.TseExplanationDto
import org.example.project.core.domain.enums.tse.TseFindingKind
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosStatusChip
import org.example.project.ui.layout.PaneBreakpoints
import org.koin.compose.viewmodel.koinViewModel

/** How much of a 64-character serial is enough to tell two devices apart on screen. */
private const val SERIAL_PREFIX = 12

/** Below this card width the status chip and the button leave the text column too little room. */
private val FINDING_STACK_BELOW: Dp = 520.dp

/**
 * TSE-Befunde — the TSE transaction numbers our own till data cannot account for, each with the
 * explanation that currently covers it (AEAO § 146a Nr. 1.15.1).
 *
 * **These are the POS-side findings.** The TSE screen's "Export prüfen" produces findings of its
 * own, from an export file, and answers a different question: a transaction number issued before
 * this device's first POS row is a finding there and no gap here. The two may legitimately
 * disagree, so they are shown on separate screens and are never merged into one list.
 */
@Composable
fun TseFindingsScreen() {
    val viewModel: TseFindingsViewModel = koinViewModel()
    val state = viewModel.state

    // LaunchedEffect, not VM init: adminApp VMs share one ViewModelStoreOwner and survive logout.
    LaunchedEffect(Unit) { viewModel.load() }

    PosErrorWatcher(
        error = state.error,
        title = str(AdminStringKeys.TseFindingsTitle),
        onClear = viewModel::clearError,
        retryAction = viewModel::load,
    )

    var explainTarget by remember { mutableStateOf<TseExplanationDto.FindingDto?>(null) }

    // The dialog closes only once the server accepted — keyed on the ViewModel's acceptance count,
    // which only a successful save changes. A refusal of the CONTENT — client-side or a server 400 —
    // lands in explainError instead, so the dialog stays with the message next to the input and the
    // operator's text survives. Keying on an isBusy flip missed fast replies (E2E round 4, R4-7).
    val acceptedAtOpen = remember(explainTarget) { state.explainAcceptedCount }
    LaunchedEffect(state.explainAcceptedCount) {
        if (explainTarget != null && state.explainAcceptedCount != acceptedAtOpen) explainTarget = null
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val phone = maxWidth - AdminListScaffoldPadding * 2 < PaneBreakpoints.PHONE_MAX
        val exportButton: @Composable () -> Unit = {
            PosSecondaryButton(
                text = str(AdminStringKeys.TseFindingsExportCsv),
                onClick = viewModel::exportCsv,
                enabled = state.explanations.isNotEmpty(),
                modifier = if (phone) Modifier.fillMaxWidth() else Modifier,
            )
        }
        AdminListScaffold(
            title = str(AdminStringKeys.TseFindingsTitle),
            actions =
                if (phone) {
                    null
                } else {
                    { exportButton() }
                },
            toolbar = if (phone) exportButton else null,
            isLoading = state.isLoading,
            isEmpty = state.findings.isEmpty() && !state.isLoading,
            empty = {
                AdminListEmptyState(
                    message = str(AdminStringKeys.TseFindingsEmpty),
                    icon = Icons.Default.Security,
                )
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.findings.forEach { finding ->
                    FindingRow(
                        finding = finding,
                        explanation = state.explanationFor(finding),
                        actionsEnabled = !state.isBusy,
                        onExplain = { explainTarget = finding },
                    )
                }
            }
        }
    }

    explainTarget?.let { finding ->
        ExplainFindingDialog(
            finding = finding,
            current = state.explanationFor(finding),
            explainError = state.explainError,
            isBusy = state.isBusy,
            onSubmit = { category, text -> viewModel.explain(finding, category, text) },
            onDismiss = {
                explainTarget = null
                viewModel.clearExplainError()
            },
        )
    }
}

@Composable
private fun FindingRow(
    finding: TseExplanationDto.FindingDto,
    explanation: TseExplanationDto.ExplanationDto?,
    actionsEnabled: Boolean,
    onExplain: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < FINDING_STACK_BELOW
        PosCard(modifier = Modifier.fillMaxWidth()) {
            body {
                if (stacked) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        FindingText(finding, explanation, Modifier.fillMaxWidth())
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            ExplainedChip(isExplained = explanation != null)
                            PosButton(
                                text = str(AdminStringKeys.TseFindingsExplain),
                                onClick = onExplain,
                                enabled = actionsEnabled,
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        FindingText(finding, explanation, Modifier.weight(1f))
                        ExplainedChip(isExplained = explanation != null)
                        PosButton(
                            text = str(AdminStringKeys.TseFindingsExplain),
                            onClick = onExplain,
                            enabled = actionsEnabled,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FindingText(
    finding: TseExplanationDto.FindingDto,
    explanation: TseExplanationDto.ExplanationDto?,
    modifier: Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = findingLabel(finding),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = finding.tseSerialHex.take(SERIAL_PREFIX) + "…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        explanation?.let { entry ->
            Text(
                text = entry.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ExplainedChip(isExplained: Boolean) {
    if (isExplained) {
        PosStatusChip(
            text = str(AdminStringKeys.TseFindingsExplained),
            backgroundColor = MaterialTheme.colorScheme.secondaryContainer,
            textColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        return
    }
    PosStatusChip(
        text = str(AdminStringKeys.TseFindingsUnexplained),
        backgroundColor = MaterialTheme.colorScheme.errorContainer,
        textColor = MaterialTheme.colorScheme.onErrorContainer,
    )
}

/**
 * A duplicate is one transaction number recorded twice, so it names a single TANR; a gap names the
 * range the device issued and the POS never recorded.
 */
@Composable
internal fun findingLabel(finding: TseExplanationDto.FindingDto): String =
    when (finding.kind) {
        TseFindingKind.DUPLICATE -> str(AdminStringKeys.TseFindingsDuplicate, finding.tanrFrom)
        TseFindingKind.GAP ->
            if (finding.tanrFrom == finding.tanrTo) {
                str(AdminStringKeys.TseFindingsGapSingle, finding.tanrFrom)
            } else {
                str(AdminStringKeys.TseFindingsGap, finding.tanrFrom, finding.tanrTo)
            }
    }
