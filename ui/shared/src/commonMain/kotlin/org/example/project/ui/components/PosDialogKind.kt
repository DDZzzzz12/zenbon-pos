package org.example.project.ui.components

/** What a [PosDialog] is for; decides how it presents itself on a phone. */
enum class PosDialogKind {
    /** A question with a decision: a compact card on a phone. */
    Confirm,

    /** Has inputs: full-screen on a phone. */
    Form,
}
