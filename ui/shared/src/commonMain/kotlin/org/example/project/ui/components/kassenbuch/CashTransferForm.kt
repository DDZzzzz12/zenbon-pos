/**
 * The manual cash transfer (Geldtransit) form.
 *
 * Exists to close a trap: without it, moving cash to the safe gets booked as an ordinary
 * `CASH_TRANSFER_OUT` entry, which carries `counterpartyBookId = null` and therefore means
 * **Bank**, not the Tresor. No transfer id, no matching Tresor leg — the safe never receives the
 * money in its own book and the two books silently disagree by the amount moved.
 *
 * Not for the nightly move, which is automatic; for shrinking an oversized float and for any
 * transfer outside the closing.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.ui.components.kassenbuch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.domain.dto.kassenbuch.CashBookDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.KassenbuchKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.toCanonicalMoneyOrNull
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosPickerField
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.components.PosTextField
import org.example.project.ui.components.formatMoneyForDisplay
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private val DD_MM_YYYY = Regex("""^\d{2}\.\d{2}\.\d{4}$""")

/**
 * Dialog for moving cash between two of the shop's cash books.
 *
 * Every server guard is mirrored here so the operator meets it before submitting rather than as an
 * unexplainable rejection: the target picker omits the chosen source (making `SAME_BOOK`
 * unconstructible), inactive books appear in neither picker, and the amount is checked against the
 * selected source's own balance.
 *
 * @param books Every cash book of the shop; inactive ones are filtered out here
 * @param language Current app language
 * @param isSubmitting Whether a transfer is in flight — blocks a double submit
 * @param onSubmit Receives the completed request; the caller performs the call
 * @param onDismiss Callback when the dialog is dismissed
 */
@OptIn(ExperimentalTime::class)
@Composable
fun CashTransferForm(
    books: List<CashBookDto.ResponseDto>,
    language: Language,
    isSubmitting: Boolean,
    onSubmit: (CashBookDto.CreateTransferRequestDto) -> Unit,
    onDismiss: () -> Unit,
) {
    val selectable = remember(books) { selectableBooks(books) }

    // The common case is moving takings OUT of the till, so it is the default source.
    var source by remember(selectable) {
        mutableStateOf(selectable.firstOrNull { it.isFiscal } ?: selectable.firstOrNull())
    }
    var target by remember(selectable, source) {
        mutableStateOf(selectable.firstOrNull { it.id != source?.id })
    }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var belegDatum by remember { mutableStateOf(todayAsDdMmYyyy()) }

    val error = validateCashTransfer(source, target, amount)
    // AMOUNT_MISSING is the untouched initial state — do not shout at the operator before they type.
    val showAmountError = error != null && error != CashTransferError.AMOUNT_MISSING
    val dateInvalid = !DD_MM_YYYY.matches(belegDatum.trim())
    val canSubmit = error == null && !dateInvalid && !isSubmitting

    PosDialog(
        onDismiss = onDismiss,
        title = str(KassenbuchKeys.transferTitle),
        footer = {
            PosTextButton(
                text = getString(CommonKeys.cancel, language),
                onClick = onDismiss,
            )
            PosButton(
                text = getString(CommonKeys.confirm, language),
                enabled = canSubmit,
                onClick = {
                    val src = source ?: return@PosButton
                    val dst = target ?: return@PosButton
                    val canonical = amount.trim().toCanonicalMoneyOrNull() ?: return@PosButton
                    onSubmit(
                        CashBookDto.CreateTransferRequestDto(
                            fromBookId = src.id,
                            toBookId = dst.id,
                            amount = canonical,
                            belegDatum = belegDatum.trim(),
                            note = note.trim().takeIf { it.isNotBlank() },
                        ),
                    )
                },
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BookPicker(
                label = str(KassenbuchKeys.transferFrom),
                selected = source,
                options = selectable,
                onSelect = { picked ->
                    source = picked
                    // Selecting the source as target is unconstructible rather than rejected.
                    if (target?.id == picked.id) target = selectable.firstOrNull { it.id != picked.id }
                },
            )
            BookPicker(
                label = str(KassenbuchKeys.transferTo),
                selected = target,
                options = selectable.filter { it.id != source?.id },
                onSelect = { target = it },
            )
            source?.let { src ->
                Text(
                    text = str(KassenbuchKeys.transferAvailable, formatMoneyForDisplay(src.currentBalance, src.currency)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            PosTextField(
                value = amount,
                onValueChange = { amount = it },
                label = str(KassenbuchKeys.transferAmount),
                keyboardType = KeyboardType.Decimal,
                isError = showAmountError,
                errorMessage = if (showAmountError) amountErrorText(error, source) else null,
            )
            PosTextField(
                value = belegDatum,
                onValueChange = { belegDatum = it },
                label = str(KassenbuchKeys.transferDate),
                isError = dateInvalid,
                errorMessage = if (dateInvalid) str(KassenbuchKeys.transferErrDate) else null,
            )
            PosTextField(
                value = note,
                onValueChange = { note = it },
                label = str(KassenbuchKeys.transferNote),
            )
        }
    }
}

/**
 * Maps a validation reason to its localized message.
 *
 * The over-balance message names the amount [source] actually holds, so the operator can correct
 * the figure without going to look it up.
 */
@Composable
private fun amountErrorText(
    error: CashTransferError?,
    source: CashBookDto.ResponseDto?,
): String =
    when (error) {
        CashTransferError.SAME_BOOK -> str(KassenbuchKeys.transferErrSameBook)
        CashTransferError.AMOUNT_EXCEEDS_SOURCE_BALANCE ->
            str(
                KassenbuchKeys.transferErrBalance,
                source?.let { formatMoneyForDisplay(it.currentBalance, it.currency) }.orEmpty(),
            )
        else -> str(KassenbuchKeys.transferErrAmount)
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookPicker(
    label: String,
    selected: CashBookDto.ResponseDto?,
    options: List<CashBookDto.ResponseDto>,
    onSelect: (CashBookDto.ResponseDto) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        PosPickerField(
            value = selected?.name.orEmpty(),
            onClick = { expanded = true },
            label = label,
            modifier = Modifier.fillMaxWidth(),
        )
        PosDropdown(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                PosDropdownItem(
                    text = option.name,
                    selected = option.id == selected?.id,
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Today as `DD.MM.YYYY`, the format the transfer endpoint validates. */
@OptIn(ExperimentalTime::class)
private fun todayAsDdMmYyyy(): String {
    val today =
        Clock.System
            .now()
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .date
    val day = today.dayOfMonth.toString().padStart(2, '0')
    val month = today.monthNumber.toString().padStart(2, '0')
    return "$day.$month.${today.year}"
}
