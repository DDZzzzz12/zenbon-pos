package org.example.project.admin.features.kassenbuch.presentation.components

import org.example.project.core.domain.enums.kassenbuch.CashBookCloseInterval

/**
 * Everything the book-management dialog can ask of its ViewModel, grouped into one parameter object
 * rather than sixteen positional lambdas (CLAUDE.md's ≤3-parameter rule).
 */
data class BookManagementCallbacks(
    val onCreate: () -> Unit,
    val onEdit: (Long) -> Unit,
    val onName: (String) -> Unit,
    val onDatevAccount: (String) -> Unit,
    val onKost1: (String) -> Unit,
    val onKost2: (String) -> Unit,
    val onCloseInterval: (CashBookCloseInterval) -> Unit,
    val onSortOrder: (String) -> Unit,
    val onActive: (Boolean) -> Unit,
    val onSubmitBook: () -> Unit,
    val onDismissForm: () -> Unit,
    /** `null` switches the automatic Geldtransit off — a choice, not an absent value. */
    val onTransferTarget: (Long?) -> Unit,
    val onSaveTransferTarget: () -> Unit,
    /** The Kassenbuch Beleg-age limit (days), as typed. */
    val onBelegAge: (String) -> Unit,
    val onSaveBelegAge: () -> Unit,
    /** `null` clears the designation, so the closing till signs again. */
    val onSigningTerminal: (Long?) -> Unit,
    val onSaveSigningTerminal: () -> Unit,
    val onDismiss: () -> Unit,
)
