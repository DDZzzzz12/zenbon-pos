package org.example.project.admin.features.customexport.presentation

/** Why the download button is disabled, so the editor can say so next to it. */
enum class RunBlockReason {
    /** The definition is new or edited: a run uses the *stored* definition, so save first. */
    SAVE_FIRST,

    /** The stored definition names a field the catalogue no longer has; the server refuses it. */
    FIX_FIELD,
}

/**
 * The reason a download is unavailable, or null when it may run.
 *
 * An unsaved or edited form is reported first: the operator has to save first, and saving is what lets
 * them repair a broken definition. A saved, untouched, broken one asks for the field repair.
 */
fun runBlockReason(
    saved: Boolean,
    dirty: Boolean,
    broken: Boolean,
): RunBlockReason? =
    when {
        !saved || dirty -> RunBlockReason.SAVE_FIRST
        broken -> RunBlockReason.FIX_FIELD
        else -> null
    }
