package org.example.project.admin.features.settings.presentation.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.auth.canManageProducts
import org.example.project.admin.core.presentation.components.DeleteConfirmDialog
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.auth.TokenStore
import org.example.project.core.domain.dto.payment.PaymentTypeDto
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.SettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosTextField
import org.example.project.ui.components.form.rememberPosFormFocus
import org.example.project.ui.components.form.requiredLabel
import org.example.project.ui.screens.settings.components.AddItemDialog
import org.example.project.ui.screens.settings.components.PaymentTypeManagementContent
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * Zahlungsarten — renders shared-ui's [PaymentTypeManagementContent], the same component composeApp's
 * Business Settings tab uses, so the till and the web admin cannot drift on which payment methods
 * exist or which of them count as cash. That second part is fiscally load-bearing: `isCash` decides
 * whether a payment lands in the Kassenbuch's cash reconciliation.
 *
 * `onSyncClick` re-fetches from the server rather than synchronising — adminApp has no local cache to
 * reconcile, unlike the till.
 *
 * Mutating callbacks are inert below MANAGER. The server enforces the same rule; this is the client
 * half of the two-level guard.
 *
 * The add/edit form is adminApp-local because composeApp's equivalent was a private composable on
 * its BUSINESS tab (removed in the till-only sweep). Every label it needs already exists in shared
 * `SettingsKeys`.
 */
@Composable
fun PaymentTypesSection(viewModel: PaymentTypeViewModel = koinViewModel()) {
    LaunchedEffect(Unit) { viewModel.refresh() }

    val language = LocalAppLanguage.current
    val tokenStore = koinInject<TokenStore>()
    val canEdit = tokenStore.canManageProducts
    var pendingDelete by remember { mutableStateOf<PaymentTypeDto.PaymentTypeResponseDto?>(null) }

    PosErrorWatcher(
        error = viewModel.state.error,
        title = str(SettingsKeys.paymentTypesSection),
        onClear = viewModel::clearError,
        retryAction = viewModel::refresh,
    )

    // The shared content is a plain Column with no scroll of its own (the till scrolls around it),
    // so this destination owns the only verticalScroll. Without it, payment types past the fold
    // were unreachable on a phone.
    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        PaymentTypeManagementContent(
            paymentTypes = viewModel.state.paymentTypes,
            isLoading = viewModel.state.isLoading,
            language = language,
            onAddClick = { if (canEdit) viewModel.showAddDialog() },
            onEditClick = { if (canEdit) viewModel.showEditDialog(it) },
            onDeleteClick = { id ->
                if (canEdit) pendingDelete = viewModel.state.paymentTypes.firstOrNull { it.id == id }
            },
            onSyncClick = viewModel::refresh,
        )
    }

    if (viewModel.state.showAddDialog) {
        val editing = viewModel.state.editingPaymentType
        PaymentTypeForm(
            existing = editing,
            isSaving = viewModel.state.isSaving,
            onDismiss = viewModel::hideDialog,
            onConfirm = { name, en, ch, isCash, createsReceivable ->
                if (editing == null) {
                    viewModel.create(name, en, ch, isCash, createsReceivable)
                } else {
                    viewModel.update(editing.id, name, en, ch, isCash, createsReceivable)
                }
            },
        )
    }

    // Confirm before deleting. composeApp deletes straight away, but adminApp's own screens
    // (ProductScreen, TablesScreen) all confirm first, and a payment type may be referenced by
    // historical bills.
    pendingDelete?.let { target ->
        DeleteConfirmDialog(
            entityType = str(AdminStringKeys.DeleteEntityPaymentType),
            entityName = target.name,
            onConfirm = {
                viewModel.delete(target.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun PaymentTypeForm(
    existing: PaymentTypeDto.PaymentTypeResponseDto?,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (name: String, en: String?, ch: String?, isCash: Boolean, createsReceivable: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var en by remember { mutableStateOf(existing?.en ?: "") }
    var ch by remember { mutableStateOf(existing?.ch ?: "") }
    var isCash by remember { mutableStateOf(existing?.isCash ?: false) }
    var createsReceivable by remember { mutableStateOf(existing?.createsReceivable ?: false) }

    // Transient validation state (not part of `dirty`): set by a refused Save, cleared per field on edit.
    var fieldErrors by remember { mutableStateOf<Map<PaymentTypeField, StringKey>>(emptyMap()) }
    var validationAttempt by remember { mutableStateOf(0) }
    val focus = rememberPosFormFocus<PaymentTypeField>()
    LaunchedEffect(validationAttempt) {
        if (validationAttempt > 0) focus.revealFirst(PaymentTypeField.entries, fieldErrors)
    }
    val nameError = fieldErrors[PaymentTypeField.NAME]?.let { str(it) }

    AddItemDialog(
        title = str(if (existing == null) SettingsKeys.addPaymentType else SettingsKeys.editPaymentType),
        onDismiss = onDismiss,
        onConfirm = {
            val errors = validatePaymentTypeInput(name)
            if (errors.isNotEmpty()) {
                fieldErrors = errors
                validationAttempt++
            } else {
                onConfirm(name.trim(), en.trim().ifBlank { null }, ch.trim().ifBlank { null }, isCash, createsReceivable)
            }
        },
        confirmText = str(CommonKeys.save),
        dismissText = str(CommonKeys.cancel),
        confirmLoading = isSaving,
        dirty =
            name != (existing?.name ?: "") ||
                en != (existing?.en ?: "") ||
                ch != (existing?.ch ?: "") ||
                isCash != (existing?.isCash ?: false) ||
                createsReceivable != (existing?.createsReceivable ?: false),
    ) {
        PosTextField(
            value = name,
            onValueChange = {
                name = it
                fieldErrors = fieldErrors - PaymentTypeField.NAME
            },
            label = requiredLabel(str(SettingsKeys.paymentTypeName)),
            modifier = with(focus) { Modifier.fillMaxWidth().formAnchor(PaymentTypeField.NAME) },
            isError = nameError != null,
            supportingText = nameError?.let { msg -> { Text(msg) } },
        )
        PosTextField(
            value = en,
            onValueChange = { en = it },
            label = str(SettingsKeys.paymentTypeEnglishName),
            modifier = Modifier.fillMaxWidth(),
        )
        PosTextField(
            value = ch,
            onValueChange = { ch = it },
            label = str(SettingsKeys.paymentTypeChineseName),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(str(SettingsKeys.isCashPayment), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = str(SettingsKeys.isCashPaymentDescription),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // raw Material 3 by design: no Pos* switch component exists
            Switch(checked = isCash, onCheckedChange = { isCash = it })
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(str(SettingsKeys.createsReceivablePayment), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = str(SettingsKeys.createsReceivablePaymentDescription),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = createsReceivable, onCheckedChange = { createsReceivable = it })
        }
    }
}
