package org.example.project.admin.features.salesinvoices.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.beleg.PosNextStepBar

/**
 * The outgoing "Nächster Schritt" bar: issue the drafts first ("Entwürfe öffnen"), then settle the
 * open invoices ("Zuordnen starten", offered only when [canAssign]). Draws nothing when neither is
 * waiting or the counts are still unknown.
 */
@Composable
fun SalesNextStepBar(
    counts: Map<SalesInvoiceBucket, Long>,
    onOpenDrafts: () -> Unit,
    onAssign: () -> Unit,
    canAssign: Boolean,
    modifier: Modifier = Modifier,
) {
    val step = SalesFlow.nextStep(counts) ?: return
    val issue =
        if (step.drafts == 1L) str(AdminStringKeys.BelegflowPhraseIssueOne, step.drafts) else str(AdminStringKeys.BelegflowPhraseIssue, step.drafts)
    val assign =
        if (step.open == 1L) str(AdminStringKeys.BelegflowPhraseAssignOne, step.open) else str(AdminStringKeys.BelegflowPhraseAssign, step.open)
    val draftsFirst = step.drafts > 0L
    val text =
        when {
            draftsFirst && step.open > 0L -> str(AdminStringKeys.BelegflowNextThen, issue, assign)
            draftsFirst -> str(AdminStringKeys.BelegflowNextOne, issue)
            else -> str(AdminStringKeys.BelegflowNextOne, assign)
        }
    val actionable = draftsFirst || canAssign
    PosNextStepBar(
        text = text,
        actionLabel = if (!actionable) null else str(if (draftsFirst) AdminStringKeys.BelegflowActionOpenDrafts else AdminStringKeys.BelegflowActionAssign),
        onAction = { if (draftsFirst) onOpenDrafts() else onAssign() },
        modifier = modifier,
    )
}
