package org.example.project.admin.features.documents.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.components.adminTouchSize
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.documents.presentation.DocumentUploadSection
import org.example.project.admin.features.documents.presentation.DocumentUploadViewModel
import org.example.project.admin.features.documents.presentation.UploadOutcome
import org.example.project.core.domain.enums.document.DocumentType
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosMoneyText
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.theme.LocalPosColors
import org.example.project.ui.theme.PosSpacing
import org.koin.compose.viewmodel.koinViewModel

/** PDF, image, or a bare XRechnung. The server sniffs the bytes; this only filters the file dialog. */
private const val BELEG_UPLOAD_ACCEPT = ".pdf,.jpg,.jpeg,.png,.xml,application/pdf,image/*,application/xml"

/** Width of the coloured rail down the left of an outcome panel. */
private val OUTCOME_RAIL_WIDTH = 3.dp

/**
 * Upload a Beleg from the archive screen, and say what the server made of it.
 *
 * The upload control itself is the shared [DocumentUploadSection], unchanged: its KDoc is explicit
 * that it reports a document id and nothing else, and turning it into an invoice form would break
 * the two callers that want only a file attached. What this adds is the half that was missing —
 * telling the operator whether an e-invoice was found, which the server has always determined on
 * upload and never reported back.
 *
 * The [DocumentUploadViewModel] is resolved here and **passed down** rather than left to the
 * section's own default. adminApp shares one `ViewModelStoreOwner`, so both would resolve to the
 * same instance either way — but then this card would be reading state it only appears not to own,
 * and a later `viewModel = ` argument at either call site would silently split them.
 *
 * [UploadOutcome] has four cases and three are successes. Only the unreadable one is styled as an
 * error: a scanned receipt carrying no XML is how most paper arrives, and colouring that red trains
 * an operator to ignore the panel.
 */
@Composable
fun DocumentImportCard(
    dialogOpen: Boolean,
    onDialogDismiss: () -> Unit,
    onUploaded: (documentId: Long) -> Unit,
    onOpenDocument: (Long) -> Unit,
    modifier: Modifier = Modifier,
    allowTypeChoice: Boolean = true,
    viewModel: DocumentUploadViewModel = koinViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.loadUploadTypes() }
    // No frame and no button of its own: the host's header button opens the dialog, and while idle
    // both children below compose nothing, so this takes no room until an upload has something to say.
    Column(modifier = modifier.fillMaxWidth()) {
        DocumentUploadSection(
            fileType = DocumentType.INVOICE.name,
            accept = BELEG_UPLOAD_ACCEPT,
            onUploaded = onUploaded,
            // Constant: the section clears itself once when it first composes (screen entry) and never
            // again, so the outcome panel below survives the screen's own recompositions.
            resetKey = REPORTED_OUTCOME_SURVIVES,
            showButton = false,
            viewModel = viewModel,
        )
        viewModel.state.outcome?.let { outcome ->
            OutcomePanel(outcome = outcome, onOpenDocument = onOpenDocument, onPickOther = viewModel::reset)
        }
    }
    if (dialogOpen) {
        DocumentUploadDialog(
            fileTypes = viewModel.uploadTypes,
            allowTypeChoice = allowTypeChoice,
            onDismiss = onDialogDismiss,
            onPickFile = { fileType ->
                onDialogDismiss()
                viewModel.pickAndUpload(fileType = fileType, accept = BELEG_UPLOAD_ACCEPT, onUploaded = onUploaded)
            },
            onScanned = { file, fileType ->
                onDialogDismiss()
                viewModel.uploadPicked(file = file, fileType = fileType, onUploaded = onUploaded)
            },
        )
    }
}

/**
 * A constant `resetKey`, so `LaunchedEffect` runs the reset once and never again.
 *
 * Named rather than inlined because "why does this card not reset" is the question a reader will
 * have, and `resetKey = Unit` looks like the default it is deliberately overriding.
 */
private val REPORTED_OUTCOME_SURVIVES = Any()

/** What an outcome offers next: a button label and what it does. */
private class OutcomeAction(
    val labelKey: StringKey,
    val onClick: () -> Unit,
)

/**
 * One outcome as the design's result banner: a coloured rail, an icon, the title and the case's detail,
 * and the one next step as an outlined button at the end ("Prüfen", "Weiter", "Andere Datei wählen").
 * The severity colours are the rail and the icon only — the text stays neutral so a banner never
 * competes with the page.
 */
@Composable
private fun OutcomePanel(
    outcome: UploadOutcome,
    onOpenDocument: (Long) -> Unit,
    onPickOther: () -> Unit,
) {
    when (outcome) {
        is UploadOutcome.Extracted ->
            OutcomeRow(
                titleKey = AdminStringKeys.DocumentImportExtractedTitle,
                icon = Icons.Default.CheckCircle,
                accent = LocalPosColors.current.posAvailable,
                action = OutcomeAction(AdminStringKeys.BelegflowStageReview) { onOpenDocument(outcome.documentId) },
            ) {
                ExtractedInvoiceLine(outcome)
            }

        is UploadOutcome.NoEInvoice ->
            OutcomeRow(
                titleKey = AdminStringKeys.DocumentImportNoEInvoiceTitle,
                icon = Icons.Default.Info,
                accent = MaterialTheme.colorScheme.outline,
                action = OutcomeAction(AdminStringKeys.BelegflowImportContinue) { onOpenDocument(outcome.documentId) },
            ) {
                DetailText(str(AdminStringKeys.DocumentImportNoEInvoice))
            }

        is UploadOutcome.NotExtractable ->
            OutcomeRow(
                titleKey =
                    if (outcome.unreadable) {
                        AdminStringKeys.DocumentImportUnreadableTitle
                    } else {
                        AdminStringKeys.DocumentImportNotConformantTitle
                    },
                icon = if (outcome.unreadable) Icons.Default.Block else Icons.Default.Error,
                accent = MaterialTheme.colorScheme.error,
                // An unreadable file has nothing to open: the way on is another file. A non-conformant
                // e-invoice is already filed as a scan, so the way on is the document itself.
                action =
                    if (outcome.unreadable) {
                        OutcomeAction(AdminStringKeys.BelegflowImportOtherFile, onPickOther)
                    } else {
                        OutcomeAction(AdminStringKeys.DocumentImportOpen) { onOpenDocument(outcome.documentId) }
                    },
            ) {
                DetailText(
                    if (outcome.unreadable) {
                        str(AdminStringKeys.DocumentImportUnreadable)
                    } else {
                        str(AdminStringKeys.DocumentImportNotConformant)
                    },
                )
            }

        // No document id: the server refused before creating one, so there is nothing to open (and the
        // existing document is not named in the refusal, so the design's "Vorhandenes öffnen" has no target).
        UploadOutcome.Duplicate ->
            OutcomeRow(
                titleKey = AdminStringKeys.DocumentImportDuplicateTitle,
                icon = Icons.Default.ContentCopy,
                accent = LocalPosColors.current.posOccupied,
                action = null,
            ) {
                DetailText(str(AdminStringKeys.DocumentImportDuplicate))
            }
    }
}

/** The shared frame: a coloured rail, an icon, a title with the case's own detail, and an optional next step. */
@Composable
private fun OutcomeRow(
    titleKey: StringKey,
    icon: ImageVector,
    accent: Color,
    action: OutcomeAction?,
    detail: @Composable () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        // A rail rather than a full border: it reads at a glance without boxing in the text.
        Box(Modifier.width(OUTCOME_RAIL_WIDTH).fillMaxHeight().background(accent))
        Row(
            modifier = Modifier.weight(1f).padding(PosSpacing.lg),
            horizontalArrangement = Arrangement.spacedBy(PosSpacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PosSpacing.xs)) {
                Text(
                    text = str(titleKey),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                detail()
            }
            if (action != null) {
                PosSecondaryButton(text = str(action.labelKey), onClick = action.onClick, height = adminTouchSize(40.dp))
            }
        }
    }
}

/**
 * Vendor, number and total — enough for the operator to see it read the right invoice.
 *
 * The money goes through [PosMoneyText] with the invoice's own currency, never a client constant and
 * never a hand-formatted string.
 */
@Composable
private fun ExtractedInvoiceLine(extracted: UploadOutcome.Extracted) {
    val invoice = extracted.invoice
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PosSpacing.sm),
    ) {
        DetailText(
            str(
                AdminStringKeys.DocumentImportExtracted,
                invoice.vendorName.orEmpty(),
                invoice.invoiceNumber.orEmpty(),
            ),
        )
        invoice.grossAmount?.let { gross ->
            PosMoneyText(
                amount = gross,
                currencyCode = invoice.currency.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DetailText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
