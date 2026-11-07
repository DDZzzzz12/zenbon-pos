package org.example.project.admin.features.contracts.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.components.PinnedHeaderForm
import org.example.project.admin.core.presentation.format.asDisplayDay
import org.example.project.admin.core.presentation.format.currencySymbolForDisplay
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.contract.ContractAlertDto
import org.example.project.core.domain.dto.contract.ContractDto
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.auth.LocalAccessPolicy
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosDateField
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosLoadingOverlay
import org.example.project.ui.components.PosMoneyText
import org.example.project.ui.components.PosTextField
import org.example.project.ui.components.form.rememberPosFormFocus
import org.example.project.ui.components.form.requiredLabel
import org.example.project.ui.guard.rememberDirtySource
import org.koin.compose.viewmodel.koinViewModel

/**
 * One contract: the form, then its instalments as a strip.
 *
 * **A null [contractId] is the create form** — the same fields with no instalments, since a
 * contract has none until the nightly job has run against it.
 *
 * The page scrolls as one `Column`; the sections are plain [PosCard]s stacked in it (content in
 * `body { }` — a plain trailing lambda renders nothing), and the strip scrolls horizontally inside
 * its own row. Never a nested vertical scroll.
 */
@Composable
fun ContractDetailScreen(
    contractId: Long?,
    onBack: () -> Unit,
) {
    val viewModel: ContractDetailViewModel = koinViewModel()
    val state = viewModel.state

    LaunchedEffect(contractId) { viewModel.load(contractId) }

    rememberDirtySource(dirty = state.hasUnsavedEdits, onDiscard = viewModel::discardDraft)

    val title = if (contractId == null) AdminStringKeys.ContractCreateTitle else AdminStringKeys.ContractDetailTitle

    PosErrorWatcher(
        error = state.error,
        title = str(title),
        onClear = viewModel::clearError,
        retryAction = { viewModel.load(contractId) },
    )

    PinnedHeaderForm(
        header = {
            DetailHeader(
                titleKey = title,
                saveEnabled = state.isDirty && !state.isSaving,
                isSaving = state.isSaving,
                onBack = onBack,
                onSave = viewModel::save,
            )
        },
    ) {
        // Validate on Save: every field at fault is marked, the first is scrolled to and focused.
        val focus = rememberPosFormFocus<ContractFormField>()
        LaunchedEffect(state.validationAttempt) {
            if (state.validationAttempt > 0) focus.revealFirst(ContractFormField.entries, state.errors.byField())
        }
        val anchor: (ContractFormField) -> Modifier = { field -> with(focus) { Modifier.formAnchor(field) } }
        BasicsSection(state, viewModel, anchor)
        ScheduleSection(state, viewModel, anchor)
        AmountsSection(state, viewModel, anchor)
        BookingSection(state.form, viewModel)
        AlertsSection(state)
        InstalmentsSection(state, viewModel)
    }

    if (state.isLoading) {
        PosLoadingOverlay(title = str(title))
    }
}

@Composable
private fun DetailHeader(
    titleKey: StringKey,
    saveEnabled: Boolean,
    isSaving: Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        PosIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = str(CommonKeys.back),
            onClick = onBack,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = str(titleKey),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (ContractsAccess.of(LocalAccessPolicy.current).mayWrite) {
            PosButton(
                text = str(CommonKeys.save),
                onClick = onSave,
                enabled = saveEnabled,
                isLoading = isSaving,
            )
        }
    }
}

@Composable
private fun BasicsSection(
    state: ContractDetailState,
    viewModel: ContractDetailViewModel,
    anchor: (ContractFormField) -> Modifier,
) {
    ContractSection(AdminStringKeys.ContractSectionBasics) {
        ContractField(
            value = state.form.name,
            onValueChange = viewModel::onNameChange,
            labelKey = AdminStringKeys.ContractFieldName,
            errorKey = state.nameError,
            required = true,
            modifier = anchor(ContractFormField.NAME),
        )
        ContractField(state.form.description, viewModel::onDescriptionChange, AdminStringKeys.ContractFieldDescription)
        ContractSupplierPicker(
            selectedId = supplierIdOrNull(state.form.supplierId),
            suppliers = state.suppliers,
            loadedName = state.loadedSupplierName,
            onSelect = { id -> viewModel.onSupplierIdChange(supplierIdText(id)) },
            modifier = anchor(ContractFormField.SUPPLIER),
        )
        FieldError(state.supplierError)
        ToggleRow(
            labelKey = AdminStringKeys.ContractFieldExpectsInvoice,
            checked = state.form.expectsInvoice,
            onCheckedChange = viewModel::onExpectsInvoiceChange,
        )
        Hint(AdminStringKeys.ContractExpectsInvoiceHint)
        ToggleRow(
            labelKey = AdminStringKeys.ContractFieldActive,
            checked = state.form.isActive,
            onCheckedChange = viewModel::onActiveChange,
        )
    }
}

@Composable
private fun ScheduleSection(
    state: ContractDetailState,
    viewModel: ContractDetailViewModel,
    anchor: (ContractFormField) -> Modifier,
) {
    ContractSection(AdminStringKeys.ContractSectionSchedule) {
        Text(
            requiredLabel(str(AdminStringKeys.ContractFieldInterval)),
            style = MaterialTheme.typography.bodyMedium,
            modifier = anchor(ContractFormField.INTERVAL),
        )
        IntervalChips(selected = state.form.interval, onSelect = viewModel::onIntervalChange)
        FieldError(state.intervalError)
        if (state.form.interval == "WEEKLY") {
            // A week has no day of month, so the day-of-month field is not merely unused here — it
            // would be a control that looks like it does something and does not.
            WeekdayChips(
                selected = state.form.dueWeekday,
                onSelect = viewModel::onDueWeekdayChange,
                modifier = anchor(ContractFormField.DUE_WEEKDAY),
            )
            FieldError(state.weekdayError)
            Hint(AdminStringKeys.ContractDueWeekdayHint)
        } else {
            // ONE_TIME anchors on due_day exactly as the month-stepped intervals do — it is a
            // schedule that stops after the first period — so it takes this branch unchanged.
            ContractField(
                value = state.form.dueDay,
                onValueChange = viewModel::onDueDayChange,
                labelKey = AdminStringKeys.ContractFieldDueDay,
                errorKey = state.dueDayError,
                keyboardType = KeyboardType.Number,
                required = true,
                modifier = anchor(ContractFormField.DUE_DAY),
            )
            Hint(AdminStringKeys.ContractDueDayHint)
        }
        // The shared date field: typed or picked, ISO on the wire — the same one the invoice capture uses.
        PosDateField(
            value = state.form.startDate,
            onValueChange = viewModel::onStartDateChange,
            label = requiredLabel(str(AdminStringKeys.ContractFieldStartDate)),
            isError = state.dateError != null,
            supportingText = state.dateError?.let { str(it) },
            modifier = anchor(ContractFormField.START_DATE),
        )
        PosDateField(
            value = state.form.endDate,
            onValueChange = viewModel::onEndDateChange,
            label = str(AdminStringKeys.ContractFieldEndDate),
            isError = state.endDateError != null,
            supportingText = state.endDateError?.let { str(it) },
            modifier = anchor(ContractFormField.END_DATE),
        )
        ContractField(
            value = state.form.noticePeriodDays,
            onValueChange = viewModel::onNoticePeriodChange,
            labelKey = AdminStringKeys.ContractFieldNoticePeriod,
            keyboardType = KeyboardType.Number,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IntervalChips(
    selected: String,
    onSelect: (String) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ContractForm.INTERVALS.forEach { interval ->
            FilterChip(
                selected = interval == selected,
                onClick = { onSelect(interval) },
                label = { Text(str(intervalKey(interval))) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekdayChips(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(requiredLabel(str(AdminStringKeys.ContractFieldDueWeekday)), style = MaterialTheme.typography.bodyMedium, modifier = modifier)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        (1..7).forEach { iso ->
            FilterChip(
                selected = selected == iso.toString(),
                onClick = { onSelect(iso.toString()) },
                label = { Text(str(weekdayKey(iso))) },
            )
        }
    }
}

/** ISO-8601: 1 = Monday .. 7 = Sunday, matching the column and kotlinx.datetime. */
private fun weekdayKey(iso: Int): StringKey =
    when (iso) {
        1 -> AdminStringKeys.WeekdayMonday
        2 -> AdminStringKeys.WeekdayTuesday
        3 -> AdminStringKeys.WeekdayWednesday
        4 -> AdminStringKeys.WeekdayThursday
        5 -> AdminStringKeys.WeekdayFriday
        6 -> AdminStringKeys.WeekdaySaturday
        else -> AdminStringKeys.WeekdaySunday
    }

@Composable
private fun AmountsSection(
    state: ContractDetailState,
    viewModel: ContractDetailViewModel,
    anchor: (ContractFormField) -> Modifier,
) {
    ContractSection(AdminStringKeys.ContractSectionAmounts) {
        ContractField(
            value = state.form.expectedAmount,
            onValueChange = viewModel::onExpectedAmountChange,
            labelKey = AdminStringKeys.ContractFieldExpectedAmount,
            errorKey = state.amountError,
            suffix = currencySymbolForDisplay(state.currency),
            modifier = anchor(ContractFormField.EXPECTED_AMOUNT),
        )
        ContractField(
            state.form.tolerancePct,
            viewModel::onTolerancePctChange,
            AdminStringKeys.ContractFieldTolerancePct,
            errorKey = state.errors.tolerancePct,
            suffix = "%",
            modifier = anchor(ContractFormField.TOLERANCE_PCT),
        )
        ContractField(
            state.form.toleranceAbs,
            viewModel::onToleranceAbsChange,
            AdminStringKeys.ContractFieldToleranceAbs,
            errorKey = state.errors.toleranceAbs,
            suffix = currencySymbolForDisplay(state.currency),
            modifier = anchor(ContractFormField.TOLERANCE_ABS),
        )
        Hint(AdminStringKeys.ContractToleranceHint)
    }
}

@Composable
private fun BookingSection(
    form: ContractForm,
    viewModel: ContractDetailViewModel,
) {
    ContractSection(AdminStringKeys.ContractSectionBooking) {
        ContractField(form.defaultAccountNo, viewModel::onDefaultAccountNoChange, AdminStringKeys.ContractFieldAccountNo)
        ContractField(form.defaultBuCode, viewModel::onDefaultBuCodeChange, AdminStringKeys.ContractFieldBuCode)
        ContractField(form.defaultCostCenter, viewModel::onDefaultCostCenterChange, AdminStringKeys.ContractFieldCostCenter)
    }
}

/**
 * What this contract is complaining about, above the strip so it is read before the periods are.
 *
 * Absent entirely when there is nothing wrong: an empty "Hinweise" card would be one more thing to
 * scan past on the contracts that are fine, which is most of them.
 */
@Composable
private fun AlertsSection(state: ContractDetailState) {
    if (state.alerts.isEmpty()) return
    ContractSection(AdminStringKeys.ContractAlertsTitle) {
        state.alerts.forEach { AlertRow(it) }
    }
}

/**
 * One alert: what is wrong, when it was due, and — for a changed amount — both figures, so an
 * operator does not have to open the invoice to learn what "the amount changed" means.
 */
@Composable
private fun AlertRow(alert: ContractAlertDto.AlertDto) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = str(alertKindKey(alert.kind)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        LabelledText(AdminStringKeys.ContractInstalmentDue, alert.dueDate.asDisplayDay(LocalAppLanguage.current))
        LabelledMoney(AdminStringKeys.ContractInstalmentExpected, alert.expectedAmount, alert.currency)
        LabelledMoney(AdminStringKeys.ContractAlertActual, alert.actualAmount, alert.currency)
    }
}

/**
 * The wire carries a `ContractAlertKind` constant name. An unknown one falls back to the missing
 * payment wording rather than rendering the raw constant — but the three are exhaustive.
 */
internal fun alertKindKey(kind: String): StringKey =
    when (kind) {
        "INVOICE_MISSING" -> AdminStringKeys.ContractAlertInvoiceMissing
        "AMOUNT_OFF_BAND" -> AdminStringKeys.ContractAlertAmountOffBand
        else -> AdminStringKeys.ContractAlertPaymentMissing
    }

/**
 * The strip, and — once a cell is selected — that instalment's amounts.
 *
 * A contract that has not been saved yet has no id, so it has no instalments and never will until
 * the nightly job has run: say so rather than showing an empty row that looks like a fault.
 */
@Composable
private fun InstalmentsSection(
    state: ContractDetailState,
    viewModel: ContractDetailViewModel,
) {
    ContractSection(AdminStringKeys.ContractInstalmentsTitle) {
        when {
            state.id == null -> Hint(AdminStringKeys.ContractInstalmentsUnsaved)
            state.instalments.isEmpty() -> Hint(AdminStringKeys.ContractInstalmentsEmpty)
            else -> {
                InstalmentStrip(
                    instalments = state.instalments,
                    // The saved interval: the instalments were generated from it, not from an unsaved edit.
                    interval = state.pristine.interval,
                    selectedId = state.selectedInstalmentId,
                    onSelect = { viewModel.selectInstalment(it.id) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Hint(AdminStringKeys.ContractInstalmentsHint)
                state.selectedInstalment?.let { SelectedInstalment(it) }
            }
        }
    }
}

@Composable
private fun SelectedInstalment(instalment: ContractDto.InstalmentDto) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LabelledText(AdminStringKeys.ContractInstalmentDue, instalment.dueDate.asDisplayDay(LocalAppLanguage.current))
        LabelledMoney(AdminStringKeys.ContractInstalmentExpected, instalment.expectedAmount, instalment.currency)
        LabelledMoney(AdminStringKeys.ContractInstalmentOpen, instalment.openAmount, instalment.currency)
        if (instalment.invoiceNumber.isNullOrBlank()) {
            Hint(AdminStringKeys.ContractInstalmentNoInvoice)
        } else {
            LabelledText(AdminStringKeys.ContractInstalmentInvoice, instalment.invoiceNumber.orEmpty())
        }
    }
}

@Composable
private fun LabelledText(
    labelKey: StringKey,
    value: String,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(str(labelKey), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LabelledMoney(
    labelKey: StringKey,
    amount: String?,
    currency: String,
) {
    if (amount.isNullOrBlank()) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(str(labelKey), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PosMoneyText(
            amount = amount,
            currencyCode = currency,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ToggleRow(
    labelKey: StringKey,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(str(labelKey), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun Hint(key: StringKey) {
    Text(
        text = str(key),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun supplierIdOrNull(text: String): Long? = text.trim().toLongOrNull()

/** The form keeps the supplier as text; a cleared selection is the empty string. */
private fun supplierIdText(id: Long?): String = id?.toString() ?: ""

/** A titled [PosCard]. Content goes through `body { }` — a plain trailing lambda renders nothing. */
@Composable
private fun ContractSection(
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
private fun ContractField(
    value: String,
    onValueChange: (String) -> Unit,
    labelKey: StringKey,
    errorKey: StringKey? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    suffix: String? = null,
    required: Boolean = false,
    modifier: Modifier = Modifier,
) {
    PosTextField(
        value = value,
        onValueChange = onValueChange,
        label = if (required) requiredLabel(str(labelKey)) else str(labelKey),
        modifier = modifier.fillMaxWidth(),
        keyboardType = keyboardType,
        suffix = suffix?.let { unit -> { Text(unit) } },
        isError = errorKey != null,
        supportingText = errorKey?.let { key -> { Text(str(key)) } },
    )
}

/** A field error under a control that has no supporting-text slot of its own (chips, a picker). */
@Composable
private fun FieldError(key: StringKey?) {
    key ?: return
    Text(text = str(key), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}
