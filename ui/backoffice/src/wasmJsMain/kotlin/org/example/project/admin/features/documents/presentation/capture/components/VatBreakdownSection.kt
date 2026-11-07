package org.example.project.admin.features.documents.presentation.capture.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.format.currencySymbolForDisplay
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.documents.presentation.capture.InvoiceCaptureField
import org.example.project.admin.features.documents.presentation.capture.InvoiceCaptureForm
import org.example.project.admin.features.documents.presentation.capture.VatRowForm
import org.example.project.admin.features.documents.presentation.capture.deriveVatRow
import org.example.project.admin.features.documents.presentation.capture.validateCapture
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.toCanonicalMoneyOrNull
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosMoneyText
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosTextFieldType
import org.example.project.ui.theme.LocalPosColors
import org.example.project.ui.theme.PosSpacing

/**
 * The VAT breakdown: one row by default, more for the mixed-rate case.
 *
 * With a single row the operator enters only the rate — the row's gross is the invoice's
 * [InvoiceCaptureForm.grossTotal], so the amount is typed once and `Σ(row gross) == gross` holds by
 * construction. From two rows up, each row's own gross appears and the sum rule becomes a real check;
 * DATEV rejects the whole import when the two disagree.
 *
 * Net and VAT are **read-only readouts**, derived by `deriveVatRow` with the VAT taken by subtraction.
 * They are never editable: three independently typed numbers can disagree, and the export re-adds the
 * pair to produce the ledger amount.
 *
 * The per-row `Row` holds two weighted `PosTextField`s and no `PosCard`. `PosCard` and `PeriodStepper`
 * force `fillMaxWidth` internally and would measure a weighted sibling to **0 px** — it renders
 * nothing, silently, with no error.
 */
@Composable
internal fun VatBreakdownSection(
    form: InvoiceCaptureForm,
    errors: Map<InvoiceCaptureField, StringKey>,
    rowErrors: Map<Int, StringKey>,
    enabled: Boolean,
    currencyCode: String,
    onChange: ((InvoiceCaptureForm) -> InvoiceCaptureForm) -> Unit,
    onAddRow: () -> Unit,
    onRemoveRow: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val multiRate = form.vatRows.size > 1
    PosCard(modifier = modifier.fillMaxWidth()) {
        body {
            Text(str(AdminStringKeys.InvoiceCaptureSectionVat), style = MaterialTheme.typography.titleMedium)

            form.vatRows.forEachIndexed { index, row ->
                // Each rate is its own bordered card, as in the Belegfluss design: rate (and, from two rows
                // up, the row gross) with a remove cross, then the derived Netto / USt / Brutto readouts.
                Column(
                    modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant).padding(PosSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(PosSpacing.md),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.weight(1f)) {
                            CaptureTextField(
                                value = row.rate,
                                onValueChange = { v -> onChange { f -> f.replaceRow(index) { it.copy(rate = v) } } },
                                label = str(AdminStringKeys.InvoiceCaptureFieldVatRate),
                                errorKey = rowErrors[index],
                                enabled = enabled,
                                type = PosTextFieldType.Percentage,
                                suffix = "%",
                            )
                        }
                        if (multiRate) {
                            Box(Modifier.weight(1f)) {
                                CaptureTextField(
                                    value = row.gross,
                                    onValueChange = { v -> onChange { f -> f.replaceRow(index) { it.copy(gross = v) } } },
                                    label = str(AdminStringKeys.InvoiceCaptureFieldRowGross),
                                    errorKey = null,
                                    enabled = enabled,
                                    type = PosTextFieldType.Price,
                                    suffix = currencySymbolForDisplay(currencyCode),
                                )
                            }
                        }
                        if (multiRate && enabled) {
                            PosIconButton(
                                icon = Icons.Default.Close,
                                contentDescription = str(AdminStringKeys.InvoiceCaptureRemoveVatRow),
                                onClick = { onRemoveRow(index) },
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }

                    val gross = form.grossFor(index)
                    val derived = deriveVatRow(rate = row.rate, gross = gross)
                    if (derived != null) {
                        val currency = currencyCode.ifBlank { "EUR" }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PosSpacing.lg)) {
                            Readout(AdminStringKeys.SalesInvoicesNet, derived.taxableAmount, currency)
                            Readout(AdminStringKeys.SalesInvoicesVat, derived.vatAmount, currency)
                            gross.toCanonicalMoneyOrNull()?.let { Readout(AdminStringKeys.SalesInvoicesGross, it, currency) }
                        }
                    }

                    CaptureTextField(
                        value = row.description,
                        onValueChange = { v -> onChange { f -> f.replaceRow(index) { it.copy(description = v) } } },
                        label = str(AdminStringKeys.InvoiceCaptureFieldRowDescription),
                        errorKey = null,
                        enabled = enabled,
                    )
                }
            }

            if (vatSumMatches(form, errors)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PosSpacing.md)) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = LocalPosColors.current.posAvailable,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = str(AdminStringKeys.BelegflowVatSumOk),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalPosColors.current.posAvailable,
                    )
                }
            }

            errors[InvoiceCaptureField.VAT_ROW_SUM]?.let { key ->
                Text(
                    text = str(key),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (enabled) {
                PosSecondaryButton(text = str(AdminStringKeys.InvoiceCaptureAddVatRow), onClick = onAddRow)
            }
        }
    }
}

/** Replaces one row in place, keeping the list's order — rows are identified by index only. */
private fun InvoiceCaptureForm.replaceRow(
    index: Int,
    mutate: (VatRowForm) -> VatRowForm,
): InvoiceCaptureForm = copy(vatRows = vatRows.mapIndexed { i, row -> if (i == index) mutate(row) else row })

/** One read-only figure of a VAT row: its caption over the money amount. */
@Composable
private fun RowScope.Readout(
    labelKey: StringKey,
    amount: String,
    currency: String,
) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PosSpacing.xs)) {
        Text(str(labelKey), style = MaterialTheme.typography.labelMedium)
        PosMoneyText(amount = amount, currencyCode = currency, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * True when the rows add up to the invoice gross right now: every row derives, a gross is entered and
 * the sum rule of [validateCapture] has nothing to say. Live, so the confirmation appears as the
 * operator types rather than only after a refused save; a refused save's own error always wins.
 */
private fun vatSumMatches(
    form: InvoiceCaptureForm,
    errors: Map<InvoiceCaptureField, StringKey>,
): Boolean =
    errors[InvoiceCaptureField.VAT_ROW_SUM] == null &&
        form.grossTotal.isNotBlank() &&
        form.vatRows.indices.all { deriveVatRow(form.vatRows[it].rate, form.grossFor(it)) != null } &&
        validateCapture(form)[InvoiceCaptureField.VAT_ROW_SUM] == null
