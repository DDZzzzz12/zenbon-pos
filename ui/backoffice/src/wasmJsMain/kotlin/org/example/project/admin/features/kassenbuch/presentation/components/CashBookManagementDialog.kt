package org.example.project.admin.features.kassenbuch.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.kassenbuch.presentation.BookManagementState
import org.example.project.admin.features.kassenbuch.presentation.CashBookField
import org.example.project.admin.features.kassenbuch.presentation.CashBookFormState
import org.example.project.admin.features.kassenbuch.presentation.DeactivationBlock
import org.example.project.admin.features.kassenbuch.presentation.SigningTerminalPicker
import org.example.project.core.domain.dto.kassenbuch.CashBookDto
import org.example.project.core.domain.enums.kassenbuch.CashBookCloseInterval
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosPickerField
import org.example.project.ui.components.PosTextField
import org.example.project.ui.components.form.rememberPosFormFocus
import org.example.project.ui.components.form.requiredLabel
import org.example.project.ui.components.formatMoneyForDisplay
import org.example.project.ui.guard.rememberGuardedDismiss
import org.example.project.ui.theme.PosSpacing

/** Wider than [PosDialog]'s 420 dp default so a book row's name, account and balance fit on one line. */
private val DialogMaxWidth = 560.dp

/** Bounded, never `weight()`ed — this screen's habit after the `PosCard` 0-px measure trap. */
private val KostFieldWidth = 160.dp

private val SortOrderFieldWidth = 120.dp

/**
 * Cash-book management: the shop's ledgers, and where the daily takings go.
 *
 * The dialog has two faces and shows one at a time rather than nesting a second [PosDialog]: the
 * list (with the transfer-target picker) or the create/edit form. Nothing here uses
 * `verticalScroll` — [PosDialog] already scrolls its own body and a second scroll inside it is an
 * infinite-height measure crash on wasm.
 *
 * **There is no fiscal toggle anywhere in this dialog**, deliberately. `CreateRequestDto` has no
 * `isFiscal` field and `UpdateRequestDto` cannot flip one — the server hardcodes `isFiscal = false`
 * on create — so a switch would be a control that silently does nothing.
 */
@Composable
fun CashBookManagementDialog(
    state: BookManagementState,
    callbacks: BookManagementCallbacks,
) {
    val form = state.form
    val close =
        rememberGuardedDismiss(
            dirty = state.isDirty,
            onClose = if (form != null) callbacks.onDismissForm else callbacks.onDismiss,
        )
    PosDialog(
        onDismiss = close,
        title = if (form == null) str(AdminStringKeys.KassenbuchManageBooks) else str(bookFormTitle(form)),
        maxWidth = DialogMaxWidth,
        kind = PosDialogKind.Form,
        primary =
            if (form == null) {
                PosDialogAction(text = str(CommonKeys.close), onClick = close)
            } else {
                // Never disabled by the input: Save validates on click and reveals the first error.
                PosDialogAction(
                    text = str(CommonKeys.save),
                    onClick = callbacks.onSubmitBook,
                    isLoading = form.isSubmitting,
                )
            },
        secondary = form?.let { PosDialogAction(text = str(CommonKeys.cancel), onClick = close) },
        showSecondary = form != null,
    ) {
        if (form == null) {
            BookList(state = state, callbacks = callbacks)
        } else {
            BookForm(form = form, signingTerminal = state.signingTerminal, callbacks = callbacks)
        }
    }
}

private fun bookFormTitle(form: CashBookFormState) = if (form.isEdit) AdminStringKeys.KassenbuchBookEdit else AdminStringKeys.KassenbuchBookCreate

/** The transfer-target picker, then the books, then the way to add one. */
@Composable
private fun BookList(
    state: BookManagementState,
    callbacks: BookManagementCallbacks,
) {
    if (!state.isLoaded) {
        // Nothing is known yet (or the first load failed and the error watcher owns the retry):
        // never say "no books" about a list that has not been read.
        if (state.isLoading) BookListLoading()
        return
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TransferTargetSection(state = state, callbacks = callbacks)

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        BelegAgeSection(state = state, callbacks = callbacks)

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        state.listedBooks.forEach { book ->
            BookRow(book = book, onEdit = { callbacks.onEdit(book.id) })
        }
        if (state.listedBooks.isEmpty()) {
            Text(
                text = str(AdminStringKeys.KassenbuchBookNone),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        TextButton(onClick = callbacks.onCreate) { Text(str(AdminStringKeys.KassenbuchBookCreate)) }
    }
}

@Composable
private fun BookListLoading() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(
            text = str(CommonKeys.loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The automatic Geldtransit, and what choosing it actually does.
 *
 * This picker is what makes Phase A operator-usable, so the consequence is spelled out rather than
 * left to the label: with a target set, every Z-Bon moves that day's Tageseinnahme out of the
 * fiscal Ladenkasse and into the chosen book; with "off", nothing moves. The fiscal book is never
 * offered — it is the *source* of the transfer, and the server refuses it as its own target.
 */
@Composable
private fun TransferTargetSection(
    state: BookManagementState,
    callbacks: BookManagementCallbacks,
) {
    var expanded by remember { mutableStateOf(false) }
    val candidates = state.transferTargetCandidates
    val offLabel = str(AdminStringKeys.KassenbuchTransferTargetOff)
    val enabled = !state.isSavingTransferTarget && state.isTransferTargetLoaded && candidates.isNotEmpty()

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = str(AdminStringKeys.KassenbuchTransferTarget),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )

        Box {
            PosPickerField(
                value = state.transferTargetName ?: offLabel,
                onClick = { if (enabled) expanded = true },
                label = str(AdminStringKeys.KassenbuchTransferTarget),
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
            PosDropdown(expanded = expanded, onDismissRequest = { expanded = false }) {
                PosDropdownItem(
                    text = offLabel,
                    selected = state.transferTargetName == null,
                    onClick = {
                        callbacks.onTransferTarget(null)
                        expanded = false
                    },
                )
                candidates.forEach { book ->
                    PosDropdownItem(
                        text = book.name,
                        selected = book.name == state.transferTargetName,
                        onClick = {
                            callbacks.onTransferTarget(book.id)
                            expanded = false
                        },
                    )
                }
            }
        }

        Text(
            text =
                state.transferTargetName
                    ?.let { str(AdminStringKeys.KassenbuchTransferTargetExplainer, it) }
                    ?: str(AdminStringKeys.KassenbuchTransferTargetOffExplainer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (candidates.isEmpty()) {
            Text(
                text = str(AdminStringKeys.KassenbuchTransferTargetNoCandidates),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        PosButton(
            text = str(CommonKeys.save),
            onClick = callbacks.onSaveTransferTarget,
            enabled = state.hasUnsavedTransferTarget && !state.isSavingTransferTarget,
            isLoading = state.isSavingTransferTarget,
        )
    }
}

/**
 * How many days back a new entry's Beleg date may lie — for every cash book. The explainer says
 * what the setting does NOT do: a receipt from a closed period is accepted and booked into the open
 * one, so this limit is the only backdating bound.
 */
@Composable
private fun BelegAgeSection(
    state: BookManagementState,
    callbacks: BookManagementCallbacks,
) {
    // Save is disabled only while nothing changed (or while saving), never by an unusable value:
    // pressing it with one keeps the field flagged and moves focus there. The view model refuses
    // the save itself (saveBelegAge), as the server would with a 422.
    var attempt by remember { mutableStateOf(0) }
    val focus = rememberPosFormFocus<Unit>()
    val blankAfterAttempt = attempt > 0 && state.belegAgeInput.isBlank()
    val message =
        when {
            state.belegAgeInputInvalid -> str(AdminStringKeys.KassenbuchBelegAgeInvalid)
            blankAfterAttempt -> str(CommonKeys.fieldRequired)
            else -> null
        }
    LaunchedEffect(attempt) {
        if (attempt > 0) focus.revealFirst(listOf(Unit), message?.let { mapOf(Unit to it) }.orEmpty())
    }
    val saved = state.savedBelegAgeDays
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PosTextField(
            value = state.belegAgeInput,
            onValueChange = callbacks.onBelegAge,
            label = requiredLabel(str(AdminStringKeys.KassenbuchBelegAge)),
            isError = message != null,
            errorMessage = message,
            enabled = saved != null && !state.isSavingBelegAge,
            singleLine = true,
            modifier = with(focus) { Modifier.fillMaxWidth().formAnchor(Unit) },
        )
        Text(
            text = str(AdminStringKeys.KassenbuchBelegAgeExplainer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PosButton(
            text = str(CommonKeys.save),
            onClick = {
                if (state.parsedBelegAgeDays == null) attempt++
                callbacks.onSaveBelegAge()
            },
            enabled = saved != null && state.belegAgeInput.trim() != saved.toString() && !state.isSavingBelegAge,
            isLoading = state.isSavingBelegAge,
        )
    }
}

/** One book: what it is, what it holds, and the way into its form. */
@Composable
private fun BookRow(
    book: CashBookDto.ResponseDto,
    onEdit: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = book.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Text(
                text = bookSubtitle(book),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = formatMoneyForDisplay(book.currentBalance, book.currency),
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = onEdit) { Text(str(CommonKeys.edit)) }
    }
}

/** The DATEV account, plus the two badges that change what the operator may do with the book. */
@Composable
private fun bookSubtitle(book: CashBookDto.ResponseDto): String {
    val parts =
        buildList {
            add(book.datevAccount)
            if (book.isFiscal) add(str(AdminStringKeys.KassenbuchBookFiscal))
            if (!book.isActive) add(str(AdminStringKeys.KassenbuchBookInactive))
        }
    return parts.joinToString(" · ")
}

/** Create/edit fields. Bounded widths in every [Row] — no `weight()`ed sibling anywhere. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BookForm(
    form: CashBookFormState,
    signingTerminal: SigningTerminalPicker,
    callbacks: BookManagementCallbacks,
) {
    val focus = rememberPosFormFocus<CashBookField>()
    val formErrors = form.fieldErrors.entries.associate { (field, key) -> field to str(key) }

    fun anchor(field: CashBookField): Modifier = with(focus) { Modifier.formAnchor(field) }
    LaunchedEffect(form.validationAttempt) {
        if (form.validationAttempt > 0) focus.revealFirst(CashBookField.entries, formErrors)
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PosSpacing.md)) {
        PosTextField(
            value = form.name,
            onValueChange = callbacks.onName,
            label = requiredLabel(str(CommonKeys.name)),
            modifier = anchor(CashBookField.NAME),
            isError = CashBookField.NAME in formErrors,
            errorMessage = formErrors[CashBookField.NAME],
            enabled = !form.isSubmitting,
        )

        PosTextField(
            value = form.datevAccount,
            onValueChange = callbacks.onDatevAccount,
            label = requiredLabel(str(AdminStringKeys.KassenbuchBookDatevAccount)),
            modifier = anchor(CashBookField.DATEV_ACCOUNT),
            isError = CashBookField.DATEV_ACCOUNT in formErrors,
            errorMessage = formErrors[CashBookField.DATEV_ACCOUNT],
            enabled = !form.isSubmitting,
        )

        // Weighted, so each row of the wrap fills the dialog: a phone used to end Kostenstelle 2 ~30dp
        // short of the fields above it.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PosTextField(
                value = form.kost1,
                onValueChange = callbacks.onKost1,
                label = str(AdminStringKeys.KassenbuchBookKost1),
                enabled = !form.isSubmitting,
                modifier = Modifier.widthIn(min = KostFieldWidth).weight(1f),
            )
            PosTextField(
                value = form.kost2,
                onValueChange = callbacks.onKost2,
                label = str(AdminStringKeys.KassenbuchBookKost2),
                enabled = !form.isSubmitting,
                modifier = Modifier.widthIn(min = KostFieldWidth).weight(1f),
            )
            PosTextField(
                value = form.sortOrder,
                onValueChange = callbacks.onSortOrder,
                label = requiredLabel(str(AdminStringKeys.KassenbuchBookSortOrder)),
                isError = CashBookField.SORT_ORDER in formErrors,
                enabled = !form.isSubmitting,
                modifier = Modifier.widthIn(min = SortOrderFieldWidth).weight(0.75f).then(anchor(CashBookField.SORT_ORDER)),
            )
        }
        // Below the row rather than as the field's supporting text: the field is 120 dp wide.
        formErrors[CashBookField.SORT_ORDER]?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        CloseIntervalPicker(
            selected = form.closeInterval,
            enabled = !form.isSubmitting,
            onSelect = callbacks.onCloseInterval,
        )

        if (form.isEdit) ActiveRow(form = form, onActive = callbacks.onActive)

        SigningTerminalSection(
            picker = signingTerminal,
            onSelect = callbacks.onSigningTerminal,
            onSave = callbacks.onSaveSigningTerminal,
        )
    }
}

/**
 * The active switch, disabled exactly where the server would refuse — and saying which of the two
 * reasons applies.
 *
 * The fiscal Ladenkasse can never be deactivated whatever its balance, and a non-fiscal book only
 * while its balance is zero (`CashBookServiceImpl.requireDeactivatable`). Leaving the switch live
 * and letting the server return a 422 is exactly the half-implemented guard CLAUDE.md rule 8
 * forbids. An already-deactivated book keeps a live switch: re-activating strands nothing.
 */
@Composable
private fun ActiveRow(
    form: CashBookFormState,
    onActive: (Boolean) -> Unit,
) {
    val block = form.deactivationBlock
    val locked = form.isActive && block != null

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = str(AdminStringKeys.KassenbuchBookActive), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = form.isActive,
                onCheckedChange = onActive,
                enabled = !form.isSubmitting && !locked,
            )
        }
        if (locked) {
            Text(
                text =
                    when (block) {
                        DeactivationBlock.FISCAL -> str(AdminStringKeys.KassenbuchBookDeactivateBlockedFiscal)
                        DeactivationBlock.NON_ZERO_BALANCE ->
                            // Formatted money, never the raw wire string ("Still holds 12.50" in a
                            // German sentence, or a stored-scale "35.8100").
                            str(
                                AdminStringKeys.KassenbuchBookDeactivateBlockedBalance,
                                formatMoneyForDisplay(form.currentBalance, form.currency),
                            )
                        null -> ""
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** How often the book is *expected* to be closed. A hint only — never enforced by the server. */
@Composable
private fun CloseIntervalPicker(
    selected: CashBookCloseInterval,
    enabled: Boolean,
    onSelect: (CashBookCloseInterval) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        PosPickerField(
            value = str(closeIntervalKey(selected)),
            onClick = { if (enabled) expanded = true },
            label = str(AdminStringKeys.KassenbuchBookCloseInterval),
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        PosDropdown(expanded = expanded, onDismissRequest = { expanded = false }) {
            CashBookCloseInterval.entries.forEach { interval ->
                PosDropdownItem(
                    text = str(closeIntervalKey(interval)),
                    selected = interval == selected,
                    onClick = {
                        onSelect(interval)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun closeIntervalKey(interval: CashBookCloseInterval) =
    when (interval) {
        CashBookCloseInterval.WEEKLY -> AdminStringKeys.KassenbuchIntervalWeekly
        CashBookCloseInterval.MONTHLY -> AdminStringKeys.KassenbuchIntervalMonthly
        CashBookCloseInterval.QUARTERLY -> AdminStringKeys.KassenbuchIntervalQuarterly
        CashBookCloseInterval.MANUAL -> AdminStringKeys.KassenbuchIntervalManual
    }
