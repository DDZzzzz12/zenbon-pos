package org.example.project.features.financialdocuments.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.components.fileBrowser.FileBrowserDialog
import org.example.project.core.presentation.components.fileBrowser.rememberDocumentPickerLauncher
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.KassenbuchKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.toDecimalOrZero
import org.example.project.core.utils.toUserInputDecimalOrNull
import org.example.project.core.utils.withDecimalSeparatorOf
import org.example.project.features.financialdocuments.presentation.state.EntryLineState
import org.example.project.money.Decimal
import org.example.project.money.sum
import org.example.project.ui.components.PosAutoSizeText
import org.example.project.ui.components.PosDangerButton
import org.example.project.ui.components.PosDangerIconButton
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosSegmentedButtonRow
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.components.PosTextField

/**
 * Correction (Storno) dialog for a Kassenbuch entry.
 *
 * Shows the original entry summary, a mandatory reason field, and the editable
 * VAT line items (pre-filled from the original's lines) rendered with the same
 * [EntryLinesSection] used by the create-entry form — giving number formatting
 * on the amount field and a VAT-rate dropdown for free.
 *
 * The user chooses whether the corrected re-entry reuses the original document
 * (default — `replacementDocumentId` stays null, resolved server-side) or uploads
 * a replacement document. When a replacement file is picked it is forwarded to the
 * caller (via [onConfirm]) so the ViewModel can upload it and set
 * `replacementDocumentId`.
 *
 * @param original The entry being corrected.
 * @param language Current application language (for the reused VAT section).
 * @param isCorrectionInProgress Whether a correction submission is currently running.
 * @param onDismiss Called when the dialog is dismissed without confirming.
 * @param onConfirm Called with the original header id, the correction request, and
 *   the optional replacement file bytes/name.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateCorrectionDialog(
    original: KassenbuchDto.KassenbuchGroupedDetailDto,
    language: Language,
    isCorrectionInProgress: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (
        headerId: Long,
        request: KassenbuchDto.CorrectEntryRequestDto,
        fileBytes: ByteArray?,
        fileName: String?,
    ) -> Unit,
) {
    var reason by remember { mutableStateOf("") }

    // Pre-fill one editable line per original line item, converted from stored
    // cash-flow sign back to DOCUMENT sign (what the user typed, spec §1.3).
    val docIncoming = original.entryType.isIncoming
    val lineItems =
        remember {
            mutableStateListOf<EntryLineState>().apply {
                val source = original.lineItems
                if (source.isEmpty()) {
                    // totalAmount is an exact decimal string; flip to document sign in Decimal,
                    // never by multiplying a Double.
                    add(
                        EntryLineState(
                            vatRate = "19",
                            amount = correctionAmountPrefill(original.totalAmount, original.entryType.isIncoming, language),
                        ),
                    )
                } else {
                    source.forEach { line ->
                        add(
                            EntryLineState(
                                kind = line.kind,
                                vatRate = line.ustSatz ?: "0",
                                // Recover the document-sign amount in Decimal (was Double * docSign).
                                amount = correctionAmountPrefill(line.vorzBetrag, docIncoming, language),
                            ),
                        )
                    }
                }
            }
        }

    var useReplacement by remember { mutableStateOf(false) }
    var fileBytes by remember { mutableStateOf<ByteArray?>(null) }
    var fileName by remember { mutableStateOf<String?>(null) }
    var showFileBrowser by remember { mutableStateOf(false) }
    val documentPicker =
        rememberDocumentPickerLauncher(
            mimeTypes = listOf("application/pdf", "image/*"),
            onPicked = { file ->
                fileBytes = file.bytes
                fileName = file.name
            },
            onFailed = { reason -> Logger.withTag("CreateCorrectionDialog").w { "Document pick failed: $reason" } },
        )

    val reasonError = reason.isBlank()
    // Typed input, parsed exactly: comma or dot, never through Double (E2E-2026-09-18 #6).
    val parsedAmounts = lineItems.map { it.amount.toUserInputDecimalOrNull() }
    val linesValid =
        parsedAmounts.all { it != null && !it.isZero } &&
            lineItems.all { it.vatRate.isNotBlank() }
    val total = parsedAmounts.filterNotNull().sum()
    val totalPositive = total > Decimal.ZERO
    val replacementMissing = useReplacement && fileBytes == null
    val canConfirm = !reasonError && linesValid && totalPositive && !replacementMissing && !isCorrectionInProgress

    PosDialog(
        onDismiss = onDismiss,
        title = str(KassenbuchKeys.correctionDialogTitle),
        footer = {
            PosTextButton(text = str(CommonKeys.cancel), onClick = onDismiss)
            PosDangerButton(
                text = str(KassenbuchKeys.correctionConfirm),
                enabled = canConfirm,
                isLoading = isCorrectionInProgress,
                onClick = {
                    val dtoLineItems =
                        lineItems.map { item ->
                            KassenbuchDto.EntryLineDto(
                                kind = item.kind,
                                vatRate = item.vatRate,
                                amount =
                                    requireNotNull(item.amount.toUserInputDecimalOrNull()) {
                                        "confirm must be disabled while a line is invalid"
                                    }.toPlainString(),
                            )
                        }
                    val request =
                        KassenbuchDto.CorrectEntryRequestDto(
                            reason = reason.trim(),
                            correctedEntry =
                                KassenbuchDto.CreateKassenbuchEntryRequestDto(
                                    entryType = original.entryType,
                                    lines = dtoLineItems,
                                    waehrung = original.waehrung,
                                    belegtext = original.belegtext,
                                    documentId = null, // resolved server-side (copy of original / replacement)
                                ),
                            replacementDocumentId = null, // VM fills this after uploading the replacement
                        )
                    if (useReplacement) {
                        onConfirm(original.headerId, request, fileBytes, fileName)
                    } else {
                        onConfirm(original.headerId, request, null, null)
                    }
                },
            )
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // raw Material 3 by design: simple label, no Pos* text wrapper exists in ui/components
            Text(
                str(KassenbuchKeys.correctionOriginalSummary, original.belegNr, original.belegDatum),
            )

            PosTextField(
                value = reason,
                onValueChange = { reason = it },
                label = str(KassenbuchKeys.correctionReasonLabel),
                isError = reasonError,
                errorMessage = if (reasonError) str(KassenbuchKeys.correctionReasonRequired) else null,
            )

            EntryLinesSection(
                // The corrected re-entry keeps the ORIGINAL's Vorgangsart, so the VAT/kind controls
                // must be gated by it too (round-7 C1/D1).
                entryType = original.entryType,
                items = lineItems,
                total = total,
                totalIsPositive = totalPositive,
                lineAmountInvalid = { index ->
                    lineItems.getOrNull(index)?.let { it.amount.isNotBlank() && parsedAmounts[index] == null } ?: false
                },
                language = language,
                validationErrors = emptyList(),
                onUpdate = { index, item -> lineItems[index] = item },
                onAdd = { lineItems.add(EntryLineState()) },
                onRemove = { index -> if (lineItems.size > 1) lineItems.removeAt(index) },
            )

            // raw Material 3 by design: simple label, no Pos* text wrapper exists in ui/components
            Text(str(KassenbuchKeys.correctionDocumentSource))

            PosSegmentedButtonRow(
                options = listOf(false, true),
                selectedOption = useReplacement,
                onOptionSelected = { useReplacement = it },
            ) { replacement ->
                if (replacement) {
                    str(KassenbuchKeys.correctionUploadReplacement)
                } else {
                    str(KassenbuchKeys.correctionUseOriginalDocument)
                }
            }

            if (useReplacement) {
                val pickedName = fileName
                if (pickedName != null) {
                    SelectedReplacementFileRow(
                        fileName = pickedName,
                        onRemove = {
                            fileBytes = null
                            fileName = null
                        },
                    )
                } else {
                    PosSecondaryButton(
                        text = str(KassenbuchKeys.correctionSelectFile),
                        onClick = { documentPicker?.launch() ?: run { showFileBrowser = true } },
                        icon = Icons.Default.AttachFile,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    if (showFileBrowser) {
        FileBrowserDialog(
            mode = org.example.project.core.domain.fileBrowser.models.FileBrowserMode.FILE_SYSTEM,
            isMultiSelect = false,
            onDismiss = { showFileBrowser = false },
            onSelect = { selectedFiles ->
                selectedFiles.firstOrNull()?.let { file ->
                    fileBytes = file.bytes
                    fileName = file.name
                }
                showFileBrowser = false
            },
        )
    }
}

@Composable
private fun SelectedReplacementFileRow(
    fileName: String,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PosAutoSizeText(
            text = str(KassenbuchKeys.fileSelected, fileName),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth(0.8f),
        )
        PosDangerIconButton(
            icon = Icons.Default.Close,
            contentDescription = str(KassenbuchKeys.removeAttachment),
            onClick = onRemove,
            size = 32.dp,
            iconSize = 20.dp,
        )
    }
}

/**
 * The correction form's prefilled amount for one original line (or the entry total): the stored
 * cash-flow [amount] flipped back to DOCUMENT sign — what the operator typed, spec §1.3 — as a
 * canonical two-decimal string.
 *
 * Scale 2 on purpose. The server's `DECIMAL(19,4)` form ("35.8100") was otherwise copied into the
 * editable field verbatim whenever a response carried it (the summary `totalAmount` does), and an
 * operator asked to correct a figure should see it as money, "35.81". Unparseable reads as zero,
 * which the dialog's positive-total guard then refuses.
 */
internal fun correctionAmountPrefill(
    amount: String,
    documentIncoming: Boolean,
    language: Language = Language.ENGLISH,
): String =
    amount
        .toDecimalOrZero()
        .let { if (documentIncoming) it else -it }
        .setScale(2)
        .toPlainString()
        .withDecimalSeparatorOf(language)
