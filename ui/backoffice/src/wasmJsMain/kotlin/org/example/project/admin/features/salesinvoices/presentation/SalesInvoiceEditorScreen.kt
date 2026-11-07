package org.example.project.admin.features.salesinvoices.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.money.Decimal
import org.example.project.ui.auth.LocalAccessPolicy
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.components.PosDangerIconButton
import org.example.project.ui.components.PosDateField
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosLoadingOverlay
import org.example.project.ui.components.PosMoneyText
import org.example.project.ui.components.PosPickerField
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosTextField
import org.example.project.ui.components.PosTextFieldType
import org.example.project.ui.components.form.rememberPosFormFocus
import org.example.project.ui.components.form.requiredLabel
import org.example.project.ui.components.formatPercentForDisplay
import org.example.project.ui.guard.rememberDirtySource
import org.example.project.ui.layout.PaneBreakpoints
import org.example.project.ui.theme.PosSpacing
import org.koin.compose.viewmodel.koinViewModel

/**
 * The standalone-invoice editor — one body behind two routes (create / edit), following the
 * `InvoiceCaptureRoute` / `InvoiceEditRoute` precedent.
 *
 * The form scrolls as one `Column`; the sections are plain [PosCard]s stacked in it, never a nested
 * scrolling container. The debtor picker is a [PosDialog], which self-scrolls — adding another
 * `verticalScroll` inside it would crash the wasm layout pass.
 *
 * Where the totals and the two write actions sit follows the width the screen gets: from
 * [WIDE_MIN] a **sticky summary column** beside the scrolling form, between that and the phone width
 * the summary **below** the form, and on a phone a **fixed bottom bar** (Brutto, Entwurf, Ausstellen).
 * Issuing is irreversible, so it always confirms first.
 *
 * Save and Ausstellen are gated on the draft status locally; the server answers 409
 * `SALES_INVOICE_IMMUTABLE` regardless (project rule 8).
 */
@Composable
fun SalesInvoiceEditorScreen(
    invoiceId: Long?,
    onBack: () -> Unit,
) {
    val viewModel: SalesInvoiceEditorViewModel = koinViewModel()
    val state = viewModel.state
    val mayWrite = SalesInvoicesAccess.of(LocalAccessPolicy.current).mayWrite
    var confirmingIssue by remember { mutableStateOf(false) }

    // LaunchedEffect, not VM init: adminApp view models survive logout, so init would show the
    // previous session's invoice.
    LaunchedEffect(invoiceId) { viewModel.load(invoiceId) }

    // `hasChanges` is sticky (edit-then-undo stays dirty) - accepted. Not dirty once issued, so the
    // automatic leave below never prompts. Nothing to reset on Discard: the screen reloads on entry.
    rememberDirtySource(dirty = state.hasChanges && !state.isIssued, onDiscard = {})

    // Leaving on issue is the whole point of the action: the list re-reads from the server on entry
    // and shows the invoice in its new bucket.
    LaunchedEffect(state.isIssued) { if (state.isIssued) onBack() }

    PosErrorWatcher(
        error = state.error,
        title = str(editorTitleKey(invoiceId)),
        onClear = viewModel::clearError,
        retryAction = { viewModel.load(invoiceId) },
    )

    // Writing (and so issuing) needs INVOICE_WRITE; a read-only invoice or group sees no actions at all.
    val showActions = mayWrite && !state.readOnly
    val totals = SalesEditorTotals.of(state.lines)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= WIDE_MIN
        val phone = maxWidth < PaneBreakpoints.PHONE_MAX
        Column(Modifier.fillMaxSize().padding(if (phone) 8.dp else 16.dp)) {
            EditorHeader(state, onBack, phone)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    EditorBody(state, viewModel)
                    // Between the phone and the wide layout the summary closes the form instead.
                    if (!wide && !phone) {
                        EditorSummary(
                            totals,
                            state,
                            showActions,
                            onIssue = { if (viewModel.validateForIssue()) confirmingIssue = true },
                            onSaveDraft = viewModel::save,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (wide) {
                    Column(Modifier.width(SUMMARY_WIDTH)) {
                        EditorSummary(
                            totals,
                            state,
                            showActions,
                            onIssue = { if (viewModel.validateForIssue()) confirmingIssue = true },
                            onSaveDraft = viewModel::save,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            if (phone) {
                EditorBottomBar(
                    totals,
                    state,
                    showActions,
                    onIssue = { if (viewModel.validateForIssue()) confirmingIssue = true },
                    onSaveDraft = viewModel::save,
                )
            }
        }
    }

    if (confirmingIssue) {
        PosDialog(
            onDismiss = { confirmingIssue = false },
            title = str(AdminStringKeys.SalesInvoicesActionIssue),
            kind = PosDialogKind.Confirm,
            primary =
                PosDialogAction(
                    text = str(AdminStringKeys.SalesInvoicesActionIssue),
                    onClick = {
                        confirmingIssue = false
                        viewModel.saveAndIssue()
                    },
                ),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(str(AdminStringKeys.SalesInvoicesIssueConfirm), style = MaterialTheme.typography.bodyMedium)
                IssueSummaryCard(buyerName = state.buyerName, gross = totals.gross.toPlainString(), currency = state.currency)
            }
        }
    }

    if (state.isLoading) {
        PosLoadingOverlay(title = str(editorTitleKey(invoiceId)))
    }
}

/** The form's sections, in their on-screen order, with the validate-on-press focus wiring. */
@Composable
private fun EditorBody(
    state: SalesInvoiceEditorState,
    viewModel: SalesInvoiceEditorViewModel,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (state.readOnly) {
            ImmutableNotice()
        }
        if (state.status == "DRAFT" && state.debtorInactive) {
            DebtorInactiveNotice()
        }

        // Validate on Save: every field at fault is marked; the first is scrolled to and focused.
        val focus = rememberPosFormFocus<String>()
        LaunchedEffect(state.validationAttempt) {
            if (state.validationAttempt > 0) focus.revealFirst(SalesInvoiceEditorFields.order(state.lines.size), state.fieldErrors)
        }
        val anchor: (String) -> Modifier = { key -> with(focus) { Modifier.formAnchor(key) } }
        DebtorSection(state, viewModel)
        BuyerSection(state, viewModel, anchor)
        DatesSection(state, viewModel, anchor)
        BookingSection(state, viewModel)
        LinesSection(state, viewModel, anchor)
        NotesSection(state, viewModel)
    }
}

/** Back, the title and, for a draft, the reminder that the number is only given when it is issued. */
@Composable
private fun EditorHeader(
    state: SalesInvoiceEditorState,
    onBack: () -> Unit,
    phone: Boolean,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        PosIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = str(CommonKeys.back),
            onClick = onBack,
            size = if (phone) 48.dp else 40.dp,
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = editorTitle(state),
                style = if (phone) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
            )
            if (!state.readOnly) {
                Text(
                    text = str(AdminStringKeys.BelegflowEditorDraftSubtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.saved && !state.isSaving) {
                Text(
                    text = str(AdminStringKeys.SalesInvoiceEditorSaved),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * Netto, USt per rate and Brutto, the immutability hint and the two write actions: *Speichern und
 * ausstellen* (primary) and *Als Entwurf speichern*. The figures are feedback derived from the typed
 * lines; the server derives the ones that count.
 */
@Composable
private fun EditorSummary(
    totals: SalesEditorTotals,
    state: SalesInvoiceEditorState,
    showActions: Boolean,
    onIssue: () -> Unit,
    onSaveDraft: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(PosSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(PosSpacing.md),
    ) {
        Text(
            text = str(AdminStringKeys.BelegflowEditorSummaryTitle),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SummaryLine(str(AdminStringKeys.SalesInvoicesNet), totals.net, state.currency)
        totals.vatRows.forEach { row ->
            SummaryLine(
                str(AdminStringKeys.BelegflowEditorVatRow, formatPercentForDisplay(row.rate, LocalAppLanguage.current).removeSuffix("%").trim()),
                row.vat,
                state.currency,
            )
        }
        HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.onSurface)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                str(AdminStringKeys.SalesInvoicesGross),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            PosMoneyText(
                amount = totals.gross.toPlainString(),
                currencyCode = state.currency,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
        }
        if (showActions) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(PosSpacing.md)) {
                Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = str(AdminStringKeys.BelegflowEditorImmutableHint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PosButton(
                text = str(AdminStringKeys.BelegflowEditorSaveAndIssue),
                icon = Icons.AutoMirrored.Filled.Send,
                onClick = onIssue,
                enabled = state.canSaveAndIssue,
                modifier = Modifier.fillMaxWidth(),
            )
            PosSecondaryButton(
                text = str(AdminStringKeys.BelegflowEditorSaveDraft),
                onClick = onSaveDraft,
                enabled = state.canSave,
                isLoading = state.isSaving,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SummaryLine(
    label: String,
    amount: Decimal,
    currency: String,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        PosMoneyText(amount = amount.toPlainString(), currencyCode = currency, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The phone's fixed bar: Brutto on the first line, *Entwurf* and *Ausstellen* (48dp) beneath. */
@Composable
private fun EditorBottomBar(
    totals: SalesEditorTotals,
    state: SalesInvoiceEditorState,
    showActions: Boolean,
    onIssue: () -> Unit,
    onSaveDraft: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(top = PosSpacing.md),
        verticalArrangement = Arrangement.spacedBy(PosSpacing.md),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                str(AdminStringKeys.SalesInvoicesGross),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            PosMoneyText(
                amount = totals.gross.toPlainString(),
                currencyCode = state.currency,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
        }
        if (showActions) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PosSpacing.md)) {
                PosSecondaryButton(
                    text = str(AdminStringKeys.BelegflowSalesDraft),
                    onClick = onSaveDraft,
                    enabled = state.canSave,
                    isLoading = state.isSaving,
                    modifier = Modifier.weight(1f),
                )
                PosButton(
                    text = str(AdminStringKeys.SalesInvoicesActionIssue),
                    icon = Icons.AutoMirrored.Filled.Send,
                    onClick = onIssue,
                    enabled = state.canSaveAndIssue,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** The explanation a disabled form deserves: not silent, not an error, a rule. */
@Composable
private fun ImmutableNotice() {
    PosCard(variant = PosCardVariant.Plain, modifier = Modifier.fillMaxWidth()) {
        body {
            Text(
                text = "${str(AdminStringKeys.SalesInvoiceEditorIssued)} – ${str(AdminStringKeys.SalesInvoiceEditorImmutableHint)}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Why the issue action is missing on a draft whose debtor was deactivated after it was written. */
@Composable
private fun DebtorInactiveNotice() {
    PosCard(variant = PosCardVariant.Plain, modifier = Modifier.fillMaxWidth()) {
        body {
            Text(
                text = str(AdminStringKeys.SalesInvoiceEditorDebtorInactive),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun DebtorSection(
    state: SalesInvoiceEditorState,
    viewModel: SalesInvoiceEditorViewModel,
) {
    var showPicker by remember { mutableStateOf(false) }

    EditorSection(AdminStringKeys.SalesInvoiceEditorSectionDebtor) {
        // Read-only on an issued invoice in the same locked style as its other fields (no dropdown arrow).
        PosPickerField(
            value = state.buyerName,
            onClick = { if (!state.readOnly) showPicker = true },
            label = str(AdminStringKeys.SalesInvoiceEditorPickDebtor),
            modifier = Modifier.fillMaxWidth(),
            readOnly = state.readOnly,
        )
    }

    if (showPicker) {
        PosDialog(
            onDismiss = { showPicker = false },
            title = str(AdminStringKeys.SalesInvoiceEditorPickDebtor),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.debtors.isEmpty()) {
                    Text(
                        text = str(AdminStringKeys.SalesInvoiceEditorNoDebtors),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    state.debtors.forEach { debtor ->
                        PosCard(
                            modifier = Modifier.fillMaxWidth(),
                            variant = PosCardVariant.Plain,
                            onClick = {
                                viewModel.selectDebtor(debtor.id)
                                showPicker = false
                            },
                        ) {
                            body {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(text = debtor.name, style = MaterialTheme.typography.titleSmall)
                                    debtor.city?.let { city ->
                                        Text(
                                            text = city,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BuyerSection(
    state: SalesInvoiceEditorState,
    viewModel: SalesInvoiceEditorViewModel,
    anchor: (String) -> Modifier,
) {
    EditorSection(AdminStringKeys.SalesInvoicesColumnBuyer) {
        EditorField(
            value = state.buyerName,
            onValueChange = viewModel::onBuyerNameChange,
            labelKey = AdminStringKeys.DebtorFieldName,
            errorKey = state.buyerNameError,
            readOnly = state.readOnly,
            required = true,
            modifier = anchor(SalesInvoiceEditorFields.BUYER_NAME),
        )
        EditorField(state.buyerVatId, viewModel::onBuyerVatIdChange, AdminStringKeys.DebtorFieldVatId, readOnly = state.readOnly)
        EditorField(state.buyerStreet, viewModel::onBuyerStreetChange, AdminStringKeys.DebtorFieldStreet, readOnly = state.readOnly)
        EditorField(state.buyerZip, viewModel::onBuyerZipChange, AdminStringKeys.DebtorFieldZip, readOnly = state.readOnly)
        EditorField(state.buyerCity, viewModel::onBuyerCityChange, AdminStringKeys.DebtorFieldCity, readOnly = state.readOnly)
        EditorField(state.buyerCountry, viewModel::onBuyerCountryChange, AdminStringKeys.DebtorFieldCountry, readOnly = state.readOnly)
        EditorField(state.buyerEmail, viewModel::onBuyerEmailChange, AdminStringKeys.DebtorFieldEmail, readOnly = state.readOnly)
    }
}

@Composable
private fun DatesSection(
    state: SalesInvoiceEditorState,
    viewModel: SalesInvoiceEditorViewModel,
    anchor: (String) -> Modifier,
) {
    val hint = str(CommonKeys.dateFormatHint)
    EditorSection(AdminStringKeys.SalesInvoiceEditorSectionDates) {
        // The state stores `yyyy-MM-ddTHH:mm`; the field speaks plain dates, so the midnight suffix
        // is put back here. Blanking the field must stay blank rather than becoming a bare "T00:00",
        // which would reach the server as an unparseable date instead of as "not stated".
        PosDateField(
            value = state.serviceDate.dateOnly(),
            onValueChange = { date -> viewModel.onServiceDateChange(date.withMidnight()) },
            label =
                if (state.readOnly) {
                    str(
                        AdminStringKeys.SalesInvoiceEditorServiceDate,
                    )
                } else {
                    requiredLabel(str(AdminStringKeys.SalesInvoiceEditorServiceDate))
                },
            modifier = anchor(SalesInvoiceEditorFields.SERVICE_DATE).fillMaxWidth(),
            readOnly = state.readOnly,
            isError = state.serviceDateError != null,
            supportingText = state.serviceDateError?.let { str(it, hint) },
        )
        PosDateField(
            value = state.dueDate.dateOnly(),
            onValueChange = { date -> viewModel.onDueDateChange(date.withMidnight()) },
            label = str(AdminStringKeys.SalesInvoiceEditorDueDate),
            modifier = anchor(SalesInvoiceEditorFields.DUE_DATE).fillMaxWidth(),
            readOnly = state.readOnly,
            isError = state.dueDateError != null,
            supportingText = state.dueDateError?.let { str(it, hint) },
        )
        EditorField(
            state.paymentTermsText,
            viewModel::onPaymentTermsChange,
            AdminStringKeys.SalesInvoiceEditorPaymentTerms,
            readOnly = state.readOnly,
        )
    }
}

@Composable
private fun BookingSection(
    state: SalesInvoiceEditorState,
    viewModel: SalesInvoiceEditorViewModel,
) {
    EditorSection(AdminStringKeys.DebtorSectionBooking) {
        EditorField(
            state.revenueAccountNo,
            viewModel::onRevenueAccountChange,
            AdminStringKeys.SalesInvoiceEditorRevenueAccount,
            readOnly = state.readOnly,
        )
        EditorField(state.buCode, viewModel::onBuCodeChange, AdminStringKeys.SalesInvoiceEditorBuCode, readOnly = state.readOnly)
    }
}

@Composable
private fun LinesSection(
    state: SalesInvoiceEditorState,
    viewModel: SalesInvoiceEditorViewModel,
    anchor: (String) -> Modifier,
) {
    EditorSection(AdminStringKeys.SalesInvoiceEditorSectionLines) {
        state.lines.forEachIndexed { index, line ->
            EditorLineCard(
                line = line,
                index = index,
                errors = state.fieldErrors,
                anchor = anchor,
                canRemove = state.lines.size > 1,
                readOnly = state.readOnly,
                onChange = { updated -> viewModel.updateLine(index) { updated } },
                onRemove = { viewModel.removeLine(index) },
            )
        }

        if (!state.readOnly) {
            PosSecondaryButton(
                text = str(AdminStringKeys.SalesInvoiceEditorAddLine),
                onClick = viewModel::addLine,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${str(AdminStringKeys.SalesInvoiceEditorTotals)}:",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            // Feedback only: the server re-derives net/VAT and sums the breakdown it trusts.
            PosMoneyText(
                amount = state.subtotal.toPlainString(),
                currencyCode = state.currency,
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
}

@Composable
private fun EditorLineCard(
    line: EditorLine,
    index: Int,
    errors: Map<String, StringKey>,
    anchor: (String) -> Modifier,
    canRemove: Boolean,
    readOnly: Boolean,
    onChange: (EditorLine) -> Unit,
    onRemove: () -> Unit,
) {
    PosCard(variant = PosCardVariant.Plain, modifier = Modifier.fillMaxWidth()) {
        body {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "${index + 1}.",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    val itemError = errors[SalesInvoiceEditorFields.itemName(index)]
                    PosTextField(
                        value = line.itemName,
                        onValueChange = { onChange(line.copy(itemName = it)) },
                        label =
                            if (readOnly) {
                                str(
                                    AdminStringKeys.SalesInvoiceEditorLineItemName,
                                )
                            } else {
                                requiredLabel(str(AdminStringKeys.SalesInvoiceEditorLineItemName))
                            },
                        modifier = anchor(SalesInvoiceEditorFields.itemName(index)).weight(1f),
                        readOnly = readOnly,
                        locked = readOnly,
                        isError = itemError != null,
                        supportingText = itemError?.let { key -> { Text(str(key)) } },
                    )
                    if (!readOnly) {
                        Spacer(Modifier.width(4.dp))
                        PosDangerIconButton(
                            icon = Icons.Default.Close,
                            contentDescription = str(AdminStringKeys.SalesInvoiceEditorRemoveLine),
                            onClick = onRemove,
                            enabled = canRemove,
                            size = 48.dp,
                        )
                    }
                }
                PosTextField(
                    value = line.itemDescription,
                    onValueChange = { onChange(line.copy(itemDescription = it)) },
                    label = str(AdminStringKeys.SalesInvoiceEditorLineDescription),
                    modifier = Modifier.fillMaxWidth(),
                    readOnly = readOnly,
                    locked = readOnly,
                )
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth < MetricsOneRowMin) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                LineQuantityField(
                                    line,
                                    errors[SalesInvoiceEditorFields.quantity(index)],
                                    readOnly,
                                    onChange,
                                    anchor(SalesInvoiceEditorFields.quantity(index)).weight(1f),
                                )
                                LineUnitField(line, readOnly, onChange, Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                LinePriceField(
                                    line,
                                    errors[SalesInvoiceEditorFields.price(index)],
                                    readOnly,
                                    onChange,
                                    anchor(SalesInvoiceEditorFields.price(index)).weight(1f),
                                )
                                LineRateField(line, readOnly, onChange, Modifier.weight(1f))
                            }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LineQuantityField(
                                line,
                                errors[SalesInvoiceEditorFields.quantity(index)],
                                readOnly,
                                onChange,
                                anchor(SalesInvoiceEditorFields.quantity(index)).weight(1f),
                            )
                            LineUnitField(line, readOnly, onChange, Modifier.weight(1f))
                            LinePriceField(
                                line,
                                errors[SalesInvoiceEditorFields.price(index)],
                                readOnly,
                                onChange,
                                anchor(SalesInvoiceEditorFields.price(index)).weight(1f),
                            )
                            LineRateField(line, readOnly, onChange, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LineQuantityField(
    line: EditorLine,
    lineError: StringKey?,
    readOnly: Boolean,
    onChange: (EditorLine) -> Unit,
    modifier: Modifier = Modifier,
) {
    PosTextField(
        value = line.quantity,
        onValueChange = { onChange(line.copy(quantity = it)) },
        label = str(AdminStringKeys.SalesInvoiceEditorLineQuantity),
        modifier = modifier,
        readOnly = readOnly,
        locked = readOnly,
        keyboardType = KeyboardType.Decimal,
        isError = lineError != null,
        supportingText = lineError?.let { key -> { Text(str(key)) } },
    )
}

@Composable
private fun LineUnitField(
    line: EditorLine,
    readOnly: Boolean,
    onChange: (EditorLine) -> Unit,
    modifier: Modifier = Modifier,
) {
    PosTextField(
        value = line.unitCode,
        onValueChange = { onChange(line.copy(unitCode = it)) },
        label = str(AdminStringKeys.SalesInvoiceEditorLineUnit),
        modifier = modifier,
        readOnly = readOnly,
        locked = readOnly,
    )
}

@Composable
private fun LinePriceField(
    line: EditorLine,
    lineError: StringKey?,
    readOnly: Boolean,
    onChange: (EditorLine) -> Unit,
    modifier: Modifier = Modifier,
) {
    PosTextField(
        value = line.grossUnitPrice,
        onValueChange = { onChange(line.copy(grossUnitPrice = it)) },
        label = str(AdminStringKeys.SalesInvoiceEditorLineUnitPriceGross),
        modifier = modifier,
        readOnly = readOnly,
        locked = readOnly,
        textFieldType = PosTextFieldType.Price,
        isError = lineError != null,
        supportingText = lineError?.let { key -> { Text(str(key)) } },
    )
}

@Composable
private fun LineRateField(
    line: EditorLine,
    readOnly: Boolean,
    onChange: (EditorLine) -> Unit,
    modifier: Modifier = Modifier,
) {
    var rateMenuExpanded by remember { mutableStateOf(false) }
    RateSelector(
        rate = line.vatRate,
        expanded = rateMenuExpanded,
        onToggle = { if (!readOnly) rateMenuExpanded = !rateMenuExpanded },
        onDismiss = { rateMenuExpanded = false },
        onSelect = { rate ->
            rateMenuExpanded = false
            onChange(line.copy(vatRate = rate))
        },
        modifier = modifier,
    )
}

/**
 * The standard rates as percent units — `19.00`, never `0.19`.
 *
 * Reads as the fourth field of the metrics row rather than a stray button below it: a labelled
 * [PosPickerField] anchoring a [PosDropdown], so it lines up with Menge / Einheit / Einzelpreis and
 * inherits the same appearance from the theme.
 */
@Composable
private fun RateSelector(
    rate: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        PosPickerField(
            value = formatPercentForDisplay(rate, LocalAppLanguage.current),
            onClick = onToggle,
            label = str(AdminStringKeys.SalesInvoiceEditorLineVatRate),
            modifier = Modifier.fillMaxWidth(),
        )
        PosDropdown(expanded = expanded, onDismissRequest = onDismiss) {
            VatRates.forEach { option ->
                PosDropdownItem(
                    text = formatPercentForDisplay(option, LocalAppLanguage.current),
                    selected = option == rate,
                    onClick = { onSelect(option) },
                )
            }
        }
    }
}

@Composable
private fun NotesSection(
    state: SalesInvoiceEditorState,
    viewModel: SalesInvoiceEditorViewModel,
) {
    EditorSection(AdminStringKeys.SalesInvoiceEditorNotes) {
        PosTextField(
            value = state.notes,
            onValueChange = viewModel::onNotesChange,
            label = str(AdminStringKeys.SalesInvoiceEditorNotes),
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            minLines = 3,
            readOnly = state.readOnly,
            locked = state.readOnly,
        )
    }
}

/** A titled [PosCard]. Content goes through `body { }` — a plain trailing lambda renders nothing. */
@Composable
private fun EditorSection(
    titleKey: StringKey,
    content: @Composable () -> Unit,
) {
    PosCard(modifier = Modifier.fillMaxWidth()) {
        body {
            Text(str(titleKey), style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun EditorField(
    value: String,
    onValueChange: (String) -> Unit,
    labelKey: StringKey,
    errorKey: StringKey? = null,
    readOnly: Boolean = false,
    required: Boolean = false,
    modifier: Modifier = Modifier,
) {
    PosTextField(
        value = value,
        onValueChange = onValueChange,
        label = if (required && !readOnly) requiredLabel(str(labelKey)) else str(labelKey),
        modifier = modifier.fillMaxWidth(),
        readOnly = readOnly,
        locked = readOnly,
        isError = errorKey != null,
        supportingText = errorKey?.let { key -> { Text(str(key)) } },
    )
}

@Composable
private fun editorTitle(state: SalesInvoiceEditorState): String =
    if (state.readOnly) {
        // A blank number leaves a trailing space behind the placeholder; trimming gives plain "Rechnung".
        str(AdminStringKeys.SalesInvoiceEditorTitleView, state.invoiceNumber).trim()
    } else {
        str(editorTitleKey(state.invoiceId))
    }

private fun editorTitleKey(invoiceId: Long?): StringKey =
    if (invoiceId == null) AdminStringKeys.SalesInvoiceEditorTitleCreate else AdminStringKeys.SalesInvoiceEditorTitleEdit

/** "2026-08-16T00:00" renders as "2026-08-16"; an empty string stays empty. */
internal fun String.dateOnly(): String = substringBefore('T')

/**
 * The inverse of [dateOnly]: puts the midnight the state's format carries back on.
 *
 * A blank stays blank. `"T00:00"` on its own is not an empty date, it is a broken one, and the
 * server would reject it rather than read it as "not stated".
 */
internal fun String.withMidnight(): String = if (isBlank()) "" else "${this}T00:00"

/** From this content width the Summen sit in a sticky column beside the form. */
private val WIDE_MIN = 960.dp
private val SUMMARY_WIDTH = 340.dp

private val VatRates = listOf("19.00", "7.00", "0.00")

/** Below this width the four line-metric fields wrap into two rows of two. */
private val MetricsOneRowMin = 520.dp
