package org.example.project.admin.core.presentation.belegfluss

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.beleg.BelegFlowStrip
import org.example.project.ui.components.beleg.BelegStage
import org.example.project.ui.components.formatMoneyForDisplay

/**
 * The incoming Belegfluss strip — Erfassen · Prüfen · Zuordnen · Erledigt with live counts — used
 * by Rechnungen, Zuordnung and Dokumente.
 *
 * [selected] is the stage the host screen stands on (null: none, e.g. Dokumente); [onSelect] decides
 * whether that filters in place (Rechnungen) or navigates (the other two).
 */
@Composable
fun InvoiceFlowStrip(
    counts: InvoiceFlowCounts,
    selected: InvoiceFlowStage?,
    onSelect: (InvoiceFlowStage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val stages =
        InvoiceFlowStage.entries.map { stage ->
            BelegStage(
                id = stage.name,
                label = str(stage.labelKey()),
                count = counts.countOf(stage),
                icon = stage.icon(),
                sub = stageSub(stage, counts),
                selected = stage == selected,
            )
        }
    BelegFlowStrip(
        stages = stages,
        onSelect = { id -> onSelect(InvoiceFlowStage.valueOf(id)) },
        groupDescription = str(AdminStringKeys.BelegflowStripLabel),
        modifier = modifier,
    )
}

@Composable
private fun stageSub(
    stage: InvoiceFlowStage,
    counts: InvoiceFlowCounts,
): String? =
    when (stage) {
        InvoiceFlowStage.CAPTURE -> str(AdminStringKeys.BelegflowStageCaptureSub)
        InvoiceFlowStage.REVIEW -> str(AdminStringKeys.BelegflowStageReviewSub)
        InvoiceFlowStage.ASSIGN ->
            counts.sumOf(stage.bucket)?.let { str(AdminStringKeys.BelegflowStageAssignSub, formatMoneyForDisplay(it, counts.currency)) }
        InvoiceFlowStage.DONE -> str(AdminStringKeys.BelegflowStageDoneSub)
    }

/** The stage's name, shared by the strip and the row actions. */
fun InvoiceFlowStage.labelKey(): StringKey =
    when (this) {
        InvoiceFlowStage.CAPTURE -> AdminStringKeys.BelegflowStageCapture
        InvoiceFlowStage.REVIEW -> AdminStringKeys.BelegflowStageReview
        InvoiceFlowStage.ASSIGN -> AdminStringKeys.BelegflowStageAssign
        InvoiceFlowStage.DONE -> AdminStringKeys.BelegflowStageDone
    }

private fun InvoiceFlowStage.icon(): ImageVector =
    when (this) {
        InvoiceFlowStage.CAPTURE -> Icons.Filled.DocumentScanner
        InvoiceFlowStage.REVIEW -> Icons.AutoMirrored.Filled.FactCheck
        InvoiceFlowStage.ASSIGN -> Icons.Filled.CompareArrows
        InvoiceFlowStage.DONE -> Icons.Filled.TaskAlt
    }
