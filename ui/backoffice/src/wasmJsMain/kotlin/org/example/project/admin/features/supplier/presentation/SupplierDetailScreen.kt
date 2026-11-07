package org.example.project.admin.features.supplier.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.components.PinnedHeaderForm
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.auth.LocalAccessPolicy
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardStatus
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosLoadingOverlay
import org.example.project.ui.components.PosTextField
import org.example.project.ui.components.form.rememberPosFormFocus
import org.example.project.ui.components.form.requiredLabel
import org.example.project.ui.components.formatMoneyForDisplay
import org.example.project.ui.guard.rememberDirtySource
import org.koin.compose.viewmodel.koinViewModel

/**
 * One creditor's master data: identity, address, booking defaults and the IBANs seen on its
 * invoices.
 *
 * Saving a provisional creditor confirms it server-side, so the action is labelled as a
 * confirmation rather than a save while [SupplierDetailState.isProvisional] holds.
 *
 * The page scrolls as one `Column`; the four sections are plain [PosCard]s stacked in it, never a
 * nested scrolling container.
 */
@Composable
fun SupplierDetailScreen(
    supplierId: Long,
    onBack: () -> Unit,
) {
    val viewModel: SupplierDetailViewModel = koinViewModel()
    val state = viewModel.state

    LaunchedEffect(supplierId) { viewModel.load(supplierId) }

    rememberDirtySource(dirty = state.isDirty, onDiscard = viewModel::discardDraft)

    PosErrorWatcher(
        error = state.error,
        title = str(AdminStringKeys.SupplierDetailTitle),
        onClear = viewModel::clearError,
        retryAction = { viewModel.load(supplierId) },
    )

    PinnedHeaderForm(
        header = {
            SupplierDetailHeader(
                isProvisional = state.isProvisional,
                saveEnabled = state.canSave,
                isSaving = state.isSaving,
                onBack = onBack,
                onSave = viewModel::save,
            )
        },
    ) {
        if (state.isProvisional) {
            ProvisionalBanner()
        }

        // Validate on Save: the name (required) and a malformed e-mail are marked; the first is
        // scrolled to and focused.
        val focus = rememberPosFormFocus<String>()
        LaunchedEffect(state.validationAttempt) {
            if (state.validationAttempt > 0) {
                val errors = listOfNotNull(state.nameError?.let { NAME_FIELD to it }, state.emailError?.let { EMAIL_FIELD to it }).toMap()
                focus.revealFirst(listOf(NAME_FIELD, EMAIL_FIELD), errors)
            }
        }
        IdentitySection(
            state = state,
            viewModel = viewModel,
            nameAnchor = with(focus) { Modifier.formAnchor(NAME_FIELD) },
            emailAnchor = with(focus) { Modifier.formAnchor(EMAIL_FIELD) },
        )
        SpendSection(state)
        AddressSection(state.form, viewModel)
        BookingSection(state.form, viewModel)
        IbanSection(state.knownIbans)
    }

    if (state.isLoading) {
        PosLoadingOverlay(title = str(AdminStringKeys.SupplierDetailTitle))
    }
}

/** The focus keys of the two fields a Save can flag. */
private const val NAME_FIELD = "name"
private const val EMAIL_FIELD = "email"

@Composable
private fun SupplierDetailHeader(
    isProvisional: Boolean,
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
            text = str(AdminStringKeys.SupplierDetailTitle),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // Confirming IS saving server-side, so this is one button with two labels, not two buttons.
        // CREDITOR_WRITE on the server (PUT /api/suppliers/{id}); hidden for a read-only group (FO-03).
        if (SupplierAccess.of(LocalAccessPolicy.current).mayWrite) {
            PosButton(
                text = if (isProvisional) str(AdminStringKeys.SupplierConfirmAction) else str(CommonKeys.save),
                onClick = onSave,
                enabled = saveEnabled,
                isLoading = isSaving,
            )
        }
    }
}

@Composable
private fun ProvisionalBanner() {
    PosCard(status = PosCardStatus.Warning) {
        body {
            Text(
                text = str(AdminStringKeys.SupplierProvisionalExplainer),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun IdentitySection(
    state: SupplierDetailState,
    viewModel: SupplierDetailViewModel,
    nameAnchor: Modifier,
    emailAnchor: Modifier,
) {
    SupplierSection(AdminStringKeys.SupplierSectionIdentity) {
        SupplierField(
            value = state.form.name,
            onValueChange = viewModel::onNameChange,
            labelKey = AdminStringKeys.SupplierFieldName,
            errorKey = state.nameError,
            required = true,
            modifier = nameAnchor,
        )
        SupplierField(state.form.legalName, viewModel::onLegalNameChange, AdminStringKeys.SupplierFieldLegalName)
        SupplierField(state.form.vatId, viewModel::onVatIdChange, AdminStringKeys.SupplierFieldVatId)
        SupplierField(state.form.taxNumber, viewModel::onTaxNumberChange, AdminStringKeys.SupplierFieldTaxNumber)
        SupplierField(
            value = state.form.email,
            onValueChange = viewModel::onEmailChange,
            labelKey = AdminStringKeys.SupplierFieldEmail,
            errorKey = state.emailError,
            modifier = emailAnchor,
        )
        SupplierField(state.form.phone, viewModel::onPhoneChange, AdminStringKeys.SupplierFieldPhone)
        SupplierField(state.form.notes, viewModel::onNotesChange, AdminStringKeys.SupplierFieldNotes)
    }
}

@Composable
private fun AddressSection(
    form: SupplierForm,
    viewModel: SupplierDetailViewModel,
) {
    SupplierSection(AdminStringKeys.SupplierSectionAddress) {
        SupplierField(form.street, viewModel::onStreetChange, AdminStringKeys.SupplierFieldStreet)
        SupplierField(form.zip, viewModel::onZipChange, AdminStringKeys.SupplierFieldZip)
        SupplierField(form.city, viewModel::onCityChange, AdminStringKeys.SupplierFieldCity)
        SupplierField(form.country, viewModel::onCountryChange, AdminStringKeys.SupplierFieldCountry)
    }
}

@Composable
private fun BookingSection(
    form: SupplierForm,
    viewModel: SupplierDetailViewModel,
) {
    SupplierSection(AdminStringKeys.SupplierSectionBooking) {
        SupplierField(form.creditorNo, viewModel::onCreditorNoChange, AdminStringKeys.SupplierFieldCreditorNo)
        SupplierField(form.defaultAccountNo, viewModel::onDefaultAccountNoChange, AdminStringKeys.SupplierFieldAccountNo)
        SupplierField(form.defaultBuCode, viewModel::onDefaultBuCodeChange, AdminStringKeys.SupplierFieldBuCode)
        SupplierField(
            form.defaultCostCenter,
            viewModel::onDefaultCostCenterChange,
            AdminStringKeys.SupplierFieldCostCenter,
        )
        SupplierField(
            value = form.paymentTermsDays,
            onValueChange = viewModel::onPaymentTermsChange,
            labelKey = AdminStringKeys.SupplierFieldPaymentTerms,
            keyboardType = KeyboardType.Number,
        )
    }
}

/**
 * The IBANs seen on this creditor's invoices. Read-only by design: they are observed facts, not
 * something an operator types — hence the explainer under them either way.
 */
@Composable
private fun IbanSection(knownIbans: List<String>) {
    SupplierSection(AdminStringKeys.SupplierSectionIbans) {
        if (knownIbans.isEmpty()) {
            Text(
                text = str(AdminStringKeys.SupplierNoIbans),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            knownIbans.forEach { iban ->
                Text(text = iban, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(
            text = str(AdminStringKeys.SupplierIbansExplainer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The two server-computed aggregates — what we still owe this creditor, and what we have paid them
 * this calendar year. Read-only: they are derived from the invoices and allocations, not typed.
 */
@Composable
private fun SpendSection(state: SupplierDetailState) {
    SupplierSection(AdminStringKeys.SupplierSectionSpend) {
        SpendRow(
            label = str(AdminStringKeys.SuppliersColumnOpenTotal),
            value = formatMoneyForDisplay(state.openTotal, state.currency),
        )
        SpendRow(
            label = str(AdminStringKeys.SuppliersColumnYtdSpend),
            value = formatMoneyForDisplay(state.ytdSpend, state.currency),
        )
    }
}

@Composable
private fun SpendRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** A titled [PosCard]. Content goes through `body { }` — a plain trailing lambda renders nothing. */
@Composable
private fun SupplierSection(
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
private fun SupplierField(
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
