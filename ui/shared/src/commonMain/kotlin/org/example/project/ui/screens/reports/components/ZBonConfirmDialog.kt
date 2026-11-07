package org.example.project.ui.screens.reports.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.domain.enums.zBon.ClosingSignerProblem
import org.example.project.core.domain.enums.zBon.TipPayoutMethod
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.ReportsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.toCanonicalMoneyOrNull
import org.example.project.features.reports.presentation.components.zBon.ClosingTipPayoutSection
import org.example.project.features.reports.presentation.components.zBon.ClosingTopUpSection
import org.example.project.features.reports.presentation.state.ZBonClosingDraft
import org.example.project.money.Decimal
import org.example.project.money.DecimalSeparator
import org.example.project.money.MoneyFormat
import org.example.project.money.format
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.components.PosTextField
import org.example.project.ui.components.formatMoneyForDisplay
import org.example.project.ui.guard.rememberGuardedDismiss

/**
 * Confirmation dialog for creating a Z-Bon, including the optional drawer count (A3) and the
 * employee-tip payouts booked in the closing (F58).
 *
 * The count is deliberately **skippable**: a Z-Bon that cannot be closed is worse than a Z-Bon
 * closed without a count, and a mandatory field does not produce more counting — it produces an
 * operator in a hurry typing the expected figure, which is a false attestation rather than a
 * missing one. Accepting the prefilled figure therefore books nothing at all.
 *
 * The tip section shows one row per employee with employee tips in the period being closed, checked
 * and prefilled with the whole tip; the expected cash — and the count prefill, until the operator
 * types a count — follow the checked payouts. With no rows (no tips, or no `CASHBOOK_WRITE`) the
 * section is not shown.
 *
 * @param draft the dialog's state, owned by `ZBonViewModel`
 * @param currencyCode the till's display currency
 */
@Composable
fun CreateZBonConfirmDialog(
    language: Language,
    draft: ZBonClosingDraft,
    currencyCode: String,
    onCountChange: (String) -> Unit,
    onTipCheckedChange: (employeeId: String, checked: Boolean) -> Unit,
    onTipAmountChange: (employeeId: String, text: String) -> Unit,
    onTipMethodChange: (employeeId: String, method: TipPayoutMethod) -> Unit,
    onTopUpSourceSelect: (cashBookId: Long) -> Unit,
    onTopUpAmountChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    /** Offered next to a signing-till problem; null (the till) shows no button. */
    onOpenSigningSettings: (() -> Unit)? = null,
    /** True when the operator has edited the draft: closing it then asks first. Defaults to false, so the till is unchanged. */
    dirty: Boolean = false,
) {
    val close = rememberGuardedDismiss(dirty = dirty, onClose = onDismiss)
    PosDialog(
        onDismiss = close,
        title = str(ReportsKeys.createZBon),
        titleIcon = Icons.Default.Receipt,
        kind = PosDialogKind.Form,
        primary =
            PosDialogAction(
                text = getString(CommonKeys.confirm, language),
                // Disabled ONLY for an unparseable count or a payout the server would refuse. An empty
                // count means "did not count", which must always be allowed to close.
                enabled = draft.canConfirm,
                onClick = onConfirm,
            ),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = str(ReportsKeys.createZBonConfirmMessage),
                style = MaterialTheme.typography.bodyMedium,
            )
            draft.readiness?.let { readiness ->
                val problemText =
                    when (readiness.signerProblem) {
                        null -> null
                        ClosingSignerProblem.NOT_DESIGNATED -> str(ReportsKeys.zBonSignerProblemNotDesignated)
                        ClosingSignerProblem.INACTIVE -> str(ReportsKeys.zBonSignerProblemInactive, readiness.signerName.orEmpty())
                        ClosingSignerProblem.NOT_FISCAL -> str(ReportsKeys.zBonSignerProblemNotFiscal, readiness.signerName.orEmpty())
                        ClosingSignerProblem.NOT_REGISTERED -> str(ReportsKeys.zBonSignerProblemNotRegistered, readiness.signerName.orEmpty())
                    }
                if (readiness.tseDisabled) {
                    Text(str(ReportsKeys.zBonTseDisabledNote), style = MaterialTheme.typography.bodySmall)
                }
                if (problemText == null) {
                    readiness.signerName?.let { Text(str(ReportsKeys.zBonSignedBy, it), style = MaterialTheme.typography.bodySmall) }
                } else {
                    Text(problemText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Text(
                        text = str(if (readiness.blocksClosing) ReportsKeys.zBonBlockedByDailyTransfer else ReportsKeys.zBonMovementsUnavailable),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    onOpenSigningSettings?.let { open ->
                        PosTextButton(text = str(ReportsKeys.zBonOpenSigningSettings), onClick = open)
                    }
                }
            }
            // Without a signer nothing that moves cash can be booked: no count, no top-up, and every
            // tip payout is forced to bank/payroll, which books no Kassenbuch entry.
            if (draft.tipRows.isNotEmpty()) {
                ClosingTipPayoutSection(
                    rows = draft.effectiveTipRows,
                    currencyCode = currencyCode,
                    onCheckedChange = onTipCheckedChange,
                    onAmountChange = onTipAmountChange,
                    onMethodChange = onTipMethodChange,
                    methodChoiceEnabled = draft.movementsAllowed,
                )
                if (draft.movementsAllowed) {
                    ClosingTopUpSection(
                        draft = draft,
                        currencyCode = currencyCode,
                        onSourceSelect = onTopUpSourceSelect,
                        onAmountChange = onTopUpAmountChange,
                    )
                }
            }
            if (draft.movementsAllowed) {
                PosTextField(
                    value = draft.countText,
                    onValueChange = onCountChange,
                    label = str(ReportsKeys.cashCountLabel),
                    placeholder = draft.countPlaceholder.ifEmpty { null },
                    keyboardType = KeyboardType.Decimal,
                    isError = draft.countInvalid,
                    errorMessage = if (draft.countInvalid) str(ReportsKeys.cashCountInvalid) else null,
                    supportingText = {
                        Text(
                            text = str(ReportsKeys.cashCountHint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                )
                draft.expectedAfterPayouts?.let { expected ->
                    val tipTotal = draft.tipPayoutTotal
                    Text(
                        text =
                            if (tipTotal > Decimal.ZERO) {
                                str(
                                    ReportsKeys.cashCountExpectedAfterTips,
                                    formatMoneyForDisplay(expected.toPlainString(), currencyCode),
                                    formatMoneyForDisplay(tipTotal.toPlainString(), currencyCode),
                                )
                            } else {
                                str(ReportsKeys.cashCountExpected, formatMoneyForDisplay(expected.toPlainString(), currencyCode))
                            },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Text(
                text = str(ReportsKeys.createZBonWarning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * The counted-cash field's PLACEHOLDER (FO-33: the field itself starts empty, "empty = not counted"): [expectedBalance] in the same comma convention the
 * expected-cash line renders, so the operator is not shown one separator and asked to type another.
 *
 * Deliberately NOT [formatMoneyForDisplay]. That appends the currency symbol and dot-groups the
 * thousands ("1.234,56 €"), while [toCanonicalMoneyOrNull] — the field's validator — only swaps ","
 * for ".". So the symbol and the space fail to parse, the grouping dot survives the swap as a
 * SECOND decimal point, the validator returns null, and the dialog opens with Confirm disabled on
 * the very value it just suggested. The operator cannot then close the Z-Bon without first clearing
 * a field they never typed into. Symbol and grouping are therefore both omitted; the comma survives
 * the round trip.
 *
 * A null or unparseable balance yields an EMPTY field, never a "—" placeholder: empty reads as "did
 * not count", which must always be allowed to close, whereas a placeholder is non-empty and would
 * fail the validator — blocking the closing outright on a failed lookup.
 *
 * Pinned by `ZBonCountedPrefillTest`, round trip included.
 */
fun zBonCountedPrefill(expectedBalance: String?): String =
    expectedBalance
        ?.let { balance ->
            Decimal.parseOrNull(balance)?.format(MoneyFormat(separator = DecimalSeparator.COMMA))
        }.orEmpty()
