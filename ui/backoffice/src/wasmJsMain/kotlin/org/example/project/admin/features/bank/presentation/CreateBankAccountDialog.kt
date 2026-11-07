package org.example.project.admin.features.bank.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind
import org.example.project.ui.components.PosTextField
import org.example.project.ui.components.form.rememberPosFormFocus
import org.example.project.ui.components.form.requiredLabel
import org.example.project.ui.guard.rememberGuardedDismiss

/**
 * Creates one of our own bank accounts.
 *
 * [createError] is a field-level error and is rendered under the IBAN: both cases it can carry — a
 * blank IBAN and one that already belongs to another account — are about that input.
 *
 * No `verticalScroll` here: [PosDialog] already scrolls its body, and nesting a second scroller
 * crashes the wasm measure pass with an infinite height.
 */
@Composable
fun CreateBankAccountDialog(
    createError: StringKey?,
    isBusy: Boolean,
    onSubmit: (BankAccountDto.CreateRequestDto) -> Unit,
    onDismiss: () -> Unit,
    /** Bumped by the view model on every refused Save; each bump focuses the IBAN. */
    validationAttempt: Int = 0,
    /** The IBAN changed — its error no longer describes what is typed. */
    onIbanEdited: () -> Unit = {},
) {
    var iban by remember { mutableStateOf("") }
    var bic by remember { mutableStateOf("") }
    var bankName by remember { mutableStateOf("") }
    var accountHolder by remember { mutableStateOf("") }
    var ledgerAccount by remember { mutableStateOf("") }

    val focus = rememberPosFormFocus<BankAccountField>()
    val formErrors = createError?.let { mapOf(BankAccountField.IBAN to str(it)) }.orEmpty()
    LaunchedEffect(validationAttempt) {
        if (validationAttempt > 0) focus.revealFirst(BankAccountField.entries, formErrors)
    }

    val close =
        rememberGuardedDismiss(
            dirty = isCreateBankAccountDirty(iban, bic, bankName, accountHolder, ledgerAccount),
            onClose = onDismiss,
        )
    PosDialog(
        onDismiss = close,
        title = str(AdminStringKeys.BankCreateTitle),
        kind = PosDialogKind.Form,
        primary =
            PosDialogAction(
                text = str(AdminStringKeys.BankCreateSubmit),
                onClick = {
                    onSubmit(
                        BankAccountDto.CreateRequestDto(
                            iban = iban.trim(),
                            bic = bic.trim().ifBlank { null },
                            bankName = bankName.trim().ifBlank { null },
                            accountHolder = accountHolder.trim().ifBlank { null },
                            ledgerAccount = ledgerAccount.trim().ifBlank { null },
                        ),
                    )
                },
                isLoading = isBusy,
            ),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PosTextField(
                value = iban,
                onValueChange = {
                    iban = it
                    onIbanEdited()
                },
                label = requiredLabel(str(AdminStringKeys.BankFieldIban)),
                modifier = with(focus) { Modifier.fillMaxWidth().formAnchor(BankAccountField.IBAN) },
                isError = BankAccountField.IBAN in formErrors,
                supportingText = formErrors[BankAccountField.IBAN]?.let { msg -> { Text(msg) } },
            )
            PosTextField(
                value = bic,
                onValueChange = { bic = it },
                label = str(AdminStringKeys.BankFieldBic),
                modifier = Modifier.fillMaxWidth(),
            )
            PosTextField(
                value = bankName,
                onValueChange = { bankName = it },
                label = str(AdminStringKeys.BankFieldBankName),
                modifier = Modifier.fillMaxWidth(),
            )
            PosTextField(
                value = accountHolder,
                onValueChange = { accountHolder = it },
                label = str(AdminStringKeys.BankFieldAccountHolder),
                modifier = Modifier.fillMaxWidth(),
            )
            PosTextField(
                value = ledgerAccount,
                onValueChange = { ledgerAccount = it },
                label = str(AdminStringKeys.BankFieldLedgerAccount),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** The dialog opens empty, so it holds unsaved input as soon as any of its five fields is non-blank. */
internal fun isCreateBankAccountDirty(
    iban: String,
    bic: String,
    bankName: String,
    accountHolder: String,
    ledgerAccount: String,
): Boolean = listOf(iban, bic, bankName, accountHolder, ledgerAccount).any { it.isNotBlank() }
