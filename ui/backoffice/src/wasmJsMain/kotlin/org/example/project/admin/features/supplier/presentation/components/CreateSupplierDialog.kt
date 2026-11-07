package org.example.project.admin.features.supplier.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.supplier.presentation.SupplierForm
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind
import org.example.project.ui.components.PosTextField
import org.example.project.ui.components.form.rememberPosFormFocus
import org.example.project.ui.components.form.requiredLabel
import org.example.project.ui.guard.rememberGuardedDismiss
import org.example.project.ui.theme.PosSpacing

/**
 * Creates a creditor, from wherever one turns out to be missing.
 *
 * Until now adminApp had **no** way to add a creditor at all: `SupplierForm` carried only
 * `toUpdateRequest`, and every supplier in the system arrived because the server minted one while
 * resolving an invoice. It lives in the supplier feature rather than beside the capture form that
 * first needed it because what it creates is a supplier, not a capture.
 *
 * ## It is not what Lieferanten should use
 *
 * That screen already has a full master-data form in `SupplierDetailScreen` — legal name, tax
 * number, e-mail, phone, notes, defaults — bound to a non-null id, so today it can only edit. Giving
 * it a create button should follow the Debitoren precedent instead: make the id nullable, add a
 * create route, and let the whole form serve both. Six fields are the right ask in the middle of
 * capturing an invoice and the wrong ask on the screen whose job is the supplier record itself.
 *
 * ## What it asks for, and what it does not
 *
 * Name, VAT id, creditor number and address. Those are the fields that decide **identity and
 * booking**: the VAT id is the strongest key the server's resolution matches on, the creditor number
 * is what the DATEV export writes, and the address is what an Eingangsrechnung has to carry. Payment
 * terms, e-mail, cost centres and notes are deliberately absent — an operator filling this in the
 * middle of capturing an invoice has the invoice in front of them, not the supplier's file, and
 * every one of those fields is editable afterwards in Lieferanten.
 *
 * Errors render inline rather than through `PosErrorWatcher`: a taken VAT id belongs next to the box
 * that has to change, and a second modal stacked over this one would hide the form being corrected.
 */

/** Focus key of the name field, the one field Create can flag. */
private const val NAME_FIELD = "name"

@Composable
fun CreateSupplierDialog(
    initialName: String,
    isSaving: Boolean,
    error: UiError?,
    onDismiss: () -> Unit,
    onCreate: (SupplierForm) -> Unit,
) {
    // Seeded from what was already typed, so the operator does not retype the name they just entered
    // — and so the name that reaches the server is the one the invoice actually shows.
    var form by remember { mutableStateOf(SupplierForm(name = initialName.trim())) }
    // Validate on press (UI-E2-06): the name is marked only once Create was pressed without one.
    var attempt by remember { mutableStateOf(0) }
    val nameMissing = attempt > 0 && form.name.isBlank()
    val focus = rememberPosFormFocus<String>()
    LaunchedEffect(attempt) {
        if (attempt > 0 && form.name.isBlank()) focus.revealFirst(listOf(NAME_FIELD), mapOf(NAME_FIELD to true))
    }
    val close =
        rememberGuardedDismiss(
            dirty = form != SupplierForm(name = initialName.trim()),
            onClose = onDismiss,
        )

    PosDialog(
        onDismiss = close,
        title = str(AdminStringKeys.SupplierCreateTitle),
        kind = PosDialogKind.Form,
        primary =
            PosDialogAction(
                text = str(AdminStringKeys.SupplierCreateAction),
                onClick = { if (form.name.isBlank()) attempt++ else onCreate(form) },
                isLoading = isSaving,
            ),
        secondary = PosDialogAction(str(CommonKeys.cancel), close, enabled = !isSaving),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(PosSpacing.md)) {
            Text(
                text = str(AdminStringKeys.SupplierCreateExplainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            PosTextField(
                value = form.name,
                onValueChange = { v -> form = form.copy(name = v) },
                label = requiredLabel(str(AdminStringKeys.SupplierFieldName)),
                modifier = with(focus) { Modifier.fillMaxWidth().formAnchor(NAME_FIELD) },
                enabled = !isSaving,
                isError = nameMissing,
                supportingText = if (nameMissing) ({ Text(str(CommonKeys.fieldRequired)) }) else null,
            )
            PosTextField(
                value = form.vatId,
                onValueChange = { v -> form = form.copy(vatId = v) },
                label = str(AdminStringKeys.SupplierFieldVatId),
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSaving,
            )
            PosTextField(
                value = form.creditorNo,
                onValueChange = { v -> form = form.copy(creditorNo = v) },
                label = str(AdminStringKeys.SupplierFieldCreditorNo),
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSaving,
            )
            PosTextField(
                value = form.street,
                onValueChange = { v -> form = form.copy(street = v) },
                label = str(AdminStringKeys.SupplierFieldStreet),
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSaving,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(PosSpacing.md)) {
                PosTextField(
                    value = form.zip,
                    onValueChange = { v -> form = form.copy(zip = v) },
                    label = str(AdminStringKeys.SupplierFieldZip),
                    modifier = Modifier.weight(1f),
                    enabled = !isSaving,
                )
                PosTextField(
                    value = form.city,
                    onValueChange = { v -> form = form.copy(city = v) },
                    label = str(AdminStringKeys.SupplierFieldCity),
                    modifier = Modifier.weight(2f),
                    enabled = !isSaving,
                )
            }

            if (error != null) {
                Text(
                    text = str(error.messageKey, *error.messageArgs.toTypedArray()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
