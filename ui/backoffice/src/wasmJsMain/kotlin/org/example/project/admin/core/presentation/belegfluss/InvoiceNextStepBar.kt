package org.example.project.admin.core.presentation.belegfluss

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.beleg.PosNextStepBar

/**
 * The "Nächster Schritt" bar of the incoming Belegfluss. Draws nothing while the counts are unknown
 * or when no stage has work waiting.
 *
 * [canOpen] says whether the action for a stage may be offered on the host screen (the screen it
 * would lead to may be this very one, or one the principal may not read); a stage that cannot be
 * opened leaves the bar as a sentence without the button.
 */
@Composable
fun InvoiceNextStepBar(
    counts: InvoiceFlowCounts,
    onOpen: (InvoiceFlowStage) -> Unit,
    modifier: Modifier = Modifier,
    canOpen: (InvoiceFlowStage) -> Boolean = { true },
) {
    val step = InvoiceFlow.nextStep(counts) ?: return
    val first = phrase(step.first, step.firstCount)
    val text =
        step.then?.let { then -> str(AdminStringKeys.BelegflowNextThen, first, phrase(then, step.thenCount)) }
            ?: str(AdminStringKeys.BelegflowNextOne, first)
    PosNextStepBar(
        text = text,
        actionLabel = if (canOpen(step.first)) str(actionKey(step.first)) else null,
        onAction = { onOpen(step.first) },
        modifier = modifier,
    )
}

@Composable
private fun phrase(
    stage: InvoiceFlowStage,
    count: Long,
): String {
    val one = count == 1L
    val key =
        when (stage) {
            InvoiceFlowStage.CAPTURE -> if (one) AdminStringKeys.BelegflowPhraseCaptureOne else AdminStringKeys.BelegflowPhraseCapture
            InvoiceFlowStage.REVIEW -> if (one) AdminStringKeys.BelegflowPhraseReviewOne else AdminStringKeys.BelegflowPhraseReview
            InvoiceFlowStage.ASSIGN -> if (one) AdminStringKeys.BelegflowPhraseAssignOne else AdminStringKeys.BelegflowPhraseAssign
            // Never a next step (nothing is left to do once done), so no phrase of its own.
            InvoiceFlowStage.DONE -> AdminStringKeys.BelegflowPhraseAssign
        }
    return str(key, count)
}

private fun actionKey(stage: InvoiceFlowStage): StringKey =
    when (stage) {
        InvoiceFlowStage.CAPTURE -> AdminStringKeys.BelegflowActionCapture
        InvoiceFlowStage.REVIEW -> AdminStringKeys.BelegflowActionReview
        InvoiceFlowStage.ASSIGN, InvoiceFlowStage.DONE -> AdminStringKeys.BelegflowActionAssign
    }
