package org.example.project.admin.features.documents.presentation.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.documents.presentation.DocumentMetadataField
import org.example.project.admin.features.documents.presentation.DocumentMetadataForm
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosDateField
import org.example.project.ui.components.PosTextField

/**
 * Editable metadata for [org.example.project.core.domain.enums.document.DocumentType.BANK_STATEMENT]
 * documents. Money fields ([DocumentMetadataForm.openingBalance]/[DocumentMetadataForm.closingBalance])
 * reuse the [MoneyField] helper in `MoneyField.kt` — plain `String`, `PosTextFieldType.Price`
 * while editing, [org.example.project.ui.components.PosMoneyText] while [readOnly].
 *
 * The date fields ([DocumentMetadataForm.statementDate]/[DocumentMetadataForm.periodStart]/
 * [DocumentMetadataForm.periodEnd]) are [PosDateField]s: the form holds the ISO `yyyy-MM-dd` the
 * server parses (it 400s on anything else), the field shows it in the UI language at rest.
 */
@Composable
fun BankStatementMetadataForm(
    form: DocumentMetadataForm,
    readOnly: Boolean,
    onChange: ((DocumentMetadataForm) -> DocumentMetadataForm) -> Unit,
    modifier: Modifier = Modifier,
    /** What the last refused Save flagged. */
    errors: Map<DocumentMetadataField, StringKey> = emptyMap(),
    /** Scroll/focus target of each validated field. */
    anchor: (DocumentMetadataField) -> Modifier = { Modifier },
) {
    val hint = str(CommonKeys.dateFormatHint)
    PosCard(modifier = modifier.fillMaxWidth()) {
        body {
            Text(
                text = str(AdminStringKeys.DocumentSectionBankStatement),
                style = MaterialTheme.typography.titleMedium,
            )
            PosTextField(
                value = form.bankName,
                onValueChange = { newValue -> onChange { it.copy(bankName = newValue) } },
                label = str(AdminStringKeys.DocumentsFieldBank),
                enabled = !readOnly,
            )
            PosTextField(
                value = form.bic,
                onValueChange = { newValue -> onChange { it.copy(bic = newValue) } },
                label = str(AdminStringKeys.DocumentsFieldBic),
                enabled = !readOnly,
            )
            PosTextField(
                value = form.accountNumber,
                onValueChange = { newValue -> onChange { it.copy(accountNumber = newValue) } },
                label = str(AdminStringKeys.DocumentsFieldAccountNumber),
                enabled = !readOnly,
            )
            PosTextField(
                value = form.statementNumber,
                onValueChange = { newValue -> onChange { it.copy(statementNumber = newValue) } },
                label = str(AdminStringKeys.DocumentsFieldStatementNumber),
                enabled = !readOnly,
            )
            PosDateField(
                value = form.statementDate,
                onValueChange = { newValue -> onChange { it.copy(statementDate = newValue) } },
                label = str(AdminStringKeys.DocumentsFieldStatementDate),
                enabled = !readOnly,
                isError = errors[DocumentMetadataField.STATEMENT_DATE] != null,
                supportingText = errors[DocumentMetadataField.STATEMENT_DATE]?.let { str(it, hint) },
                modifier = anchor(DocumentMetadataField.STATEMENT_DATE),
            )
            PosDateField(
                value = form.periodStart,
                onValueChange = { newValue -> onChange { it.copy(periodStart = newValue) } },
                label = str(AdminStringKeys.DocumentsFieldPeriodStart),
                enabled = !readOnly,
                isError = errors[DocumentMetadataField.PERIOD_START] != null,
                supportingText = errors[DocumentMetadataField.PERIOD_START]?.let { str(it, hint) },
                modifier = anchor(DocumentMetadataField.PERIOD_START),
            )
            PosDateField(
                value = form.periodEnd,
                onValueChange = { newValue -> onChange { it.copy(periodEnd = newValue) } },
                label = str(AdminStringKeys.DocumentsFieldPeriodEnd),
                enabled = !readOnly,
                isError = errors[DocumentMetadataField.PERIOD_END] != null,
                supportingText = errors[DocumentMetadataField.PERIOD_END]?.let { str(it, hint) },
                modifier = anchor(DocumentMetadataField.PERIOD_END),
            )
            MoneyField(
                value = form.openingBalance,
                readOnly = readOnly,
                currency = form.currency,
                label = str(AdminStringKeys.DocumentsFieldOpeningBalance),
                onValueChange = { newValue -> onChange { it.copy(openingBalance = newValue) } },
                modifier = anchor(DocumentMetadataField.OPENING_BALANCE),
            )
            MoneyField(
                value = form.closingBalance,
                readOnly = readOnly,
                currency = form.currency,
                label = str(AdminStringKeys.DocumentsFieldClosingBalance),
                onValueChange = { newValue -> onChange { it.copy(closingBalance = newValue) } },
                modifier = anchor(DocumentMetadataField.CLOSING_BALANCE),
            )
        }
    }
}
