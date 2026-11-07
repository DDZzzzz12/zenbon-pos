package org.example.project.admin.features.debtors.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.components.AdminListScaffoldPadding
import org.example.project.admin.core.presentation.components.PinnedHeaderForm
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.enums.salesinvoice.EInvoiceFormatPreference
import org.example.project.core.presentation.components.notification.LocalGlobalOverlayViewModel
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.auth.LocalAccessPolicy
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardStatus
import org.example.project.ui.components.PosDangerOutlinedButton
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosFormGrid
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosLoadingOverlay
import org.example.project.ui.components.PosPickerField
import org.example.project.ui.components.PosTextField
import org.example.project.ui.components.form.rememberPosFormFocus
import org.example.project.ui.components.form.requiredLabel
import org.example.project.ui.guard.rememberDirtySource
import org.koin.compose.viewmodel.koinViewModel

/**
 * One debtor's master data. A null [debtorId] is the create form — the same fields, saved with a
 * POST instead of a PUT.
 *
 * Deleting is offered only for a debtor no invoice references. One that has been invoiced is
 * retired with the *aktiv* switch instead: its invoices are kept ten years under §147 AO and must
 * keep pointing at the party they were issued to. The server enforces the same rule.
 *
 * The page scrolls as one `Column`; the sections are plain [PosCard]s stacked in it, never a nested
 * scrolling container. The sections themselves live in [DebtorDetailSections] so the Debitoren
 * detail pane can render the same form without this screen's scroll and back arrow.
 */
@Composable
fun DebtorDetailScreen(
    debtorId: Long?,
    onBack: () -> Unit,
    onCreated: (debtorId: Long) -> Unit = {},
) {
    val viewModel: DebtorDetailViewModel = koinViewModel()
    val state = viewModel.state
    val overlay = LocalGlobalOverlayViewModel.current
    val createdTitle = str(AdminStringKeys.DebtorDetailTitle)
    val createdMessage = str(AdminStringKeys.DebtorCreatedToast, state.form.name)

    // NEW-20: a successful create is confirmed and leaves the create route for the new debtor's own
    // route, so nothing on screen still reads "new" and a later Save is an ordinary edit of that
    // record. `pristine` is re-pinned by the save, so the navigation never asks about edits.
    LaunchedEffect(state.createdDebtorId) {
        val created = state.createdDebtorId ?: return@LaunchedEffect
        overlay.showSuccess(createdTitle, createdMessage)
        viewModel.consumeCreated()
        onCreated(created)
    }

    LaunchedEffect(debtorId) { viewModel.load(debtorId) }

    // A deleted debtor leaves through `onBack` below and must not ask about edits to a record that is gone.
    rememberDirtySource(dirty = state.isDirty && !state.isDeleted, onDiscard = viewModel::discardDraft)

    // Leaving on delete is the whole point of the action, so it navigates rather than showing an
    // emptied form the operator has to back out of.
    LaunchedEffect(state.isDeleted) { if (state.isDeleted) onBack() }

    PosErrorWatcher(
        error = state.error,
        title = str(AdminStringKeys.DebtorDetailTitle),
        onClear = viewModel::clearError,
        retryAction = { viewModel.load(debtorId) },
    )

    PinnedHeaderForm(
        header = {
            DebtorDetailHeader(
                mayWrite = DebtorsAccess.of(LocalAccessPolicy.current).mayWrite,
                saveEnabled = state.canSave,
                isSaving = state.isSaving,
                onBack = onBack,
                onSave = viewModel::save,
            )
        },
        padding = AdminListScaffoldPadding,
    ) {
        DebtorDetailSections(state, viewModel)
    }

    if (state.isLoading) {
        PosLoadingOverlay(title = str(AdminStringKeys.DebtorDetailTitle))
    }
}

/**
 * The four editable sections, with **no chrome and no scrolling container of its own**.
 *
 * That is the whole point of the split: [DebtorDetailScreen] wraps this in its own
 * `verticalScroll`, while the Debitoren detail pane renders it inside a scaffold that already
 * scrolls. A second `verticalScroll` nested in the first measures to infinite height and crashes on
 * wasm, so the scrolling has to belong to the caller — which is why this is a separate composable
 * rather than a boolean on the screen.
 */
@Composable
internal fun DebtorDetailSections(
    state: DebtorDetailState,
    viewModel: DebtorDetailViewModel,
) {
    // Validate on Save (UI-E2-06): the first field at fault is scrolled into view and focused, in
    // whichever scroll container hosts the sections (this screen's or the Debitoren pane's).
    val focus = rememberPosFormFocus<DebtorFormField>()
    LaunchedEffect(state.validationAttempt) {
        if (state.validationAttempt > 0) focus.revealFirst(DebtorFormField.entries, state.fieldErrors)
    }
    val anchor: (DebtorFormField) -> Modifier = { field -> with(focus) { Modifier.formAnchor(field) } }
    IdentitySection(state, viewModel, anchor)
    AddressSection(state.form, viewModel)
    BookingSection(state, viewModel, anchor)
    StatusSection(state, viewModel)
}

@Composable
private fun DebtorDetailHeader(
    mayWrite: Boolean,
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
            text = str(AdminStringKeys.DebtorDetailTitle),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (mayWrite) {
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
private fun IdentitySection(
    state: DebtorDetailState,
    viewModel: DebtorDetailViewModel,
    anchor: (DebtorFormField) -> Modifier,
) {
    DebtorSection(AdminStringKeys.DebtorSectionIdentity) {
        PosFormGrid {
            Cell {
                DebtorField(
                    value = state.form.name,
                    onValueChange = viewModel::onNameChange,
                    labelKey = AdminStringKeys.DebtorFieldName,
                    errorKey = state.fieldErrors[DebtorFormField.NAME],
                    required = true,
                    modifier = anchor(DebtorFormField.NAME),
                )
            }
            Cell {
                DebtorField(state.form.legalName, viewModel::onLegalNameChange, AdminStringKeys.DebtorFieldLegalName)
            }
            Cell { DebtorField(state.form.vatId, viewModel::onVatIdChange, AdminStringKeys.DebtorFieldVatId) }
            Cell {
                DebtorField(state.form.taxNumber, viewModel::onTaxNumberChange, AdminStringKeys.DebtorFieldTaxNumber)
            }
            Cell {
                DebtorField(
                    value = state.form.email,
                    onValueChange = viewModel::onEmailChange,
                    labelKey = AdminStringKeys.DebtorFieldEmail,
                    keyboardType = KeyboardType.Email,
                    errorKey = state.fieldErrors[DebtorFormField.EMAIL],
                    modifier = anchor(DebtorFormField.EMAIL),
                )
            }
            Cell { DebtorField(state.form.phone, viewModel::onPhoneChange, AdminStringKeys.DebtorFieldPhone) }
            FullRow { DebtorField(state.form.notes, viewModel::onNotesChange, AdminStringKeys.DebtorFieldNotes) }
        }
    }
}

@Composable
private fun AddressSection(
    form: DebtorForm,
    viewModel: DebtorDetailViewModel,
) {
    DebtorSection(AdminStringKeys.DebtorSectionAddress) {
        PosFormGrid {
            FullRow { DebtorField(form.street, viewModel::onStreetChange, AdminStringKeys.DebtorFieldStreet) }
            Cell { DebtorField(form.zip, viewModel::onZipChange, AdminStringKeys.DebtorFieldZip) }
            Cell { DebtorField(form.city, viewModel::onCityChange, AdminStringKeys.DebtorFieldCity) }
            Cell { DebtorField(form.country, viewModel::onCountryChange, AdminStringKeys.DebtorFieldCountry) }
        }
    }
}

@Composable
private fun BookingSection(
    state: DebtorDetailState,
    viewModel: DebtorDetailViewModel,
    anchor: (DebtorFormField) -> Modifier,
) {
    DebtorSection(AdminStringKeys.DebtorSectionBooking) {
        PosFormGrid {
            Cell { DebtorField(state.form.debtorNo, viewModel::onDebtorNoChange, AdminStringKeys.DebtorFieldNumber) }
            FullRow {
                Text(
                    text = str(AdminStringKeys.DebtorNumberHint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Cell {
                DebtorField(
                    value = state.form.paymentTermsDays,
                    onValueChange = viewModel::onPaymentTermsChange,
                    labelKey = AdminStringKeys.DebtorFieldPaymentTerms,
                    keyboardType = KeyboardType.Number,
                    errorKey = state.fieldErrors[DebtorFormField.PAYMENT_TERMS],
                    modifier = anchor(DebtorFormField.PAYMENT_TERMS),
                )
            }
            Cell {
                DebtorField(
                    state.form.revenueAccountNo,
                    viewModel::onRevenueAccountChange,
                    AdminStringKeys.DebtorFieldRevenueAccount,
                )
            }
            Cell { DebtorField(state.form.buCode, viewModel::onBuCodeChange, AdminStringKeys.DebtorFieldBuCode) }
            FullRow {
                Text(
                    text = str(AdminStringKeys.DebtorBookingDefaultsHint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Cell {
                EinvoiceFormatPicker(
                    selected = state.form.einvoiceFormat,
                    onSelect = viewModel::onEinvoiceFormatChange,
                )
            }
        }
    }
}

/** NONE (plain PDF) or ZUGFERD (hybrid PDF/A-3 e-invoice, slice 4e). */
@Composable
private fun EinvoiceFormatPicker(
    selected: EInvoiceFormatPreference,
    onSelect: (EInvoiceFormatPreference) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        PosPickerField(
            value = str(einvoiceFormatLabel(selected)),
            onClick = { expanded = true },
            label = str(AdminStringKeys.DebtorFieldEinvoiceFormat),
            modifier = Modifier.fillMaxWidth(),
        )
        PosDropdown(expanded = expanded, onDismissRequest = { expanded = false }) {
            EInvoiceFormatPreference.entries.forEach { option ->
                PosDropdownItem(
                    text = str(einvoiceFormatLabel(option)),
                    selected = option == selected,
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun einvoiceFormatLabel(format: EInvoiceFormatPreference): StringKey =
    when (format) {
        EInvoiceFormatPreference.NONE -> AdminStringKeys.DebtorEinvoiceFormatNone
        EInvoiceFormatPreference.ZUGFERD -> AdminStringKeys.DebtorEinvoiceFormatZugferd
    }

/**
 * Active/inactive, plus deleting when — and only when — no invoice points at this debtor.
 *
 * The count is shown either way: it is the reason the delete button is or is not there, and an
 * absent button with no explanation is what sends an operator to support.
 */
@Composable
private fun StatusSection(
    state: DebtorDetailState,
    viewModel: DebtorDetailViewModel,
) {
    DebtorSection(AdminStringKeys.DebtorSectionStatus) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = str(AdminStringKeys.DebtorFieldActive), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Switch(
                checked = state.form.isActive,
                onCheckedChange = viewModel::onActiveChange,
                enabled = DebtorsAccess.of(LocalAccessPolicy.current).mayWrite,
            )
        }

        if (state.id != null) {
            Text(
                text = str(AdminStringKeys.DebtorInvoiceCount, state.salesInvoiceCount.toString()),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        when {
            state.id == null || !DebtorsAccess.of(LocalAccessPolicy.current).mayWrite -> Unit
            state.canDelete ->
                PosDangerOutlinedButton(
                    text = str(AdminStringKeys.DebtorDeleteButton),
                    onClick = viewModel::delete,
                    enabled = !state.isSaving,
                    modifier = Modifier.fillMaxWidth(),
                )
            else ->
                PosCard(status = PosCardStatus.Warning, modifier = Modifier.fillMaxWidth()) {
                    body {
                        Text(
                            text = str(AdminStringKeys.DebtorCannotDelete, state.salesInvoiceCount.toString()),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
        }
    }
}

/** A titled [PosCard]. Content goes through `body { }` — a plain trailing lambda renders nothing. */
@Composable
private fun DebtorSection(
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
private fun DebtorField(
    value: String,
    onValueChange: (String) -> Unit,
    labelKey: StringKey,
    errorKey: StringKey? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    required: Boolean = false,
    modifier: Modifier = Modifier,
) {
    PosTextField(
        value = value,
        onValueChange = onValueChange,
        label = if (required) requiredLabel(str(labelKey)) else str(labelKey),
        modifier = modifier.fillMaxWidth(),
        keyboardType = keyboardType,
        isError = errorKey != null,
        supportingText = errorKey?.let { key -> { Text(str(key)) } },
    )
}
