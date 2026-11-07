package org.example.project.admin.core.presentation.belegfluss

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.document.InvoiceListDto.Bucket
import org.example.project.core.domain.enums.document.ProcessingStatus
import org.example.project.ui.components.beleg.BELEG_PROGRESS_STEPS
import org.example.project.ui.components.beleg.BelegTone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InvoiceFlowTest {
    private fun counts(vararg pairs: Pair<Bucket, Long>) = InvoiceFlowCounts(counts = pairs.associate { (b, n) -> b.name to n })

    @Test
    fun theThreePaymentBucketsBelongToTheAssignStage() {
        assertEquals(InvoiceFlowStage.CAPTURE, InvoiceFlow.stageOf(Bucket.ZU_ERFASSEN))
        assertEquals(InvoiceFlowStage.REVIEW, InvoiceFlow.stageOf(Bucket.ZU_PRUEFEN))
        listOf(Bucket.OFFEN, Bucket.UEBERFAELLIG, Bucket.UEBERZAHLT).forEach {
            assertEquals(InvoiceFlowStage.ASSIGN, InvoiceFlow.stageOf(it), it.name)
        }
        assertEquals(InvoiceFlowStage.DONE, InvoiceFlow.stageOf(Bucket.FERTIG))
    }

    @Test
    fun everyStageOpensABucketThatBelongsToIt() {
        InvoiceFlowStage.entries.forEach { assertEquals(it, InvoiceFlow.stageOf(it.bucket)) }
    }

    @Test
    fun rowProgressCountsTheStageFromOneAndNeverExceedsTheSquares() {
        assertEquals(1, InvoiceFlow.stepsOf(Bucket.ZU_ERFASSEN))
        assertEquals(2, InvoiceFlow.stepsOf(Bucket.ZU_PRUEFEN))
        assertEquals(3, InvoiceFlow.stepsOf(Bucket.UEBERFAELLIG))
        assertEquals(BELEG_PROGRESS_STEPS, InvoiceFlow.stepsOf(Bucket.FERTIG))
        Bucket.entries.forEach { assertTrue(InvoiceFlow.stepsOf(it) in 1..BELEG_PROGRESS_STEPS, it.name) }
    }

    @Test
    fun statusColoursFollowTheZenbonWords() {
        assertEquals(BelegTone.Neutral, InvoiceFlow.toneOf(Bucket.ZU_ERFASSEN))
        assertEquals(BelegTone.Info, InvoiceFlow.toneOf(Bucket.ZU_PRUEFEN))
        assertEquals(BelegTone.Warning, InvoiceFlow.toneOf(Bucket.OFFEN))
        assertEquals(BelegTone.Danger, InvoiceFlow.toneOf(Bucket.UEBERFAELLIG))
        assertEquals(BelegTone.Success, InvoiceFlow.toneOf(Bucket.FERTIG))
    }

    @Test
    fun theFinishedStageIsCalledErledigtNotFertig() {
        assertEquals(AdminStringKeys.BelegflowStageDone, InvoiceFlow.statusLabelKey(Bucket.FERTIG))
        assertEquals(AdminStringKeys.InvoicesBucketOverdue, InvoiceFlow.statusLabelKey(Bucket.UEBERFAELLIG))
    }

    @Test
    fun theNextStepIsTheEarliestStageWithWorkThenTheOneAfterIt() {
        val step = InvoiceFlow.nextStep(counts(Bucket.ZU_ERFASSEN to 0, Bucket.ZU_PRUEFEN to 5, Bucket.OFFEN to 14, Bucket.FERTIG to 63))
        assertEquals(InvoiceNextStep(InvoiceFlowStage.REVIEW, 5, InvoiceFlowStage.ASSIGN, 14), step)
    }

    @Test
    fun theNextStepHasNoSecondPartWhenOnlyOneStageHasWork() {
        val step = InvoiceFlow.nextStep(counts(Bucket.ZU_ERFASSEN to 0, Bucket.ZU_PRUEFEN to 0, Bucket.OFFEN to 3))
        assertEquals(InvoiceNextStep(InvoiceFlowStage.ASSIGN, 3, null, 0), step)
    }

    @Test
    fun finishedWorkAloneProposesNothing() {
        assertNull(InvoiceFlow.nextStep(counts(Bucket.FERTIG to 63, Bucket.ZU_PRUEFEN to 0)))
    }

    @Test
    fun unloadedCountsProposeNothingRatherThanClaimingAllIsDone() {
        assertNull(InvoiceFlow.nextStep(InvoiceFlowCounts()))
        assertNull(InvoiceFlowCounts().countOf(InvoiceFlowStage.CAPTURE))
    }

    @Test
    fun aScanRowOffersCaptureOnlyToAPrincipalWhoMayCapture() {
        assertEquals(InvoiceRowAction.CAPTURE, InvoiceFlow.rowAction(Bucket.ZU_ERFASSEN, mayCapture = true, mayAssign = true))
        assertNull(InvoiceFlow.rowAction(Bucket.ZU_ERFASSEN, mayCapture = false, mayAssign = true))
    }

    @Test
    fun theRowActionFollowsTheRowsState() {
        assertEquals(InvoiceRowAction.REVIEW, InvoiceFlow.rowAction(Bucket.ZU_PRUEFEN, mayCapture = false, mayAssign = false))
        assertEquals(InvoiceRowAction.ASSIGN, InvoiceFlow.rowAction(Bucket.UEBERFAELLIG, mayCapture = false, mayAssign = true))
        assertEquals(InvoiceRowAction.OPEN, InvoiceFlow.rowAction(Bucket.FERTIG, mayCapture = true, mayAssign = true))
    }

    @Test
    fun anOpenRowFallsBackToOpenWithoutTheZuordnungQueue() {
        assertEquals(InvoiceRowAction.OPEN, InvoiceFlow.rowAction(Bucket.OFFEN, mayCapture = true, mayAssign = false))
    }

    @Test
    fun theCaptureFormStandsOnTheStageOfItsDocument() {
        assertEquals(InvoiceFlowStage.CAPTURE, InvoiceFlow.stageForCapture(ProcessingStatus.NONE))
        assertEquals(InvoiceFlowStage.CAPTURE, InvoiceFlow.stageForCapture(ProcessingStatus.FAILED))
        assertEquals(InvoiceFlowStage.REVIEW, InvoiceFlow.stageForCapture(ProcessingStatus.COMPLETED))
        assertEquals(InvoiceFlowStage.DONE, InvoiceFlow.stageForCapture(ProcessingStatus.REVIEWED))
        assertEquals(InvoiceFlowStage.REVIEW, InvoiceFlow.stageForCapture(null), "an invoice without a document is being checked")
    }
}
