package org.example.project.admin.features.documents.presentation.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.example.project.admin.core.presentation.format.currencySymbolForDisplay
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.toCanonicalMoneyOrNull
import org.example.project.ui.components.PosMoneyText
import org.example.project.ui.components.PosTextField
import org.example.project.ui.components.PosTextFieldType

/**
 * A single money field: an editable [PosTextFieldType.Price] field, or — when [readOnly] — the same
 * value rendered through [PosMoneyText] so a read-only view never shows the raw unformatted string.
 * The error state appears only while editing a non-blank value that fails
 * `String.toCanonicalMoneyOrNull()`, mirroring the check
 * [org.example.project.admin.features.documents.presentation.DocumentMetadataForm] is validated
 * against on save.
 *
 * It lived inside `InvoiceMetadataForm.kt` until Stage 4 deleted that file;
 * [BankStatementMetadataForm] is its remaining caller, so it needed a home of its own rather than
 * to be inlined there. Values are plain `String` throughout — never a `Double`. See
 * `platform/money/README.md`.
 */
@Composable
internal fun MoneyField(
    value: String,
    readOnly: Boolean,
    currency: String,
    label: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (readOnly) {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
        // A blank amount must render as "unrecorded" (the shared em-dash placeholder), never as
        // "0,00 €" — an earlier `value.ifBlank { "0" }` told a non-manager the amount was literally
        // zero. formatMoneyForDisplay already renders "—" for anything it can't parse (including
        // blank), so passing `value` straight through is enough.
        PosMoneyText(amount = value, currencyCode = currency.ifBlank { "EUR" })
        return
    }
    val isInvalid = value.isNotBlank() && value.toCanonicalMoneyOrNull() == null
    PosTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = modifier,
        textFieldType = PosTextFieldType.Price,
        suffix = { Text(currencySymbolForDisplay(currency)) },
        isError = isInvalid,
        supportingText =
            if (isInvalid) {
                { Text(str(AdminStringKeys.DocumentInvalidAmount)) }
            } else {
                null
            },
    )
}
