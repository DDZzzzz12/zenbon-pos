package org.example.project.admin.features.kassenbuch.presentation.components

import androidx.compose.runtime.Immutable
import org.example.project.admin.features.kassenbuch.presentation.EntryLineInput
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType

/**
 * What [CreateEntryDialog] can ask the ViewModel to do.
 *
 * Grouped into one object rather than passed as ten positional lambdas: the form has a field per
 * DATEV column and the argument list was the part most likely to be wired up wrong — two adjacent
 * `(String) -> Unit` parameters swap silently and compile.
 *
 * [onDocumentUploaded] is the only one that is not a field edit. It is what the upload section's
 * `onUploaded` becomes here: the section reports an id, and this is where the id acquires the
 * meaning "this booking's mandatory Beleg".
 */
@Immutable
data class CreateEntryCallbacks(
    val onEntryType: (KassenbuchEntryType) -> Unit,
    val onBelegDatum: (String) -> Unit,
    /** The late-booking reason, needed only for a Beleg date past the shop's limit. */
    val onLateBookingReason: (String) -> Unit,
    val onBelegtext: (String) -> Unit,
    val onNote: (String) -> Unit,
    val onLine: (index: Int, line: EntryLineInput) -> Unit,
    val onAddLine: () -> Unit,
    val onRemoveLine: (index: Int) -> Unit,
    val onDocumentUploaded: (documentId: Long) -> Unit,
    val onConfirm: () -> Unit,
    val onDismiss: () -> Unit,
)
