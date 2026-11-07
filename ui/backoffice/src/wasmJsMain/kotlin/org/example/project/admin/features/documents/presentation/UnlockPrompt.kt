package org.example.project.admin.features.documents.presentation

import org.example.project.core.presentation.error.UiError

/**
 * The unlock dialog for a locked PDF the operator just picked.
 *
 * The password is deliberately **not** here: it lives in the dialog's own composition state, goes
 * straight into [DocumentUploadViewModel.submitUnlockPassword] and is not kept by the ViewModel, which
 * outlives the form that hosts it (adminApp shares one `ViewModelStoreOwner`).
 *
 * [rejection] is the server's refusal of the last attempt (wrong or missing password). It keeps the
 * dialog open so the operator can correct a typo without picking the file again.
 */
data class UnlockPrompt(
    val fileName: String,
    val step: UnlockStep = UnlockStep.Ask,
    val rejection: UiError? = null,
)
