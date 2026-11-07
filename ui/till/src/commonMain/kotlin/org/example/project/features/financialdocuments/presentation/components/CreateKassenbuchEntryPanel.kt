/**
 * Create Kassenbuch Entry Panel component.
 *
 * Form panel for creating new kassenbuch (cash book) entries. All form data
 * lives in [CreateKassenbuchEntryFormState]; this composable only renders UI
 * and forwards user actions to that state holder.
 *
 * @since 1.0.0
 */
package org.example.project.features.financialdocuments.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.material3.SelectableDates
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.auth.LocalSettingManager
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.components.DiscardChangesDialog
import org.example.project.core.presentation.components.RightSideDetailPanel
import org.example.project.core.presentation.components.docUtil.DocumentScannerButton
import org.example.project.core.presentation.components.fileBrowser.FileBrowserDialog
import org.example.project.core.presentation.components.fileBrowser.rememberDocumentPickerLauncher
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.DocumentManagementKeys
import org.example.project.core.presentation.strings.keys.KassenbuchKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.KassenbuchBelegDateRule
import org.example.project.core.utils.KassenbuchBelegDateViolation
import org.example.project.core.utils.enumStrings.getDescription
import org.example.project.core.utils.enumStrings.getName
import org.example.project.core.utils.isDocumentScannerAvailable
import org.example.project.core.utils.keyboardAwarePadding
import org.example.project.features.financialdocuments.presentation.state.BelegType
import org.example.project.features.financialdocuments.presentation.state.CreateKassenbuchEntryFormState
import org.example.project.features.financialdocuments.presentation.state.EntryLineState
import org.example.project.features.financialdocuments.presentation.state.InvoiceMetadataInput
import org.example.project.features.financialdocuments.presentation.state.localToday
import org.example.project.money.Decimal
import org.example.project.ui.components.PosAutoSizeText
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.components.PosDangerIconButton
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosPickerField
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.components.PosTextField
import org.example.project.ui.components.PosTextFieldType
import org.example.project.ui.components.formatMoneyForDisplay
import org.example.project.ui.components.kassenbuch.kindLabel
import kotlin.time.Instant

@OptIn(ExperimentalMaterial3Api::class, kotlin.time.ExperimentalTime::class)
@Composable
fun CreateKassenbuchEntryPanel(
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit,
    onConfirm: (KassenbuchDto.CreateKassenbuchEntryRequestDto, ByteArray?, String?, String?, InvoiceMetadataInput?) -> Unit,
    language: Language,
    isCreating: Boolean = false,
    availableCash: String? = null,
    formState: CreateKassenbuchEntryFormState = remember { CreateKassenbuchEntryFormState() },
) {
    // F59: the Kassenminus ceiling, loaded by the ViewModel when the panel opened.
    LaunchedEffect(availableCash) { formState.availableCash = availableCash?.let { Decimal.parseOrNull(it) } }
    // Transient UI flags — not part of the form data, so they stay local.
    var showAdvancedFields by remember { mutableStateOf(false) }
    var showEntryTypeMenu by remember { mutableStateOf(false) }
    var showBelegTypeMenu by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showFileBrowser by remember { mutableStateOf(false) }
    val documentPicker =
        rememberDocumentPickerLauncher(
            mimeTypes = listOf("application/pdf", "image/*"),
            onPicked = { file ->
                formState.setFile(file.bytes, file.name)
            },
            onFailed = { reason -> Logger.withTag("CreateKassenbuchEntryPanel").w { "Document pick failed: $reason" } },
        )
    var showInvoiceMetadata by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }

    // The shop's Beleg-age limit, synced from the server; the server enforces it regardless.
    val maxBelegAgeDays by LocalSettingManager.current.kassenbuchMaxBelegAgeDays.collectAsState()
    LaunchedEffect(maxBelegAgeDays) { formState.maxBelegAgeDays = maxBelegAgeDays }
    val today = localToday()
    val dateViolation = formState.belegDateViolation(today)
    // Past the limit but within a year: bookable with a reason and a manager's authority.
    val needsLateBookingReason = formState.needsLateBookingReason(today)

    val handleDismiss: () -> Unit = {
        if (formState.isDirty) showDiscardDialog = true else onDismiss()
    }

    RightSideDetailPanel(
        visible = true,
        onDismiss = handleDismiss,
        title = str(KassenbuchKeys.createEntry),
        modifier = modifier,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .keyboardAwarePadding()
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                EntryTypeDropdown(
                    selected = formState.selectedEntryType,
                    expanded = showEntryTypeMenu,
                    language = language,
                    validationErrors = formState.validationErrors,
                    onExpandedChange = { showEntryTypeMenu = it },
                    onSelect = { type ->
                        formState.onEntryTypeSelected(type)
                        showEntryTypeMenu = false
                    },
                )

                PosTextField(
                    value = formState.date,
                    onValueChange = {},
                    label = str(KassenbuchKeys.documentDate),
                    modifier = Modifier.fillMaxWidth(),
                    readOnly = true,
                    supportingText = {
                        Text(
                            if (needsLateBookingReason) {
                                str(KassenbuchKeys.documentDateNeedsReason, formState.maxBelegAgeDays.toString())
                            } else {
                                str(KassenbuchKeys.documentDateHint)
                            },
                        )
                    },
                    isError = dateViolation != null && !needsLateBookingReason,
                    errorMessage =
                        when {
                            needsLateBookingReason -> null
                            dateViolation == KassenbuchBelegDateViolation.FUTURE -> str(KassenbuchKeys.documentDateInFuture)
                            dateViolation == KassenbuchBelegDateViolation.TOO_OLD ->
                                str(KassenbuchKeys.documentDateTooOld, KassenbuchBelegDateRule.OVERRIDE_CEILING_DAYS.toString())
                            else -> null
                        },
                    singleLine = true,
                    trailingIcon = {
                        PosIconButton(
                            icon = Icons.Default.CalendarToday,
                            contentDescription = str(KassenbuchKeys.documentDate),
                            onClick = { showDatePicker = true },
                        )
                    },
                )

                if (needsLateBookingReason) {
                    PosTextField(
                        value = formState.lateBookingReason,
                        onValueChange = {
                            formState.lateBookingReason = it.take(KassenbuchBelegDateRule.MAX_LATE_BOOKING_REASON_LENGTH)
                        },
                        label = str(KassenbuchKeys.lateBookingReason),
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text(str(KassenbuchKeys.lateBookingReasonHint)) },
                        isError = formState.lateBookingReason.isBlank(),
                    )
                }

                EntryLinesSection(
                    entryType = formState.selectedEntryType,
                    items = formState.lines,
                    total = formState.total,
                    totalIsPositive = formState.totalIsPositive,
                    lineAmountInvalid = formState::lineAmountInvalid,
                    language = language,
                    validationErrors = formState.validationErrors,
                    onUpdate = formState::updateLineItem,
                    onAdd = formState::addLineItem,
                    onRemove = formState::removeLineItem,
                )

                AvailableCashHint(formState)

                PosTextField(
                    value = formState.description,
                    onValueChange = { formState.description = it },
                    label = str(KassenbuchKeys.description) + " *",
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    minLines = 2,
                    maxLines = 3,
                    imeAction = ImeAction.Done,
                    isError = formState.validationErrors.any { it.contains("Belegtext", ignoreCase = true) },
                )

                DocumentAttachmentSection(
                    formState = formState,
                    showBelegTypeMenu = showBelegTypeMenu,
                    onBelegTypeMenuExpandedChange = { showBelegTypeMenu = it },
                    onPickFile = { documentPicker?.launch() ?: run { showFileBrowser = true } },
                    showInvoiceMetadata = showInvoiceMetadata,
                    onToggleInvoiceMetadata = { showInvoiceMetadata = !showInvoiceMetadata },
                    onAutoExpandInvoiceMetadata = { showInvoiceMetadata = true },
                    language = language,
                )

                HorizontalDivider()

                AdvancedDatevSection(
                    formState = formState,
                    expanded = showAdvancedFields,
                    onToggle = { showAdvancedFields = !showAdvancedFields },
                )

                if (formState.validationErrors.isNotEmpty()) {
                    ValidationErrorsCard(formState.validationErrors)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PosSecondaryButton(
                    text = getString(CommonKeys.cancel, language),
                    onClick = handleDismiss,
                    modifier = Modifier.weight(1f),
                )
                PosButton(
                    text = getString(CommonKeys.create, language),
                    onClick = {
                        if (formState.selectedEntryType == null) {
                            formState.validationErrors = listOf("Entry type is required")
                            return@PosButton
                        }
                        val request = formState.buildRequest()
                        val errors =
                            request
                                .validate()
                                .filterNot { it.contains("documentId", ignoreCase = true) }
                        if (errors.isNotEmpty()) {
                            formState.validationErrors = errors
                        } else {
                            formState.validationErrors = emptyList()
                            onConfirm(
                                request,
                                formState.selectedFileBytes,
                                formState.selectedFileName,
                                formState.fileType(),
                                formState.invoiceMetadataInputOrNull(),
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = formState.canSubmit,
                    isLoading = isCreating,
                )
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
                    formState.setFile(file.bytes, file.name)
                }
                showFileBrowser = false
            },
        )
    }

    if (showDatePicker) {
        DDMMDatePickerDialog(
            onDismiss = { showDatePicker = false },
            onDateSelected = { ddmm ->
                formState.date = ddmm
                showDatePicker = false
            },
            language = language,
            // Up to a year back: past the shop's limit the form asks for a late-booking reason.
            selectableRange = KassenbuchBelegDateRule.earliestAllowed(today, KassenbuchBelegDateRule.OVERRIDE_CEILING_DAYS)..today,
        )
    }

    if (showDiscardDialog) {
        DiscardChangesDialog(
            onDiscard = {
                showDiscardDialog = false
                onDismiss()
            },
            onKeepEditing = {
                showDiscardDialog = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryTypeDropdown(
    selected: KassenbuchEntryType?,
    expanded: Boolean,
    language: Language,
    validationErrors: List<String>,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (KassenbuchEntryType) -> Unit,
) {
    Box {
        PosPickerField(
            value = selected?.getName(language) ?: "",
            onClick = { onExpandedChange(true) },
            label = str(KassenbuchKeys.entryType) + " *",
            modifier =
                Modifier
                    .fillMaxWidth(),
            placeholder = str(KassenbuchKeys.selectEntryType),
            isError = validationErrors.any { it.contains("Entry type", ignoreCase = true) },
        )
        PosDropdown(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            KassenbuchEntryType.entries.forEach { type ->
                PosDropdownItem(
                    text = type.getName(language),
                    supportingText = type.getDescription(language),
                    selected = type == selected,
                    leadingIcon = if (type.isIncoming) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                    onClick = { onSelect(type) },
                )
            }
        }
    }
}

@Composable
internal fun EntryLinesSection(
    /** Null until the operator picks a type; the VAT/kind controls stay fully enabled until then. */
    entryType: KassenbuchEntryType?,
    items: List<EntryLineState>,
    total: Decimal,
    totalIsPositive: Boolean,
    /** True when that line's typed amount is not a number — the field shows its own error. */
    lineAmountInvalid: (Int) -> Boolean,
    language: Language,
    validationErrors: List<String>,
    onUpdate: (Int, EntryLineState) -> Unit,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
) {
    PosCard(
        modifier = Modifier.fillMaxWidth(),
        variant = PosCardVariant.Elevated,
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        body {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = str(KassenbuchKeys.vatLineItems) + " *",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )

                items.forEachIndexed { index, item ->
                    EntryLineCard(
                        entryType = entryType,
                        item = item,
                        index = index,
                        language = language,
                        canDelete = items.size > 1,
                        onUpdate = { onUpdate(index, it) },
                        onDelete = { onRemove(index) },
                        validationErrors = validationErrors,
                        amountInvalid = lineAmountInvalid(index),
                    )
                }

                PosSecondaryButton(
                    text = str(KassenbuchKeys.addLineItem),
                    onClick = onAdd,
                    icon = Icons.Default.Add,
                    modifier = Modifier.fillMaxWidth(),
                )

                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = str(KassenbuchKeys.total) + ":",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = formatMoneyForDisplay(total.toPlainString(), "EUR"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (!totalIsPositive && items.any { it.amount.isNotBlank() }) {
                    Text(
                        text = str(KassenbuchKeys.totalMustBePositive),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocumentAttachmentSection(
    formState: CreateKassenbuchEntryFormState,
    showBelegTypeMenu: Boolean,
    onBelegTypeMenuExpandedChange: (Boolean) -> Unit,
    onPickFile: () -> Unit,
    showInvoiceMetadata: Boolean,
    onToggleInvoiceMetadata: () -> Unit,
    onAutoExpandInvoiceMetadata: () -> Unit,
    language: Language,
) {
    PosCard(
        modifier = Modifier.fillMaxWidth(),
        variant = PosCardVariant.Elevated,
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        body {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = str(KassenbuchKeys.documentTypeLabel),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )

                Box {
                    PosPickerField(
                        value =
                            when (formState.selectedBelegType) {
                                BelegType.UPLOAD_EIGENBELEG -> str(KassenbuchKeys.uploadEigenbelegOption)
                                BelegType.UPLOAD_INVOICE -> str(KassenbuchKeys.invoiceOption)
                            },
                        onClick = { onBelegTypeMenuExpandedChange(true) },
                        label = str(KassenbuchKeys.documentTypeLabel),
                        modifier =
                            Modifier
                                .fillMaxWidth(),
                    )
                    PosDropdown(
                        expanded = showBelegTypeMenu,
                        onDismissRequest = { onBelegTypeMenuExpandedChange(false) },
                    ) {
                        PosDropdownItem(
                            text = str(KassenbuchKeys.uploadEigenbelegOption),
                            selected = formState.selectedBelegType == BelegType.UPLOAD_EIGENBELEG,
                            onClick = {
                                formState.onBelegTypeSelected(BelegType.UPLOAD_EIGENBELEG)
                                onBelegTypeMenuExpandedChange(false)
                            },
                        )
                        PosDropdownItem(
                            text = str(KassenbuchKeys.invoiceOption),
                            selected = formState.selectedBelegType == BelegType.UPLOAD_INVOICE,
                            onClick = {
                                formState.onBelegTypeSelected(BelegType.UPLOAD_INVOICE)
                                onBelegTypeMenuExpandedChange(false)
                            },
                        )
                    }
                }

                if (formState.selectedFileName != null) {
                    SelectedFileRow(
                        fileName = formState.selectedFileName!!,
                        onRemove = formState::clearFile,
                    )
                } else {
                    PosSecondaryButton(
                        text = str(KassenbuchKeys.attachPdf),
                        onClick = onPickFile,
                        icon = Icons.Default.AttachFile,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (isDocumentScannerAvailable()) {
                        DocumentScannerButton(
                            onScanResult = { scan ->
                                formState.setFile(scan.pdfBytes, scan.fileName)
                                formState.invoice.applyScanResult(scan)
                                if (formState.invoice.hasMeaningfulScanData(scan)) {
                                    onAutoExpandInvoiceMetadata()
                                }
                            },
                            onError = { /* Silently ignore scanner errors */ },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = Icons.Default.CameraAlt,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                when (language) {
                                    Language.GERMAN -> "Dokument scannen"
                                    Language.ENGLISH -> "Scan document"
                                    Language.CHINESE -> "扫描文档"
                                },
                            )
                        }
                    }
                }

                if (formState.selectedBelegType == BelegType.UPLOAD_INVOICE && formState.selectedFileName != null) {
                    InvoiceMetadataPanel(
                        invoice = formState.invoice,
                        expanded = showInvoiceMetadata,
                        onToggle = onToggleInvoiceMetadata,
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectedFileRow(
    fileName: String,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.AttachFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            PosAutoSizeText(
                text = str(KassenbuchKeys.fileSelected, fileName),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        PosDangerIconButton(
            icon = Icons.Default.Close,
            contentDescription = str(KassenbuchKeys.removeAttachment),
            onClick = onRemove,
            size = 32.dp,
            iconSize = 20.dp,
        )
    }
}

@Composable
private fun InvoiceMetadataPanel(
    invoice: org.example.project.features.financialdocuments.presentation.state.InvoiceMetadataFormState,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = str(KassenbuchKeys.invoiceDetailsLabel),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        PosIconButton(
            icon = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (expanded) str(KassenbuchKeys.collapse) else str(KassenbuchKeys.expand),
            onClick = onToggle,
            size = 32.dp,
            iconSize = 20.dp,
        )
    }

    if (!expanded) return

    PosCard(
        modifier = Modifier.fillMaxWidth(),
        variant = PosCardVariant.Elevated,
    ) {
        body {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PosTextField(
                    value = invoice.vendorName,
                    onValueChange = { invoice.vendorName = it },
                    label = str(DocumentManagementKeys.vendorNameLabel),
                    modifier = Modifier.fillMaxWidth(),
                )
                PosTextField(
                    value = invoice.invoiceNumber,
                    onValueChange = { invoice.invoiceNumber = it },
                    label = str(DocumentManagementKeys.invoiceNumberLabel),
                    placeholder = str(DocumentManagementKeys.invoiceNumberPlaceholder),
                    supportingText = { Text(str(DocumentManagementKeys.invoiceNumberHint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PosTextField(
                        value = invoice.invoiceDate,
                        onValueChange = { invoice.invoiceDate = it },
                        label = str(DocumentManagementKeys.invoiceDateLabel),
                        placeholder = "YYYY-MM-DD",
                        modifier = Modifier.weight(1f),
                    )
                    PosTextField(
                        value = invoice.dueDate,
                        onValueChange = { invoice.dueDate = it },
                        label = str(DocumentManagementKeys.dueDateLabel),
                        placeholder = "YYYY-MM-DD",
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PosTextField(
                        value = invoice.netAmount,
                        onValueChange = { invoice.netAmount = it },
                        label = str(DocumentManagementKeys.netAmountLabel),
                        modifier = Modifier.weight(1f),
                        textFieldType = PosTextFieldType.Price,
                    )
                    PosTextField(
                        value = invoice.grossAmount,
                        onValueChange = { invoice.grossAmount = it },
                        label = str(DocumentManagementKeys.grossAmountLabel),
                        modifier = Modifier.weight(1f),
                        textFieldType = PosTextFieldType.Price,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PosTextField(
                        value = invoice.taxAmount,
                        onValueChange = { invoice.taxAmount = it },
                        label = str(DocumentManagementKeys.taxAmountLabel),
                        modifier = Modifier.weight(1f),
                        textFieldType = PosTextFieldType.Price,
                    )
                    PosTextField(
                        value = invoice.taxRate,
                        onValueChange = { invoice.taxRate = it },
                        label = str(DocumentManagementKeys.taxRateLabel),
                        modifier = Modifier.weight(1f),
                        keyboardType = KeyboardType.Decimal,
                    )
                }
                PosTextField(
                    value = invoice.currency,
                    onValueChange = { invoice.currency = it },
                    label = str(DocumentManagementKeys.currencyLabel),
                    placeholder = "EUR",
                    modifier = Modifier.fillMaxWidth(),
                )
                PosTextField(
                    value = invoice.iban,
                    onValueChange = { invoice.iban = it },
                    label = str(DocumentManagementKeys.ibanLabel),
                    placeholder = str(DocumentManagementKeys.ibanPlaceholder),
                    modifier = Modifier.fillMaxWidth(),
                )
                PosTextField(
                    value = invoice.paymentReference,
                    onValueChange = { invoice.paymentReference = it },
                    label = str(DocumentManagementKeys.paymentReferenceLabel),
                    placeholder = str(DocumentManagementKeys.paymentReferencePlaceholder),
                    supportingText = { Text(str(DocumentManagementKeys.paymentReferenceHint)) },
                    modifier = Modifier.fillMaxWidth(),
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Text(
                    text = str(KassenbuchKeys.datevApSection),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PosTextField(
                        value = invoice.supplierCity,
                        onValueChange = { invoice.supplierCity = it },
                        label = str(DocumentManagementKeys.supplierCityLabel),
                        modifier = Modifier.weight(1f),
                    )
                    PosTextField(
                        value = invoice.vatId,
                        onValueChange = { invoice.vatId = it },
                        label = str(DocumentManagementKeys.vatIdLabel),
                        placeholder = str(DocumentManagementKeys.vatIdPlaceholder),
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PosTextField(
                        value = invoice.accountNo,
                        onValueChange = { invoice.accountNo = it },
                        label = str(DocumentManagementKeys.accountNoLabel),
                        placeholder = str(DocumentManagementKeys.accountNoPlaceholder),
                        modifier = Modifier.weight(1f),
                    )
                    PosTextField(
                        value = invoice.bpAccountNo,
                        onValueChange = { invoice.bpAccountNo = it },
                        label = str(DocumentManagementKeys.bpAccountNoLabel),
                        placeholder = str(DocumentManagementKeys.bpAccountNoPlaceholder),
                        modifier = Modifier.weight(1f),
                    )
                }
                PosTextField(
                    value = invoice.deliveryDate,
                    onValueChange = { invoice.deliveryDate = it },
                    label = str(DocumentManagementKeys.deliveryDateLabel),
                    placeholder = "YYYY-MM-DD",
                    modifier = Modifier.fillMaxWidth(),
                )
                PosTextField(
                    value = invoice.bookingText,
                    onValueChange = { invoice.bookingText = it },
                    label = str(DocumentManagementKeys.bookingTextLabel),
                    placeholder = str(DocumentManagementKeys.bookingTextPlaceholder),
                    supportingText = { Text(str(DocumentManagementKeys.bookingTextHint)) },
                    modifier = Modifier.fillMaxWidth(),
                    imeAction = ImeAction.Done,
                )
            }
        }
    }
}

@Composable
private fun AdvancedDatevSection(
    formState: CreateKassenbuchEntryFormState,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    PosCard(
        modifier = Modifier.fillMaxWidth(),
        variant = PosCardVariant.Elevated,
    ) {
        body {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = str(KassenbuchKeys.advancedDatevFields),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    PosIconButton(
                        icon = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) str(KassenbuchKeys.collapse) else str(KassenbuchKeys.expand),
                        onClick = onToggle,
                    )
                }

                if (!expanded) return@Column

                PosTextField(
                    value = formState.notes,
                    onValueChange = { formState.notes = it },
                    label = str(KassenbuchKeys.notes),
                    placeholder = str(KassenbuchKeys.notesPlaceholder),
                    supportingText = { Text(str(KassenbuchKeys.notesHint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    minLines = 2,
                    maxLines = 3,
                )
                PosTextField(
                    value = formState.counterAccount,
                    onValueChange = { formState.counterAccount = it },
                    label = str(KassenbuchKeys.counterAccount),
                    placeholder = "4-stellig (z.B. 8400)",
                    modifier = Modifier.fillMaxWidth(),
                    keyboardType = KeyboardType.Number,
                    supportingText = {
                        Text(
                            text = str(KassenbuchKeys.counterAccountHint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                )
                PosTextField(
                    value = formState.bookingKey,
                    onValueChange = { formState.bookingKey = it },
                    label = str(KassenbuchKeys.bookingKey),
                    placeholder = "2-stellig (z.B. 40)",
                    modifier = Modifier.fillMaxWidth(),
                    keyboardType = KeyboardType.Number,
                    supportingText = {
                        Text(
                            text = str(KassenbuchKeys.bookingKeyHint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PosTextField(
                        value = formState.costCenter1,
                        onValueChange = { formState.costCenter1 = it },
                        label = str(KassenbuchKeys.costCenter1),
                        placeholder = str(KassenbuchKeys.costCenterPlaceholder),
                        modifier = Modifier.weight(1f),
                    )
                    PosTextField(
                        value = formState.costCenter2,
                        onValueChange = { formState.costCenter2 = it },
                        label = str(KassenbuchKeys.costCenter2),
                        placeholder = str(KassenbuchKeys.costCenterPlaceholder),
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PosTextField(
                        value = formState.quantity,
                        onValueChange = { formState.quantity = it },
                        label = str(KassenbuchKeys.quantity),
                        modifier = Modifier.weight(1f),
                        keyboardType = KeyboardType.Decimal,
                    )
                    PosTextField(
                        value = formState.discount,
                        onValueChange = { formState.discount = it },
                        label = str(KassenbuchKeys.discount),
                        modifier = Modifier.weight(1f),
                        textFieldType = PosTextFieldType.Price,
                        imeAction = ImeAction.Done,
                        suffix = { Text("€") },
                    )
                }
            }
        }
    }
}

@Composable
private fun ValidationErrorsCard(errors: List<String>) {
    PosCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.errorContainer,
    ) {
        body {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = str(KassenbuchKeys.validationErrors),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                errors.forEach { error ->
                    Text(
                        text = "• $error",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, kotlin.time.ExperimentalTime::class)
@Composable
private fun DDMMDatePickerDialog(
    onDismiss: () -> Unit,
    onDateSelected: (String) -> Unit,
    language: Language,
    selectableRange: ClosedRange<LocalDate>,
) {
    // Offer only Beleg dates the server accepts: not in the future, not older than the shop's limit.
    val datePickerState =
        rememberDatePickerState(
            selectableDates =
                object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                        Instant.fromEpochMilliseconds(utcTimeMillis).toLocalDateTime(TimeZone.UTC).date in selectableRange

                    override fun isSelectableYear(year: Int): Boolean = year in selectableRange.start.year..selectableRange.endInclusive.year
                },
        )

    LaunchedEffect(datePickerState.selectedDateMillis) {
        datePickerState.selectedDateMillis?.let { millis ->
            val localDate =
                Instant
                    .fromEpochMilliseconds(millis)
                    .toLocalDateTime(TimeZone.UTC)
                    .date
            val day = localDate.day.toString().padStart(2, '0')
            val month =
                localDate.month.number
                    .toString()
                    .padStart(2, '0')
            val year = localDate.year.toString()
            onDateSelected("$day.$month.$year")
            onDismiss()
        }
    }

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            PosTextButton(
                text = getString(CommonKeys.cancel, language),
                onClick = onDismiss,
            )
        },
    ) {
        DatePicker(
            state = datePickerState,
            title = {
                Text(
                    text = str(KassenbuchKeys.documentDate),
                    modifier = Modifier.padding(16.dp),
                )
            },
            headline = { Box {} },
            showModeToggle = false,
            colors =
                DatePickerDefaults.colors(
                    todayContentColor = MaterialTheme.colorScheme.primary,
                    todayDateBorderColor = MaterialTheme.colorScheme.primary,
                ),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EntryLineCard(
    entryType: KassenbuchEntryType?,
    item: EntryLineState,
    index: Int,
    language: Language,
    canDelete: Boolean,
    onUpdate: (EntryLineState) -> Unit,
    onDelete: () -> Unit,
    validationErrors: List<String>,
    amountInvalid: Boolean = false,
) {
    @Suppress("UNUSED_PARAMETER")
    val _language = language
    var showKindMenu by remember { mutableStateOf(false) }
    var showVatRateMenu by remember { mutableStateOf(false) }
    // Round-7 D1: a GUTSCHEIN line is only a voucher redemption inside an Umsatz Vorgang; the
    // server rejects it elsewhere (KassenbuchServiceImpl.createEntry), so don't offer it.
    val kindOptions =
        buildList {
            add(KassenbuchLineKind.STANDARD)
            add(KassenbuchLineKind.PFAND)
            if (entryType == null || entryType.allowsGutscheinLine) add(KassenbuchLineKind.GUTSCHEIN)
        }
    val vatRateOptions = listOf("0", "7", "19")
    // Round-7 C1: for the nine GV_TYPs DSFinV-K pins to the 0 %/nicht-steuerbar class, the declared
    // rate is DISCARDED by both the signed TSE payload and the CSV export. Offering a rate the
    // system throws away is how a Privateinlage came to sign a 19 % Umsatz declaration on the
    // default path — `EntryLineState` opens at "19". Show the fixed non-taxable state instead.
    val carriesVatRate = entryType == null || entryType.carriesVatRate

    PosCard(
        modifier = Modifier.fillMaxWidth(),
        variant = PosCardVariant.Elevated,
        tonalElevation = 1.dp,
    ) {
        body {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = str(KassenbuchKeys.lineItem, index + 1),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (canDelete) {
                        PosDangerIconButton(
                            icon = Icons.Default.Delete,
                            contentDescription = str(KassenbuchKeys.removeLineItem),
                            onClick = onDelete,
                            size = 32.dp,
                            iconSize = 20.dp,
                        )
                    }
                }

                Box(Modifier.fillMaxWidth()) {
                    PosPickerField(
                        value = kindLabel(item.kind),
                        onClick = { showKindMenu = true },
                        label = str(KassenbuchKeys.lineKind),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PosDropdown(
                        expanded = showKindMenu,
                        onDismissRequest = { showKindMenu = false },
                    ) {
                        kindOptions.forEach { kind ->
                            PosDropdownItem(
                                text = kindLabel(kind),
                                selected = kind == item.kind,
                                onClick = {
                                    onUpdate(item.copy(kind = kind))
                                    showKindMenu = false
                                },
                            )
                        }
                    }
                }

                PosTextField(
                    value = item.amount,
                    onValueChange = { onUpdate(item.copy(amount = it)) },
                    label = str(KassenbuchKeys.signedAmount) + " *",
                    placeholder = "-19,11",
                    modifier = Modifier.fillMaxWidth(),
                    // Plain text + decimal keypad, like the Z-Bon counted-cash field: the Price type
                    // rewrote a typed "200,00" to "200.00" on blur. The form parses the text as typed
                    // (`toUserInputDecimalOrNull`, comma or dot) and flags a non-number itself.
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done,
                    isError =
                        amountInvalid ||
                            validationErrors.any {
                                it.contains("Line ${index + 1}:", ignoreCase = true) &&
                                    it.contains("amount", ignoreCase = true)
                            },
                    supportingText =
                        if (amountInvalid) {
                            { Text(str(KassenbuchKeys.amountNotANumber)) }
                        } else {
                            null
                        },
                )

                if (!carriesVatRate) {
                    // Read-only, and NOT a selectable "0 %": this Vorgang has no USt-Bezug at all
                    // (Anhang C p. 58 „ohne USt-Bezug (ID 5)"), which is a different statement from
                    // a 0 % taxable supply. The line still submits its stored rate; the server
                    // classifier discards it for this GV_TYP either way.
                    PosTextField(
                        value = str(KassenbuchKeys.vatRateNotApplicable),
                        onValueChange = {},
                        label = str(KassenbuchKeys.vatRate),
                        modifier = Modifier.fillMaxWidth(),
                        readOnly = true,
                        enabled = false,
                        supportingText = { Text(str(KassenbuchKeys.vatRateNotApplicableHint)) },
                        singleLine = true,
                    )
                    return@Column
                }

                Box(Modifier.fillMaxWidth()) {
                    PosPickerField(
                        value =
                            when (item.vatRate) {
                                "0" -> str(KassenbuchKeys.vatRateTaxExempt)
                                "7" -> str(KassenbuchKeys.vatRateReduced)
                                else -> str(KassenbuchKeys.vatRateStandard)
                            },
                        onClick = { showVatRateMenu = true },
                        label = str(KassenbuchKeys.vatRate) + " *",
                        modifier = Modifier.fillMaxWidth(),
                        isError =
                            validationErrors.any {
                                it.contains("Line ${index + 1}:", ignoreCase = true) &&
                                    it.contains("VAT", ignoreCase = true)
                            },
                    )
                    PosDropdown(
                        expanded = showVatRateMenu,
                        onDismissRequest = { showVatRateMenu = false },
                    ) {
                        vatRateOptions.forEach { rate ->
                            PosDropdownItem(
                                text =
                                    when (rate) {
                                        "0" -> str(KassenbuchKeys.vatRateTaxExempt)
                                        "7" -> str(KassenbuchKeys.vatRateReduced)
                                        else -> str(KassenbuchKeys.vatRateStandard)
                                    },
                                selected = rate == item.vatRate,
                                onClick = {
                                    onUpdate(item.copy(vatRate = rate))
                                    showVatRateMenu = false
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** F59: what the drawer holds, and — for a withdrawal above it — why the form will not submit. */
@Composable
private fun AvailableCashHint(formState: CreateKassenbuchEntryFormState) {
    val available = formState.availableCash ?: return
    if (!formState.isOutflow) return
    val formatted = formatMoneyForDisplay(available.toPlainString(), "EUR")
    Text(
        text = str(KassenbuchKeys.availableCash, formatted),
        style = MaterialTheme.typography.bodySmall,
    )
    if (formState.exceedsAvailableCash) {
        Text(
            text = str(KassenbuchKeys.exceedsAvailableCash, formatted),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
