package org.example.project.admin.features.kassenbuch.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.format.asDisplayDay
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.kassenbuch.presentation.KassensturzUiState
import org.example.project.core.domain.dto.kassenbuch.CashBookDto
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.EMPTY_VALUE
import org.example.project.core.utils.formatShortDate
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind
import org.example.project.ui.components.PosTextField
import org.example.project.ui.components.PosTextFieldType
import org.example.project.ui.components.form.rememberPosFormFocus
import org.example.project.ui.components.form.requiredLabel
import org.example.project.ui.components.formatMoneyForDisplay
import org.example.project.ui.theme.PosSpacing

/**
 * The close control for the selected cash book — and, on the fiscal tab, deliberately not one.
 *
 * A Kassensturz counts the cash in a **non-fiscal** book (a Tresor) and closes its period. The
 * fiscal Ladenkasse is closed by the Z-Bon instead, and `CashBookCloseServiceImpl.requireNonFiscal`
 * rejects a fiscal close with a 422. Offering the button there and letting the server refuse is
 * precisely what CLAUDE.md rule 8 forbids, so the fiscal tab gets **no control at all** — only a
 * read-only statement of how that book is closed and how long the current business period has been
 * open. The "Alle Kassen" tab shows nothing: closing is per book, and there is no book selected.
 *
 * @param book the selected cash book, or `null` on the "Alle Kassen" tab.
 * @param latestZBonEndTime end time of the shop's most recent Z-Bon; `null` when there is none yet
 *   (or it has not been fetched), in which case only the "closed by Z-Bon" statement is shown —
 *   inventing an open-since date for a shop that has never run a Z-Bon would be a guess.
 */
@Composable
fun CashBookCloseAction(
    book: CashBookDto.ResponseDto?,
    latestZBonEndTime: String?,
    onKassensturz: () -> Unit,
    canClose: Boolean,
    modifier: Modifier = Modifier,
) {
    if (book == null) return

    if (!book.isFiscal) {
        // The close is CASHBOOK_CLOSE, not CASHBOOK_WRITE: an accountant may post but not close (FO-05).
        if (canClose) {
            PosButton(
                text = str(AdminStringKeys.KassenbuchKassensturz),
                onClick = onKassensturz,
                modifier = modifier,
            )
        }
        return
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = str(AdminStringKeys.KassenbuchZBonClosed),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
        )
        if (latestZBonEndTime != null) {
            Text(
                // The date alone: the Z-Bon's end time is a naive local timestamp and the business
                // period the operator cares about is a day, not a second. Rendered ISO, as the
                // reports screen renders its comparison dates.
                text = str(AdminStringKeys.KassenbuchOpenSince, latestZBonEndTime.asDisplayDay(LocalAppLanguage.current)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The Kassensturz: the operator counts the cash in the safe, enters it, and the server closes the
 * period — booking any difference against the book balance as its own correcting entry.
 *
 * The difference is shown **live, before confirming**, because it is the number that decides whether
 * the operator recounts rather than closes. It is computed in `Decimal` from the two exact strings
 * (see [KassensturzUiState.difference]) — a `Double` here could round a real one-cent shortfall into
 * agreement and report a safe as correct when it is not.
 *
 * Confirm is never greyed out for a missing count (UI-E2-06): a press without a usable one marks and
 * focuses the field. **`0` is usable** — an emptied
 * safe is a real count, and it is a different state from an untouched field, which is why a blank
 * field shows no validation complaint while a malformed one does.
 *
 * No `verticalScroll` anywhere inside: [PosDialog] already scrolls its own body, and nesting a
 * second scroll is an infinite-height measure crash on wasm.
 */

/** Focus key of the counted-balance field. */
private const val COUNTED_FIELD = "counted"

@Composable
fun KassensturzDialog(
    state: KassensturzUiState,
    currencyCode: String,
    onCountedBalance: (String) -> Unit,
    onNotes: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val preview = state.preview
    val focus = rememberPosFormFocus<String>()
    val countedError =
        when {
            state.hasUnusableInput -> str(AdminStringKeys.KassenbuchCountedBalanceInvalid)
            state.validationAttempt > 0 && state.countedInput.isBlank() -> str(CommonKeys.fieldRequired)
            else -> null
        }
    LaunchedEffect(state.validationAttempt) {
        if (state.validationAttempt > 0) focus.revealFirst(listOf(COUNTED_FIELD), mapOf(COUNTED_FIELD to true))
    }
    PosDialog(
        onDismiss = onDismiss,
        title = str(AdminStringKeys.KassenbuchKassensturz),
        kind = PosDialogKind.Form,
        primary =
            PosDialogAction(
                text = str(CommonKeys.confirm),
                onClick = onConfirm,
                isLoading = state.isSubmitting,
            ),
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PosSpacing.md)) {
            LabelledValue(label = str(AdminStringKeys.KassenbuchColBook), value = preview.cashBookName)
            LabelledValue(
                label = str(AdminStringKeys.KassenbuchColPeriod),
                value =
                    "${preview.periodStart.formatShortDate(LocalAppLanguage.current)} – " +
                        preview.periodEnd.formatShortDate(LocalAppLanguage.current),
            )
            LabelledValue(
                label = str(AdminStringKeys.KassenbuchBookBalance),
                value = formatMoneyForDisplay(preview.bookBalance, currencyCode),
            )
        }

        PosTextField(
            value = state.countedInput,
            onValueChange = onCountedBalance,
            label = requiredLabel(str(AdminStringKeys.KassenbuchCountedBalance)),
            textFieldType = PosTextFieldType.Price,
            isError = countedError != null,
            errorMessage = countedError,
            enabled = !state.isSubmitting,
            modifier = with(focus) { Modifier.formAnchor(COUNTED_FIELD) },
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        DifferenceRow(state = state, currencyCode = currencyCode)

        PosTextField(
            value = state.notes,
            onValueChange = onNotes,
            label = str(CommonKeys.notes),
            enabled = !state.isSubmitting,
        )

        Text(
            text = str(AdminStringKeys.KassenbuchKassensturzExplainer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * `counted − book`, or a dash while the field holds nothing usable.
 *
 * A non-zero difference is coloured and signed: it is about to become a CASH_SURPLUS or
 * CASH_SHORTAGE entry in the book, and the operator should see that before confirming rather than
 * after. Zero is shown plainly — the count agreed, and there is nothing to flag.
 */
@Composable
private fun DifferenceRow(
    state: KassensturzUiState,
    currencyCode: String,
) {
    val difference = state.difference
    val text = difference?.let { formatMoneyForDisplay(it.toPlainString(), currencyCode, forceSign = true) } ?: EMPTY_VALUE
    val isDeviation = difference != null && !difference.isZero
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = str(AdminStringKeys.KassenbuchDifference),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (isDeviation) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** One read-only line of the preview: muted label on the left, the value on the right. */
@Composable
private fun LabelledValue(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}
