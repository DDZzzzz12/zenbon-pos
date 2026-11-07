package org.example.project.admin.features.documents.presentation.capture.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.example.project.admin.core.presentation.format.currencySymbolForDisplay
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.documents.presentation.capture.InvoiceCaptureField
import org.example.project.admin.features.documents.presentation.capture.InvoiceCaptureForm
import org.example.project.admin.features.documents.presentation.capture.InvoiceTypeCode
import org.example.project.admin.features.documents.presentation.capture.invoiceTypeCodeLabelKey
import org.example.project.core.domain.dto.supplier.SupplierDto
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosDateField
import org.example.project.ui.components.PosSegmentedButtonRow
import org.example.project.ui.components.PosTextFieldType
import org.example.project.ui.components.form.requiredLabel

/**
 * The five required fields plus Rechnungsart and its conditional BT-25 reference.
 *
 * Everything here is supplier-asserted content, so the whole section goes read-only when
 * [supplierReadOnly] — the invoice came from the supplier's own e-invoice and `PUT /api/invoices/{id}`
 * rejects a supplier-field write with 409. The banner says why, because a silently disabled form is
 * indistinguishable from a broken one.
 *
 * [enabled] and [supplierReadOnly] are separate on purpose: a viewer below `canManageProducts` also
 * gets a disabled section, but telling them the invoice came from an e-invoice would be a lie.
 *
 * `PosCard` forces `fillMaxWidth` internally and takes a DSL receiver: content goes in `body { }`, and
 * a plain lambda renders nothing at all.
 */
@Composable
internal fun InvoiceContentSection(
    form: InvoiceCaptureForm,
    errors: Map<InvoiceCaptureField, StringKey>,
    enabled: Boolean,
    supplierReadOnly: Boolean,
    supplierSuggestions: List<SupplierDto.ResponseDto>,
    matchedSupplier: SupplierDto.ResponseDto?,
    supplierSearchAnswered: Boolean,
    onChange: ((InvoiceCaptureForm) -> InvoiceCaptureForm) -> Unit,
    onPickSupplier: (SupplierDto.ResponseDto) -> Unit,
    onCreateSupplier: () -> Unit,
    currencyCode: String,
    modifier: Modifier = Modifier,
    /** Scroll/focus target of each validated field, so a refused save can reveal the first error. */
    anchor: (InvoiceCaptureField) -> Modifier = { Modifier },
) {
    PosCard(modifier = modifier.fillMaxWidth()) {
        body {
            Text(str(AdminStringKeys.InvoiceCaptureSectionContent), style = MaterialTheme.typography.titleMedium)
            if (supplierReadOnly) {
                Text(
                    text = str(AdminStringKeys.InvoiceCaptureReadOnlyEInvoice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(str(AdminStringKeys.InvoiceCaptureFieldInvoiceType), style = MaterialTheme.typography.labelMedium)
            val selected = InvoiceTypeCode.fromCode(form.invoiceTypeCode)
            if (selected == null) {
                // A parsed invoice carrying a code the form does not offer keeps it: the selector is a
                // capture restriction, not a storage constraint. Show it, do not rewrite it to 380.
                Text(form.invoiceTypeCode, style = MaterialTheme.typography.bodyMedium)
            } else {
                PosSegmentedButtonRow(
                    options = InvoiceTypeCode.entries,
                    selectedOption = selected,
                    onOptionSelected = { type -> onChange { it.copy(invoiceTypeCode = type.code) } },
                    enabled = enabled,
                    optionLabel = { type -> str(invoiceTypeCodeLabelKey(type)) },
                )
                if (selected.requiresPrecedingReference) {
                    CaptureTextField(
                        value = form.precedingInvoiceReference,
                        onValueChange = { v -> onChange { it.copy(precedingInvoiceReference = v) } },
                        label = str(AdminStringKeys.InvoiceCaptureFieldPrecedingReference),
                        errorKey = errors[InvoiceCaptureField.PRECEDING_REFERENCE],
                        enabled = enabled,
                        modifier = anchor(InvoiceCaptureField.PRECEDING_REFERENCE),
                    )
                }
            }

            CaptureTextField(
                value = form.invoiceNumber,
                onValueChange = { v -> onChange { it.copy(invoiceNumber = v) } },
                label = requiredLabel(str(AdminStringKeys.DocumentsFieldInvoiceNumber)),
                errorKey = errors[InvoiceCaptureField.INVOICE_NUMBER],
                enabled = enabled,
                modifier = anchor(InvoiceCaptureField.INVOICE_NUMBER),
            )
            PosDateField(
                value = form.issueDate,
                onValueChange = { v -> onChange { it.copy(issueDate = v) } },
                label = requiredLabel(str(AdminStringKeys.DocumentsFieldInvoiceDate)),
                enabled = true,
                readOnly = !enabled,
                isError = errors[InvoiceCaptureField.ISSUE_DATE] != null,
                supportingText = errors[InvoiceCaptureField.ISSUE_DATE]?.let { str(it, str(CommonKeys.dateFormatHint)) },
                modifier = anchor(InvoiceCaptureField.ISSUE_DATE),
            )
            SupplierNameField(
                value = form.sellerName,
                onValueChange = { v -> onChange { it.copy(sellerName = v) } },
                errorKey = errors[InvoiceCaptureField.SELLER_NAME],
                suggestions = supplierSuggestions,
                matched = matchedSupplier,
                searchAnswered = supplierSearchAnswered,
                onPick = onPickSupplier,
                onCreate = onCreateSupplier,
                enabled = enabled,
                modifier = anchor(InvoiceCaptureField.SELLER_NAME),
            )
            CaptureTextField(
                value = form.grossTotal,
                onValueChange = { v -> onChange { it.copy(grossTotal = v) } },
                label = requiredLabel(str(AdminStringKeys.DocumentsFieldGrossAmount)),
                errorKey = errors[InvoiceCaptureField.GROSS_TOTAL],
                enabled = enabled,
                modifier = anchor(InvoiceCaptureField.GROSS_TOTAL),
                type = PosTextFieldType.Price,
                suffix = currencySymbolForDisplay(currencyCode),
            )
        }
    }
}
