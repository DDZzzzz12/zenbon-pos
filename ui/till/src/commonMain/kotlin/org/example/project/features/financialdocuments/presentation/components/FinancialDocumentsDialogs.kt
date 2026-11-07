/**
 * Shared dialog layer for Financial Documents screen.
 *
 * Consolidates all dialogs (DocumentUpload, KassenbuchDetail, CreateKassenbuchEntry,
 * CustomDateRangePicker) into a single composable to avoid duplication between
 * compact and expanded layouts.
 *
 * @since 2.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.financialdocuments.presentation.components

import androidx.compose.runtime.*
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.components.picker.CustomDateRangePickerDialog
import org.example.project.features.financialdocuments.presentation.state.KassenbuchState
import org.example.project.features.financialdocuments.presentation.viewmodel.DocumentUploadViewModel
import org.example.project.features.financialdocuments.presentation.viewmodel.KassenbuchViewModel
import org.example.project.ui.auth.holds
import org.example.project.ui.components.kassenbuch.CashTransferForm
import org.koin.compose.viewmodel.koinViewModel

/**
 * Renders all dialogs for the Financial Documents screen.
 *
 * Conditionally shows dialogs based on current state:
 * - DocumentUploadDialog when showUploadDialog is true
 * - KassenbuchDetailPanel when a grouped entry is selected
 * - CreateKassenbuchEntryPanel when showCreateDialog is true
 * - CustomDateRangePickerDialog when showCustomDatePicker is true
 *
 * @param kassenbuchState Current Kassenbuch state
 * @param kassenbuchViewModel Kassenbuch ViewModel for dialog actions
 * @param language Current application language
 * @param showCustomDatePicker Whether the custom date range picker is open
 * @param onDismissCustomDatePicker Callback closing the custom date range picker
 */
@Composable
fun FinancialDocumentsDialogs(
    kassenbuchState: KassenbuchState,
    kassenbuchViewModel: KassenbuchViewModel,
    language: Language,
    showCustomDatePicker: Boolean,
    onDismissCustomDatePicker: () -> Unit,
) {
    // Upload Dialog — managed by DocumentUploadViewModel
    val uploadViewModel: DocumentUploadViewModel = koinViewModel()
    val uploadState by uploadViewModel.uiState.collectAsState()

    if (uploadState.showUploadDialog) {
        DocumentUploadDialog(
            language = language,
            onDismiss = { uploadViewModel.hideUploadDialog() },
        )
    }

    // Kassenbuch Detail Panel
    kassenbuchState.selectedEntryDetail?.let { detail ->
        KassenbuchDetailPanel(
            entry = detail,
            isLoadingDetails = kassenbuchState.isLoadingDetails,
            onDismiss = { kassenbuchViewModel.clearSelectedEntry() },
            onShowLinkedPdf =
                if (detail.linkedDocument != null) {
                    { documentId ->
                        kassenbuchViewModel.showLinkedPdf(documentId)
                    }
                } else {
                    null
                },
            onUnlinkDocument =
                if (detail.linkedDocument != null && !detail.isLocked && detail.correctionRole == null) {
                    { headerId -> kassenbuchViewModel.unlinkDocument(headerId) }
                } else {
                    null
                },
            onUploadAndLink =
                if (detail.linkedDocument == null && !detail.isLocked && detail.correctionRole == null) {
                    { headerId -> kassenbuchViewModel.uploadAndLinkDocument(headerId) }
                } else {
                    null
                },
            onReplaceDocument =
                if (detail.linkedDocument != null && !detail.isLocked && detail.correctionRole == null) {
                    { headerId -> kassenbuchViewModel.replaceAndUploadDocument(headerId) }
                } else {
                    null
                },
            onCreateCorrection = {
                kassenbuchViewModel.openCorrectionDialog(detail)
            },
            language = language,
            // Both halves of the server's guard: the route needs CASHBOOK_WRITE (CashBookRules), and
            // createCorrection refuses transfer legs, Stornos and already-reversed entries (see
            // isCorrectableEntry). Locked entries ARE correctable — the Storno lands in the current period.
            canCreateCorrection = holds(Capability.CASHBOOK_WRITE) && isCorrectableEntry(detail),
        )
    }

    // Correction (Storno) dialog
    if (kassenbuchState.showCorrectionDialog) {
        kassenbuchState.correctionTarget?.let { target ->
            CreateCorrectionDialog(
                original = target,
                language = language,
                isCorrectionInProgress = kassenbuchState.isCorrecting,
                onDismiss = { kassenbuchViewModel.dismissCorrectionDialog() },
                onConfirm = { headerId, request, fileBytes, fileName ->
                    kassenbuchViewModel.submitCorrection(headerId, request, fileBytes, fileName)
                },
            )
        }
    }

    // Manual cash transfer (Geldtransit) form. A sibling of creating an entry, deliberately:
    // without it the natural workaround is a one-sided CASH_TRANSFER_OUT, which means Bank rather
    // than the Tresor and leaves the two books silently disagreeing.
    if (kassenbuchState.showTransferDialog) {
        CashTransferForm(
            books = kassenbuchState.cashBooks,
            language = language,
            isSubmitting = kassenbuchState.isTransferring,
            onSubmit = { request -> kassenbuchViewModel.createTransfer(request) },
            onDismiss = { kassenbuchViewModel.hideTransferDialog() },
        )
    }

    // Create Kassenbuch Entry Panel
    if (kassenbuchState.showCreateDialog) {
        CreateKassenbuchEntryPanel(
            onDismiss = { kassenbuchViewModel.hideCreateDialog() },
            onConfirm = { request, fileBytes, fileName, fileType, invoiceMetadata ->
                kassenbuchViewModel.createEntry(request, fileBytes, fileName, fileType, invoiceMetadata)
            },
            language = language,
            isCreating = kassenbuchState.isCreating,
            availableCash = kassenbuchState.availableCash,
        )
    }

    // Custom Date Range Picker Dialog
    if (showCustomDatePicker) {
        CustomDateRangePickerDialog(
            onDismiss = onDismissCustomDatePicker,
            onDateRangeSelected = { start, end ->
                kassenbuchViewModel.setCustomDateRange(start.toString(), end.toString())
            },
            language = language,
        )
    }
}
