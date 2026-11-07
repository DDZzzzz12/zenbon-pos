package org.example.project.admin.features.documents.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.documents.presentation.components.PdfUnlockDialog
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.theme.PosSpacing
import org.koin.compose.viewmodel.koinViewModel

/** PDFs and photographed receipts — the two shapes a Beleg actually arrives in. */
const val DOCUMENT_UPLOAD_ACCEPT: String = "application/pdf,image/*"

/**
 * Picks a file, uploads it, and hands the resulting `documentId` to its parent. That is the whole
 * contract.
 *
 * It decides **nothing** about what the document means: not which record it belongs to, not whether
 * one is required, not what happens if none is provided. The parent owns all of that — the
 * Kassenbuch entry form today, the Belege/DMS screen once that branch merges. Keeping the boundary
 * there is the reason this takes no invoice or bank-statement metadata: `uploadDocument` offers
 * vendor name, invoice number, due date, IBAN, statement period and two dozen more, and accepting
 * any of them would turn "uploads a file" into "knows what an invoice is".
 *
 * It still lives in adminApp only because both of its callers do; nothing in it is wasm-specific any
 * more. The file dialog is not called here — the ViewModel takes a
 * `suspend (accept: String) -> FilePickResult` and adminApp's DI binds the browser
 * `<input type=file>` to it, the same hoist `ShopLogoSection` uses for `onPickImage`. Moving this
 * into `shared-ui` is therefore a file move plus a picker binding per host, not a redesign.
 *
 * Errors render inline instead of through `PosErrorWatcher`. The section is designed to be
 * embedded, and its first host is a `PosDialog` — surfacing a failure as a second modal stacked over
 * that one, for something the operator is already looking straight at, is worse than a line of red
 * text under the button. A password-protected PDF additionally opens [PdfUnlockDialog]; the section
 * still reports only the document id.
 *
 * @param fileType the server's storage classification (e.g. `"EIGENBELEG"`, `"INVOICE"`). Routing,
 *   not metadata: it decides where the file is filed, and says nothing about its contents.
 * @param onUploaded invoked once per successful upload with the new document id. What that id means
 *   is the caller's business.
 * @param resetKey changing it clears the section, id included. The default resets on mount, which is
 *   what a form needs: adminApp shares one `ViewModelStoreOwner` app-wide, so without it a finished
 *   upload would still be attached the next time the form opened.
 * @param showButton false leaves the button to the host (its own header action) and keeps only the
 *   status lines and the unlock prompt.
 * @param chooseKey / [replaceKey] the button label before and after a file is attached; the
 *   defaults say "document", a caller that uploads something else (a bank statement) names it.
 */
@Composable
fun DocumentUploadSection(
    fileType: String,
    onUploaded: (documentId: Long) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accept: String = DOCUMENT_UPLOAD_ACCEPT,
    resetKey: Any? = Unit,
    showButton: Boolean = true,
    chooseKey: StringKey = AdminStringKeys.DocumentUploadChoose,
    replaceKey: StringKey = AdminStringKeys.DocumentUploadReplace,
    viewModel: DocumentUploadViewModel = koinViewModel(),
) {
    val state = viewModel.state

    LaunchedEffect(resetKey) { viewModel.reset() }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PosSpacing.sm)) {
        if (showButton) {
            PosSecondaryButton(
                text =
                    if (state.hasDocument) {
                        str(replaceKey)
                    } else {
                        str(chooseKey)
                    },
                onClick = { viewModel.pickAndUpload(fileType = fileType, accept = accept, onUploaded = onUploaded) },
                enabled = enabled && !state.isUploading,
                isLoading = state.isUploading,
            )
        }

        val fileName = state.fileName
        if (state.isUploading && fileName != null) {
            Text(
                text = str(AdminStringKeys.DocumentUploadInProgress, fileName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (state.hasDocument && fileName != null) {
            Text(
                text = str(AdminStringKeys.DocumentUploadAttached, fileName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.error?.let { error ->
            Text(
                text = str(error.messageKey, *error.messageArgs.toTypedArray()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    state.unlockPrompt?.let { prompt ->
        PdfUnlockDialog(
            prompt = prompt,
            isSubmitting = state.isUploading,
            onSubmit = viewModel::submitUnlockPassword,
            onDecline = viewModel::declineUnlock,
            onBack = viewModel::backToUnlockPrompt,
            onConfirmStoreLocked = viewModel::confirmStoreLocked,
            onCancel = viewModel::cancelUnlock,
        )
    }
}
