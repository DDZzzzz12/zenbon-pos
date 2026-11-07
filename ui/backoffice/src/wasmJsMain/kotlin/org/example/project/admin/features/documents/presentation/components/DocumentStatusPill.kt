package org.example.project.admin.features.documents.presentation.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.enums.document.DocumentType
import org.example.project.core.domain.enums.document.ProcessingStatus
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.beleg.BELEG_PROGRESS_STEPS
import org.example.project.ui.components.beleg.BelegTone
import org.example.project.ui.components.beleg.PosRowProgress
import org.example.project.ui.components.beleg.PosStatusDot
import org.example.project.ui.theme.PosSpacing

/**
 * The label key for a document's workflow state. Kept pure — non-`@Composable`, returning a
 * [StringKey] — so a missing mapping is a test failure rather than a blank cell, exactly like
 * [advancedFieldLabelKey].
 *
 * This is the single mapping: [DocumentInfoCard] used to carry a private `@Composable` copy that no
 * test could reach, and the work-queue status column would have made it two.
 */
fun processingStatusLabelKey(
    status: ProcessingStatus,
    fileType: String? = null,
): StringKey =
    when (status) {
        ProcessingStatus.NONE -> AdminStringKeys.DocumentProcessingStatusNone
        ProcessingStatus.PENDING -> AdminStringKeys.DocumentProcessingStatusPending
        ProcessingStatus.PROCESSING -> AdminStringKeys.DocumentProcessingStatusProcessing
        // "Read from e-invoice" is only true of invoices; a parsed bank statement was not an e-invoice.
        ProcessingStatus.COMPLETED ->
            if (fileType == DocumentType.BANK_STATEMENT.name) {
                AdminStringKeys.DocumentProcessingStatusCompletedStatement
            } else {
                AdminStringKeys.DocumentProcessingStatusCompleted
            }
        ProcessingStatus.FAILED -> AdminStringKeys.DocumentProcessingStatusFailed
        ProcessingStatus.UNPROCESSABLE -> AdminStringKeys.DocumentProcessingStatusUnprocessable
        ProcessingStatus.IN_PROGRESS -> AdminStringKeys.DocumentProcessingStatusInProgress
        ProcessingStatus.REVIEWED -> AdminStringKeys.DocumentProcessingStatusReviewed
    }

/**
 * A small rounded label for a document's workflow state, mirroring [DocumentTypePill]'s shape so the
 * two read as one row of metadata.
 *
 * Colour carries the queue meaning, not decoration: the three "needs entry" states are the error
 * container (something is waiting on a human), `COMPLETED`/`IN_PROGRESS` the tertiary container
 * (in flight), `REVIEWED` the primary container (done), and the two vestigial queue states the
 * neutral surface variant. The label is always present, so colour is never the only signal.
 */
@Composable
fun DocumentStatusPill(
    status: ProcessingStatus,
    modifier: Modifier = Modifier,
    fileType: String? = null,
) {
    val container: Color
    val content: Color
    when (status) {
        ProcessingStatus.NONE,
        ProcessingStatus.UNPROCESSABLE,
        ProcessingStatus.FAILED,
        -> {
            container = MaterialTheme.colorScheme.errorContainer
            content = MaterialTheme.colorScheme.onErrorContainer
        }
        ProcessingStatus.COMPLETED,
        ProcessingStatus.IN_PROGRESS,
        -> {
            container = MaterialTheme.colorScheme.tertiaryContainer
            content = MaterialTheme.colorScheme.onTertiaryContainer
        }
        ProcessingStatus.REVIEWED -> {
            container = MaterialTheme.colorScheme.primaryContainer
            content = MaterialTheme.colorScheme.onPrimaryContainer
        }
        ProcessingStatus.PENDING,
        ProcessingStatus.PROCESSING,
        -> {
            container = MaterialTheme.colorScheme.surfaceVariant
            content = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = container,
    ) {
        Text(
            text = str(processingStatusLabelKey(status, fileType)),
            style = MaterialTheme.typography.labelMedium,
            color = content,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = PosSpacing.md, vertical = 4.dp),
        )
    }
}

/**
 * The Belegfluss colour of a workflow state: grey while nothing has been read, blue while a human
 * still has to look (read from the e-invoice, or being worked on), green once reviewed, red when
 * reading failed. Pure, so a new [ProcessingStatus] forces a decision here.
 */
fun processingStatusTone(status: ProcessingStatus): BelegTone =
    when (status) {
        ProcessingStatus.NONE,
        ProcessingStatus.PENDING,
        ProcessingStatus.PROCESSING,
        -> BelegTone.Neutral
        ProcessingStatus.IN_PROGRESS,
        ProcessingStatus.COMPLETED,
        -> BelegTone.Info
        ProcessingStatus.REVIEWED -> BelegTone.Success
        ProcessingStatus.FAILED,
        ProcessingStatus.UNPROCESSABLE,
        -> BelegTone.Danger
    }

/** The status of a list row as dot + word — the same words as [DocumentStatusPill], in the Belegfluss form. */
@Composable
fun DocumentStatusDot(
    status: ProcessingStatus,
    modifier: Modifier = Modifier,
    fileType: String? = null,
) {
    PosStatusDot(
        label = str(processingStatusLabelKey(status, fileType)),
        tone = processingStatusTone(status),
        modifier = modifier,
        // Long words ("Nicht automatisch lesbar") wrap in the narrow column instead of being cut off.
        wrap = true,
    )
}

/**
 * How far an invoice document has come, 1..4 (Erfassen, Prüfen, Zuordnen, Erledigt), from facts only:
 * no stored invoice = 1 (whatever the status says); an invoice not yet reviewed = 2; reviewed = 3; reviewed
 * and really linked ([realLinks], see `DocumentFacts`) = 4. Null for every other document type — a Z-Bon or
 * a contract does not go through the invoice flow, so it gets no squares rather than misleading ones.
 */
fun documentFlowSteps(
    status: ProcessingStatus,
    hasInvoice: Boolean,
    realLinks: Int,
    fileType: String?,
): Int? {
    if (fileType != DocumentType.INVOICE.name) return null
    return when {
        !hasInvoice -> 1
        status != ProcessingStatus.REVIEWED -> 2
        realLinks > 0 -> 4
        else -> 3
    }
}

/** The four squares of [documentFlowSteps], or nothing for a document outside the invoice flow. */
@Composable
fun DocumentFlowProgress(
    status: ProcessingStatus,
    hasInvoice: Boolean,
    realLinks: Int,
    fileType: String?,
    modifier: Modifier = Modifier,
) {
    val steps = documentFlowSteps(status, hasInvoice, realLinks, fileType) ?: return
    PosRowProgress(
        steps = steps,
        description = str(AdminStringKeys.BelegflowProgressStep, steps, BELEG_PROGRESS_STEPS),
        modifier = modifier,
    )
}
