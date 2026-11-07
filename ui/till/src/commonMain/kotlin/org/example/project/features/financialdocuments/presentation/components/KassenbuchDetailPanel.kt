/**
 * Kassenbuch entry detail panel component.
 *
 * Displays comprehensive details of a grouped kassenbuch entry including:
 * - Basic information (entry type, booking number, date, employee, locked status)
 * - Transaction summary (total amount, individual line items with VAT rates and sub-balances)
 * - DATEV fields (bu, gegenkonto, kost1, kost2, nachricht) when detail is loaded
 * - TSE information (transaction ID, signature, signed timestamp) when available
 * - Linked document information and PDF viewer button
 * - Balance information
 * - Description and notes
 *
 * Supports multi-VAT entries where multiple line items share the same belegNr.
 * Fetches full details from server on entry selection to display additional DATEV and TSE fields.
 */
package org.example.project.features.financialdocuments.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.CorrectionRole
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.components.RightSideDetailPanel
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.KassenbuchKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.enumStrings.getName
import org.example.project.ui.components.*
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.kassenbuch.KassenbuchEntryDetailSections

/**
 * Detail panel for viewing complete grouped kassenbuch entry information.
 *
 * Shows a side panel with entry details organized
 * into sections: basic info, line items with VAT breakdown, DATEV fields,
 * TSE information, linked documents, and description.
 *
 * @param entry Grouped kassenbuch entry summary data to display
 * @param entry Full entry detail DTO with DATEV and TSE fields (null while loading)
 * @param isLoadingDetails Whether the detail is currently being loaded from server
 * @param previousBalance Balance before this transaction (from previous entry), null if first entry
 * @param onDismiss Callback when panel is dismissed
 * @param onShowLinkedPdf Callback when show linked PDF button is clicked
 * @param onCreateCorrection Optional callback when create correction button is clicked
 * @param language Current app language for localized strings
 * @param windowSize Current window size for responsive layout
 * @param canCreateCorrection Whether the current user may create correction entries (`CASHBOOK_WRITE`)
 */
@Composable
fun KassenbuchDetailPanel(
    modifier: Modifier = Modifier,
    entry: KassenbuchDto.KassenbuchGroupedDetailDto,
    isLoadingDetails: Boolean = false,
    previousBalance: Double? = null,
    onDismiss: () -> Unit,
    onShowLinkedPdf: ((Long) -> Unit)? = null,
    onUnlinkDocument: ((Long) -> Unit)? = null,
    onUploadAndLink: ((Long) -> Unit)? = null,
    onReplaceDocument: ((Long) -> Unit)? = null,
    onCreateCorrection: (() -> Unit)? = null,
    language: Language,
    canCreateCorrection: Boolean = false,
) {
    val isIncoming = entry.entryType.isIncoming

    var showReplaceConfirmation by remember { mutableStateOf(false) }

    RightSideDetailPanel(
        visible = true,
        onDismiss = onDismiss,
        title = str(KassenbuchKeys.entryDetails),
        subtitle = entry.entryType.getName(language),
        modifier = modifier,
        actions = {
            // Linked document count badge
            if (entry.hasLinkedDocuments) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.padding(end = 8.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.AttachFile,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            text = str(KassenbuchKeys.linkedDocumentCount, entry.linkedDocumentCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
        },
    ) {
        // Loading indicator for details
        if (isLoadingDetails) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = str(KassenbuchKeys.loadingDetails),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Scrollable content
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Shared read-only detail sections (reused by the admin panel too)
            KassenbuchEntryDetailSections(entry = entry, language = language)
        }

        // Action buttons — document actions wrap on narrow widths;
        // the destructive "Create correction" sits on its own row below.
        val linkedDoc = entry.linkedDocument
        val showPdf = onShowLinkedPdf != null && linkedDoc != null
        val unlink = onUnlinkDocument != null && linkedDoc != null && !entry.isLocked
        val addDoc = onUploadAndLink != null && linkedDoc == null && !entry.isLocked
        val replaceDoc = onReplaceDocument != null && linkedDoc != null && !entry.isLocked
        // Correction (Storno) is explicitly allowed on locked entries — it appends a
        // reversal in the current period and never mutates the locked original.
        // Z-Bon aggregates (non-cash payment collection lines) are never correctable
        // from the UI — server guard (422) is the backstop, this is the client half.
        val showCorrection = canCreateCorrection && onCreateCorrection != null && isCorrectableEntry(entry)
        // A Geldtransit leg is never corrected alone (the server answers 422); say why the
        // button is missing rather than leaving the operator to hunt for it.
        val showTransferLegNote = onCreateCorrection != null && entry.transferId != null

        if (showPdf || unlink || addDoc || replaceDoc) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val itemModifier =
                    Modifier
                        .weight(1f, fill = true)
                        .widthIn(min = 160.dp)

                if (showPdf && onShowLinkedPdf != null && linkedDoc != null) {
                    PosTonalButton(
                        text = str(KassenbuchKeys.showLinkedPdf),
                        onClick = { onShowLinkedPdf(linkedDoc.id) },
                        icon = Icons.Default.PictureAsPdf,
                        modifier = itemModifier,
                    )
                }
                if (addDoc && onUploadAndLink != null) {
                    PosTonalButton(
                        text = str(KassenbuchKeys.addDocument),
                        onClick = { onUploadAndLink(entry.headerId) },
                        icon = Icons.Default.UploadFile,
                        modifier = itemModifier,
                    )
                }
                if (replaceDoc) {
                    PosSecondaryButton(
                        text = str(KassenbuchKeys.replaceDocument),
                        onClick = { showReplaceConfirmation = true },
                        icon = Icons.Default.UploadFile,
                        modifier = itemModifier,
                    )
                }
                if (unlink && onUnlinkDocument != null) {
                    PosSecondaryButton(
                        text = str(KassenbuchKeys.unlinkDocument),
                        onClick = { onUnlinkDocument(entry.headerId) },
                        icon = Icons.Default.LinkOff,
                        modifier = itemModifier,
                    )
                }
            }
        }

        if (showTransferLegNote) {
            Text(
                text = str(KassenbuchKeys.transferLegNotCorrectable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (showCorrection && onCreateCorrection != null) {
            PosDangerButton(
                text = str(KassenbuchKeys.createCorrection),
                onClick = onCreateCorrection,
                icon = Icons.Default.Edit,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (showReplaceConfirmation) {
        PosDialog(
            onDismiss = { showReplaceConfirmation = false },
            title = str(KassenbuchKeys.replaceDocumentConfirmation),
            footer = {
                PosTextButton(
                    text = str(CommonKeys.cancel),
                    onClick = { showReplaceConfirmation = false },
                )
                PosTextButton(
                    text = str(KassenbuchKeys.replaceDocument),
                    onClick = {
                        showReplaceConfirmation = false
                        onReplaceDocument?.invoke(entry.headerId)
                    },
                )
            },
        ) {
            Text(str(KassenbuchKeys.replaceDocumentConfirmationMessage))
        }
    }
}

/**
 * Whether [entry] may be corrected (Storno + optional re-entry) on its own — the client half of the
 * refusals in `KassenbuchServiceImpl.createCorrection`, which answers 422 for each case excluded here:
 *
 * - a **Geldtransit leg** (`transferId != null`): one physical movement recorded in two books; a
 *   one-leg Storno would leave the books disagreeing, so the transfer is reversed as a whole via
 *   `POST /api/v1/kassenbuch/transfers/{transferId}/reverse` (T5-5 — the till used to offer it);
 * - a pure Storno (`REVERSAL`), or an entry that already has one;
 * - the Z-Bon aggregate entry (non-cash payment collection lines).
 *
 * A corrected re-entry (`RE_ENTRY`) stays correctable — correcting it chains another Storno.
 */
internal fun isCorrectableEntry(entry: KassenbuchDto.KassenbuchGroupedDetailDto): Boolean =
    entry.transferId == null &&
        entry.correctionRole != CorrectionRole.REVERSAL &&
        !entry.alreadyReversed &&
        !entry.isZbonAggregate
