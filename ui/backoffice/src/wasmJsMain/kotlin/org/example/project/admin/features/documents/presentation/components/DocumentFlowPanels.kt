package org.example.project.admin.features.documents.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.documents.presentation.DocumentFacts
import org.example.project.admin.features.documents.presentation.DocumentHistoryStep
import org.example.project.admin.features.documents.presentation.EntryCardMode
import org.example.project.admin.features.documents.presentation.documentHistory
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.enums.document.ProcessingStatus
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.formatShortDate
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.components.beleg.PosFlowStep
import org.example.project.ui.components.beleg.PosFlowTimeline
import org.example.project.ui.theme.PosSpacing

/**
 * The entry card of an invoice document: what to do with it next and the one button that does it.
 *
 * The mode comes from the same facts as the Verlauf ([DocumentFacts.entryCardMode]): no stored invoice is
 * offered for *entry* (opens the capture form of the document), a stored invoice not yet reviewed for
 * *checking* (opens that invoice, the target "Zur Rechnung" uses), a reviewed one gets no card.
 * Shown only above `InvoicesAccess.mayCapture`; reading a document is open to any authenticated user.
 */
@Composable
fun InvoiceEntryCard(
    document: DocumentDto.DocumentResponseDto?,
    onCapture: (Long) -> Unit,
    onOpenInvoice: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val mode = document?.let { DocumentFacts.entryCardMode(it.processingStatus, DocumentFacts.hasInvoice(it)) } ?: EntryCardMode.CAPTURE
    if (mode == EntryCardMode.NONE) return
    val verify = mode == EntryCardMode.VERIFY
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primaryContainer)
                .border(1.dp, MaterialTheme.colorScheme.primary)
                .padding(PosSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(PosSpacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PosSpacing.md)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.FactCheck,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = str(if (verify) AdminStringKeys.BelegflowEntryVerifyTitle else AdminStringKeys.BelegflowEntryCaptureTitle),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Text(
            text = str(if (verify) AdminStringKeys.BelegflowEntryVerifyText else AdminStringKeys.BelegflowEntryCaptureText),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PosButton(
            text = str(if (verify) AdminStringKeys.BelegflowEntryVerifyAction else AdminStringKeys.InvoiceCaptureTitle),
            icon = Icons.AutoMirrored.Filled.ArrowForward,
            onClick = {
                val invoiceId = document?.invoiceId
                if (verify && invoiceId != null) onOpenInvoice(invoiceId) else document?.id?.let(onCapture)
            },
            enabled = document != null,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The Beleg-Verlauf of an invoice document (Hochgeladen, Erfasst, Geprüft, Verknüpft) with a link to
 * its invoice. Zugeordnet and Gebucht are not drawn: the document row does not say whether a payment
 * was allocated, and an unknown must not look like a "not yet".
 */
@Composable
fun DocumentHistoryCard(
    document: DocumentDto.DocumentResponseDto,
    onOpenInvoice: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val language = LocalAppLanguage.current
    val realLinks = DocumentFacts.realLinkCount(document)
    val steps =
        documentHistory(document.processingStatus, DocumentFacts.hasInvoice(document), realLinks).map { entry ->
            val detail =
                when (entry.step) {
                    DocumentHistoryStep.UPLOADED ->
                        str(AdminStringKeys.BelegflowHistoryUploadedDetail, document.uploadedAt.formatShortDate(language), document.uploadedBy)
                    DocumentHistoryStep.CAPTURED ->
                        if (entry.done && document.processingStatus == ProcessingStatus.COMPLETED) str(AdminStringKeys.BelegflowHistoryReadAuto) else null
                    DocumentHistoryStep.REVIEWED -> if (entry.current) str(AdminStringKeys.BelegflowStageReviewSub) else null
                    DocumentHistoryStep.LINKED ->
                        if (entry.done) str(AdminStringKeys.BelegflowHistoryLinkedDetail, realLinks) else null
                }
            PosFlowStep(label = str(entry.step.labelKey), done = entry.done, detail = detail, current = entry.current)
        }
    val invoiceId = document.invoiceId
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                .padding(PosSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(PosSpacing.lg),
    ) {
        Text(
            text = str(AdminStringKeys.BelegflowHistoryDocumentTitle),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PosFlowTimeline(steps)
        if (invoiceId != null) {
            PosTextButton(
                text = str(AdminStringKeys.BelegflowHistoryToInvoice),
                icon = Icons.Filled.Link,
                onClick = { onOpenInvoice(invoiceId) },
            )
        }
    }
}
