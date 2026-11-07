package org.example.project.core.utils.file

/**
 * Outcome of a file-picker interaction. Cancelling and failing to read are different
 * events and must stay distinguishable: a cancel is a deliberate, common action that deserves
 * silence, while a read failure is rare and must be surfaced. Collapsing both to null would
 * force the call site to pick one wrong behaviour for the other case.
 *
 * It lives in `shared` rather than next to a picker implementation because it describes the
 * *outcome*, not the mechanism: the browser `<input type=file>` is one producer, a desktop or
 * Android picker would be another, and a component that only consumes the result must be able to
 * name this type without depending on any of them.
 */
sealed interface FilePickResult {
    /** The user chose a file and it was read successfully. */
    data class Picked(
        val file: PickedFile,
    ) : FilePickResult

    /** The user dismissed the dialog without choosing. Not an error. */
    data object Cancelled : FilePickResult

    /** A file was chosen but could not be read. */
    data object ReadFailed : FilePickResult
}
