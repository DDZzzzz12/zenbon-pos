package org.example.project.admin.features.bank.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.bank.BankAccountDto
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosPickerField
import org.example.project.ui.components.form.rememberPosFormFocus
import org.example.project.ui.components.form.requiredLabel

/**
 * Files one parsed statement under one of our accounts.
 *
 * The statement's asserted IBAN is shown as read-only context: it is what the bank wrote and what
 * failed to match, so the operator decides against it rather than against a statement id.
 *
 * No `verticalScroll` here — [PosDialog] already scrolls its body.
 */
@Composable
fun AssignStatementDialog(
    statement: BankAccountDto.UnassignedStatementDto,
    accounts: List<BankAccountDto.ResponseDto>,
    onAssign: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf<BankAccountDto.ResponseDto?>(null) }
    var expanded by remember { mutableStateOf(false) }
    // A pick-one dialog is still a form: Assign stays enabled and, pressed with nothing picked,
    // flags the picker instead of doing nothing.
    var fieldErrors by remember { mutableStateOf<Map<AssignStatementField, StringKey>>(emptyMap()) }
    var validationAttempt by remember { mutableStateOf(0) }
    val focus = rememberPosFormFocus<AssignStatementField>()
    val formErrors = fieldErrors.entries.associate { (field, key) -> field to str(key) }
    LaunchedEffect(validationAttempt) {
        if (validationAttempt > 0) focus.revealFirst(AssignStatementField.entries, formErrors)
    }

    PosDialog(
        onDismiss = onDismiss,
        title = str(AdminStringKeys.BankAssignTitle),
        kind = PosDialogKind.Form,
        primary =
            PosDialogAction(
                text = str(AdminStringKeys.BankAssignAction),
                onClick = {
                    val errors = validateAssignStatement(selected)
                    fieldErrors = errors
                    if (errors.isNotEmpty()) {
                        validationAttempt++
                    } else {
                        selected?.let { account -> onAssign(account.id) }
                    }
                },
            ),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = str(AdminStringKeys.BankFieldIban),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = statement.assertedIban.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
            )

            Box {
                PosPickerField(
                    value = selected?.let { account -> accountLabel(account) }.orEmpty(),
                    onClick = { expanded = true },
                    label = requiredLabel(str(AdminStringKeys.BankTitle)),
                    modifier = with(focus) { Modifier.fillMaxWidth().formAnchor(AssignStatementField.ACCOUNT) },
                    isError = AssignStatementField.ACCOUNT in formErrors,
                    errorMessage = formErrors[AssignStatementField.ACCOUNT],
                )
                PosDropdown(expanded = expanded, onDismissRequest = { expanded = false }) {
                    accounts.forEach { account ->
                        PosDropdownItem(
                            text = accountLabel(account),
                            selected = account.id == selected?.id,
                            onClick = {
                                selected = account
                                fieldErrors = emptyMap()
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Client-side validation of the assign dialog: an account must be picked. Empty = assignable. */
internal fun validateAssignStatement(selected: BankAccountDto.ResponseDto?): Map<AssignStatementField, StringKey> =
    if (selected == null) mapOf(AssignStatementField.ACCOUNT to CommonKeys.fieldRequired) else emptyMap()

/** Bank and IBAN, both of them the account's own data — no localized text is assembled here. */
private fun accountLabel(account: BankAccountDto.ResponseDto): String =
    listOfNotNull(account.bankName?.takeIf { it.isNotBlank() }, account.iban).joinToString(" · ")
