package org.example.project.admin.core.presentation.belegfluss

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.document.InvoiceListDto
import org.example.project.core.domain.enums.document.ProcessingStatus
import org.example.project.core.presentation.strings.StringKey
import org.example.project.ui.components.beleg.BELEG_PROGRESS_STEPS
import org.example.project.ui.components.beleg.BelegTone

/**
 * The pure rules of the incoming Belegfluss: which stage a bucket belongs to, how far along a row
 * is, what colour its status takes and what to do next. Kept free of Compose so every rule is pinned
 * by a test.
 */
object InvoiceFlow {
    /** The sub-filters of the Zuordnen stage, in chip order. */
    val ASSIGN_BUCKETS =
        listOf(
            InvoiceListDto.Bucket.OFFEN,
            InvoiceListDto.Bucket.UEBERFAELLIG,
            InvoiceListDto.Bucket.UEBERZAHLT,
        )

    fun stageOf(bucket: InvoiceListDto.Bucket): InvoiceFlowStage =
        when (bucket) {
            InvoiceListDto.Bucket.ZU_ERFASSEN -> InvoiceFlowStage.CAPTURE
            InvoiceListDto.Bucket.ZU_PRUEFEN -> InvoiceFlowStage.REVIEW
            InvoiceListDto.Bucket.OFFEN,
            InvoiceListDto.Bucket.UEBERFAELLIG,
            InvoiceListDto.Bucket.UEBERZAHLT,
            -> InvoiceFlowStage.ASSIGN
            InvoiceListDto.Bucket.FERTIG -> InvoiceFlowStage.DONE
        }

    /** Filled squares of the row progress, 1..[BELEG_PROGRESS_STEPS]: the row's stage, counted from one. */
    fun stepsOf(bucket: InvoiceListDto.Bucket): Int = stageOf(bucket).ordinal + 1

    fun toneOf(bucket: InvoiceListDto.Bucket): BelegTone =
        when (bucket) {
            InvoiceListDto.Bucket.ZU_ERFASSEN -> BelegTone.Neutral
            InvoiceListDto.Bucket.ZU_PRUEFEN -> BelegTone.Info
            InvoiceListDto.Bucket.OFFEN,
            InvoiceListDto.Bucket.UEBERZAHLT,
            -> BelegTone.Warning
            InvoiceListDto.Bucket.UEBERFAELLIG -> BelegTone.Danger
            InvoiceListDto.Bucket.FERTIG -> BelegTone.Success
        }

    /** The status word of a row: the existing bucket names, with *Erledigt* for the finished stage. */
    fun statusLabelKey(bucket: InvoiceListDto.Bucket): StringKey =
        when (bucket) {
            InvoiceListDto.Bucket.ZU_ERFASSEN -> AdminStringKeys.InvoicesBucketToCapture
            InvoiceListDto.Bucket.ZU_PRUEFEN -> AdminStringKeys.InvoicesBucketToReview
            InvoiceListDto.Bucket.OFFEN -> AdminStringKeys.InvoicesBucketOpen
            InvoiceListDto.Bucket.UEBERFAELLIG -> AdminStringKeys.InvoicesBucketOverdue
            InvoiceListDto.Bucket.UEBERZAHLT -> AdminStringKeys.InvoicesBucketOverpaid
            InvoiceListDto.Bucket.FERTIG -> AdminStringKeys.BelegflowStageDone
        }

    /**
     * The proposal for the next-step bar: the first stage (capture, review, assign — in that order)
     * that has work waiting, then the second such stage. Null when nothing is waiting or the counts
     * are not loaded yet.
     */
    fun nextStep(counts: InvoiceFlowCounts): InvoiceNextStep? {
        if (!counts.isLoaded) return null
        val waiting =
            listOf(InvoiceFlowStage.CAPTURE, InvoiceFlowStage.REVIEW, InvoiceFlowStage.ASSIGN)
                .map { it to counts.countOf(it.bucket) }
                .filter { it.second > 0L }
        val first = waiting.firstOrNull() ?: return null
        val then = waiting.getOrNull(1)
        return InvoiceNextStep(first.first, first.second, then?.first, then?.second ?: 0L)
    }

    /**
     * The row's primary action, or null when the principal may be offered none.
     *
     * A row still to be captured only has the capture form behind it, which would open disabled
     * without the capture rights, so there the action is withheld rather than offered (FO-03). An
     * open row offers *Zuordnen* only to a principal who may read the Zuordnung queue; otherwise it
     * falls back to opening the invoice, which every reader may do.
     */
    fun rowAction(
        bucket: InvoiceListDto.Bucket,
        mayCapture: Boolean,
        mayAssign: Boolean,
    ): InvoiceRowAction? =
        when (stageOf(bucket)) {
            InvoiceFlowStage.CAPTURE -> InvoiceRowAction.CAPTURE.takeIf { mayCapture }
            InvoiceFlowStage.REVIEW -> InvoiceRowAction.REVIEW
            InvoiceFlowStage.ASSIGN -> if (mayAssign) InvoiceRowAction.ASSIGN else InvoiceRowAction.OPEN
            InvoiceFlowStage.DONE -> InvoiceRowAction.OPEN
        }

    /**
     * The stage a document stands on while its capture form is open: a document nothing has been read
     * from (or whose reading failed) is still to be captured, one that was read or is being worked on
     * is being checked, a reviewed one is done. An invoice without a document counts as being checked.
     */
    fun stageForCapture(status: ProcessingStatus?): InvoiceFlowStage =
        when (status) {
            ProcessingStatus.NONE,
            ProcessingStatus.PENDING,
            ProcessingStatus.PROCESSING,
            ProcessingStatus.FAILED,
            ProcessingStatus.UNPROCESSABLE,
            -> InvoiceFlowStage.CAPTURE
            ProcessingStatus.REVIEWED -> InvoiceFlowStage.DONE
            ProcessingStatus.COMPLETED,
            ProcessingStatus.IN_PROGRESS,
            null,
            -> InvoiceFlowStage.REVIEW
        }
}
