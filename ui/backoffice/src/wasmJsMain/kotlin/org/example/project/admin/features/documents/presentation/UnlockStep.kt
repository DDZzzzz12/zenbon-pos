package org.example.project.admin.features.documents.presentation

/** Which of the unlock dialog's two screens the operator is on. */
enum class UnlockStep {
    /** "Store an unlocked copy?" with the password field. */
    Ask,

    /** "If it stays locked the document cannot be processed — store it locked anyway?" */
    ConfirmDecline,
}
