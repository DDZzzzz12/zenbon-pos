package org.example.project.admin.features.kassenbuch.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.documents.presentation.DocumentUploadSection
import org.example.project.admin.features.kassenbuch.presentation.EntryFormFields
import org.example.project.admin.features.kassenbuch.presentation.KassenbuchEntryFormState
import org.example.project.admin.features.kassenbuch.presentation.germanDateOf
import org.example.project.core.domain.dto.kassenbuch.CashBookDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.KassenbuchKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.KassenbuchBelegDateRule
import org.example.project.core.utils.KassenbuchBelegDateViolation
import org.example.project.core.utils.dateWireValue
import org.example.project.core.utils.enumStrings.getName
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosDateField
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosPickerField
import org.example.project.ui.components.PosTextField
import org.example.project.ui.components.PosTextFieldType
import org.example.project.ui.components.form.rememberPosFormFocus
import org.example.project.ui.components.form.requiredLabel
import org.example.project.ui.components.formatMoneyForDisplay
import org.example.project.ui.theme.PosSpacing

/** The Beleg a manual booking is filed under. Not an invoice from a vendor — an Eigenbeleg. */
private const val ENTRY_DOCUMENT_TYPE = "EIGENBELEG"

/** Bounded, never `weight()`ed: see [CreateEntryDialog]'s note on the 0-px measure trap. */
private val AmountFieldWidth = 170.dp

private val VatFieldWidth = 110.dp

/** Wide enough for „Nicht steuerbar“, which the 110 dp rate input truncates. */
private val VatNotApplicableWidth = 150.dp

/** Wider than [PosDialog]'s 420 dp default, so an amount and its VAT rate fit on one row. */
private val DialogMaxWidth = 520.dp

/**
 * The "+ Buchung" control — and, on the fiscal tab, deliberately not one.
 *
 * A fiscal Kassenbuch entry is signed by the TSE and needs a signing terminal, which the web admin
 * does not have: `KassenbuchServiceImpl` resolves one through `requireSigningTerminal` whenever the
 * target book is fiscal. Those entries are made at the till, in composeApp. Rendering the button on
 * the Ladenkasse and letting the server refuse is exactly the half-implemented guard CLAUDE.md rule
 * 8 forbids, so the fiscal tab gets **no control at all**; `KassenbuchViewModel.openEntryForm` is the
 * other half. The "Alle Kassen" tab shows nothing either — a booking goes into one book, and none is
 * selected there.
 *
 * @param book the selected cash book, or `null` on the "Alle Kassen" tab.
 */
@Composable
fun NewEntryAction(
    book: CashBookDto.ResponseDto?,
    onNewEntry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (book == null || book.isFiscal) return
    PosButton(
        text = str(AdminStringKeys.KassenbuchNewEntry),
        onClick = onNewEntry,
        modifier = modifier,
    )
}

/**
 * The manual booking form for one non-fiscal cash book.
 *
 * **A Beleg is mandatory.** The server rejects an entry whose `documentId` is null, so confirm stays
 * disabled until the upload section has produced one, and a line above the button says so rather
 * than leaving the operator to guess why the button is dead. The section itself decides nothing
 * about that: it uploads a file and reports an id, and this form is what turns the id into "the
 * booking now has its Beleg".
 *
 * **A VAT rate on every line is mandatory too**, and is the other rule that can hold confirm down
 * with nothing on screen explaining it — the `USt-Satz` field is narrow, easy to skip, and has no
 * error state of its own until something is typed in. It gets the same warning line, shown only once
 * the Beleg is attached ([KassenbuchEntryFormState.hasMissingVatRate]): one cause at a time.
 *
 * Amounts are strings from the field to the wire and are summed in `Decimal`
 * ([KassenbuchEntryFormState.total]) — no `Double` touches a booked amount. `belegDatum` is German
 * `DD.MM.YYYY`, the one non-ISO date on this screen, which is why the field names its format and
 * flags a malformed one.
 *
 * Layout notes that fail **silently** if ignored: no `verticalScroll` anywhere inside, because
 * [PosDialog] already scrolls its body and nesting a second scroll is an infinite-height measure
 * crash on wasm; and the amount/VAT row uses bounded widths rather than `weight()`, the habit this
 * screen keeps everywhere after the `PosCard` 0-px trap.
 */
@Composable
fun CreateEntryDialog(
    form: KassenbuchEntryFormState,
    currencyCode: String,
    callbacks: CreateEntryCallbacks,
) {
    // Validate on press (UI-E2-06): Confirm stays pressable; a refused press marks every field at
    // fault and scrolls to (and focuses) the first.
    val focus = rememberPosFormFocus<String>()
    val errors = if (form.validationAttempt > 0) form.fieldErrors() else emptyMap()
    LaunchedEffect(form.validationAttempt) {
        if (form.validationAttempt > 0) focus.revealFirst(form.fieldOrder(), form.fieldErrors())
    }
    val anchor: (String) -> Modifier = { key -> with(focus) { Modifier.formAnchor(key) } }
    PosDialog(
        onDismiss = callbacks.onDismiss,
        title = str(AdminStringKeys.KassenbuchNewEntry),
        maxWidth = DialogMaxWidth,
        kind = PosDialogKind.Form,
        primary =
            PosDialogAction(
                text = str(CommonKeys.confirm),
                onClick = callbacks.onConfirm,
                isLoading = form.isSubmitting,
            ),
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PosSpacing.md)) {
            Text(
                text = str(AdminStringKeys.KassenbuchEntryTargetBook, form.cashBookName),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )

            EntryTypePicker(
                selected = form.entryType,
                enabled = !form.isSubmitting,
                onSelect = callbacks.onEntryType,
            )

            // The one date field (UI-P1-08): typed and shown as TT.MM.JJJJ, picked from a calendar.
            // `belegDatum` stays German on the wire; the field speaks ISO, so it is converted at
            // this boundary in both directions.
            PosDateField(
                value = belegDatumAsIso(form.belegDatum),
                onValueChange = { iso -> callbacks.onBelegDatum(isoAsBelegDatum(iso)) },
                label = str(AdminStringKeys.KassenbuchEntryDate),
                isError = form.hasUnusableBelegDatum || (form.belegDatumViolation != null && !form.needsLateBookingReason),
                supportingText =
                    when {
                        form.hasUnusableBelegDatum -> str(AdminStringKeys.KassenbuchEntryDateInvalid)
                        form.needsLateBookingReason -> str(KassenbuchKeys.documentDateNeedsReason, form.maxBelegAgeDays.toString())
                        form.belegDatumViolation == KassenbuchBelegDateViolation.FUTURE ->
                            str(KassenbuchKeys.documentDateInFuture)
                        form.belegDatumViolation == KassenbuchBelegDateViolation.TOO_OLD ->
                            str(KassenbuchKeys.documentDateTooOld, KassenbuchBelegDateRule.OVERRIDE_CEILING_DAYS.toString())
                        else -> null
                    },
                enabled = !form.isSubmitting,
                modifier = anchor(EntryFormFields.BELEG_DATUM),
            )

            if (form.needsLateBookingReason) {
                PosTextField(
                    value = form.lateBookingReason,
                    onValueChange = callbacks.onLateBookingReason,
                    label = requiredLabel(str(KassenbuchKeys.lateBookingReason)),
                    supportingText = { Text(str(KassenbuchKeys.lateBookingReasonHint)) },
                    isError = form.lateBookingReason.isBlank(),
                    enabled = !form.isSubmitting,
                    modifier = anchor(EntryFormFields.LATE_REASON),
                )
            }

            PosTextField(
                value = form.belegtext,
                onValueChange = callbacks.onBelegtext,
                label = requiredLabel(str(AdminStringKeys.KassenbuchColText)),
                enabled = !form.isSubmitting,
                isError = EntryFormFields.BELEGTEXT in errors,
                supportingText = errors[EntryFormFields.BELEGTEXT]?.let { key -> { Text(str(key)) } },
                modifier = anchor(EntryFormFields.BELEGTEXT),
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            EntryLines(form = form, callbacks = callbacks, errors = errors, anchor = anchor)

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            TotalRow(form = form, currencyCode = currencyCode)
            AvailableCashHint(form = form, currencyCode = currencyCode)

            PosTextField(
                value = form.nachricht,
                onValueChange = callbacks.onNote,
                label = str(CommonKeys.notes),
                enabled = !form.isSubmitting,
            )

            DocumentUploadSection(
                fileType = ENTRY_DOCUMENT_TYPE,
                onUploaded = callbacks.onDocumentUploaded,
                enabled = !form.isSubmitting,
            )
            if (form.documentId == null) {
                Text(
                    text = str(AdminStringKeys.KassenbuchEntryDocumentRequired),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (form.validationAttempt > 0) {
                form.formLevelErrors().forEach { key ->
                    Text(
                        text = str(key),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** The booking type, localized through the shared `KassenbuchEntryType.getName`. */
@Composable
private fun EntryTypePicker(
    selected: KassenbuchEntryType,
    enabled: Boolean,
    onSelect: (KassenbuchEntryType) -> Unit,
) {
    val language = LocalAppLanguage.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        PosPickerField(
            value = selected.getName(language),
            onClick = { if (enabled) expanded = true },
            label = str(AdminStringKeys.KassenbuchEntryType),
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        PosDropdown(expanded = expanded, onDismissRequest = { expanded = false }) {
            KassenbuchEntryType.entries.forEach { type ->
                PosDropdownItem(
                    text = type.getName(language),
                    selected = type == selected,
                    onClick = {
                        onSelect(type)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * One row per Beleg line: a gross amount and its VAT rate.
 *
 * Several lines share one Belegnummer — that is how a receipt with mixed VAT rates is booked, and
 * why this is a list rather than a single amount field. Removing the last line is not offered: a
 * Beleg with no lines is not a booking.
 */
@Composable
private fun EntryLines(
    form: KassenbuchEntryFormState,
    callbacks: CreateEntryCallbacks,
    errors: Map<String, StringKey>,
    anchor: (String) -> Modifier,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        form.lines.forEachIndexed { index, line ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                val amountError = errors[EntryFormFields.amount(index)]
                val vatError = errors[EntryFormFields.vatRate(index)]
                PosTextField(
                    value = line.amount,
                    onValueChange = { callbacks.onLine(index, line.copy(amount = it)) },
                    label = requiredLabel(str(AdminStringKeys.KassenbuchColAmount)),
                    textFieldType = PosTextFieldType.Price,
                    isError = line.hasUnusableAmount || amountError != null,
                    supportingText = amountError?.let { key -> { Text(str(key)) } },
                    enabled = !form.isSubmitting,
                    modifier = anchor(EntryFormFields.amount(index)).width(AmountFieldWidth),
                )
                if (form.entryType.carriesVatRate) {
                    PosTextField(
                        value = line.vatRate,
                        onValueChange = { callbacks.onLine(index, line.copy(vatRate = it)) },
                        label = requiredLabel(str(AdminStringKeys.KassenbuchEntryVatRate)),
                        isError = vatError != null,
                        supportingText = vatError?.let { key -> { Text(str(key)) } },
                        enabled = !form.isSubmitting,
                        modifier = anchor(EntryFormFields.vatRate(index)).width(VatFieldWidth),
                    )
                } else {
                    // No USt-Bezug for this type: shown as not applicable, never as an input.
                    PosTextField(
                        value = str(KassenbuchKeys.vatRateNotApplicable),
                        onValueChange = {},
                        label = str(AdminStringKeys.KassenbuchEntryVatRate),
                        readOnly = true,
                        enabled = false,
                        modifier = Modifier.width(VatNotApplicableWidth),
                    )
                }
                if (form.lines.size > 1) {
                    TextButton(
                        onClick = { callbacks.onRemoveLine(index) },
                        enabled = !form.isSubmitting,
                    ) { Text(str(AdminStringKeys.KassenbuchEntryRemoveLine)) }
                }
            }
        }
        TextButton(onClick = callbacks.onAddLine, enabled = !form.isSubmitting) {
            Text(str(AdminStringKeys.KassenbuchEntryAddLine))
        }
    }
}

/**
 * The Beleg total, summed exactly in `Decimal`.
 *
 * Coloured as an error while it is not positive: the entry type already carries the direction, so a
 * zero or negative total means the operator picked the opposite type, and the server refuses it.
 */
@Composable
private fun TotalRow(
    form: KassenbuchEntryFormState,
    currencyCode: String,
) {
    val total = form.total
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = str(AdminStringKeys.KassenbuchEntryTotal),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = formatMoneyForDisplay(total.toPlainString(), currencyCode),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (total.isZero || total.isNegative) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * F59: what the book holds — shown for a withdrawal — and, above it, why confirm stays disabled: a
 * cash book may not go below zero (the server's Kassenminus guard refuses it too).
 */
@Composable
private fun AvailableCashHint(
    form: KassenbuchEntryFormState,
    currencyCode: String,
) {
    val available = form.availableCashAmount ?: return
    if (!form.isOutflow) return
    val formatted = formatMoneyForDisplay(available.toPlainString(), currencyCode)
    Text(
        text = str(KassenbuchKeys.availableCash, formatted),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (form.exceedsAvailableCash) {
        Text(
            text = str(KassenbuchKeys.exceedsAvailableCash, formatted),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** The stored German `belegDatum` as the ISO value the date field speaks; anything unreadable as is. */
private fun belegDatumAsIso(belegDatum: String): String = KassenbuchBelegDateRule.parseGermanDate(belegDatum)?.toString() ?: belegDatum

/** The date field's ISO value as the German `DD.MM.YYYY` the wire expects; blank or bad text as is. */
private fun isoAsBelegDatum(value: String): String {
    val wire = dateWireValue(value)
    return runCatching { germanDateOf(LocalDate.parse(wire)) }.getOrDefault(wire)
}
