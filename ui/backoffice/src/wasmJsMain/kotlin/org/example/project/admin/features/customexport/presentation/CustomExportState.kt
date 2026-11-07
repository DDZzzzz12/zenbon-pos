package org.example.project.admin.features.customexport.presentation

import org.example.project.core.domain.dto.customexport.CustomExportDto
import org.example.project.core.presentation.error.UiError

/**
 * The custom export builder: the catalogue the server offered, the definitions already saved, and
 * the one being edited.
 *
 * @property draft the definition being edited, or null when nothing is open. A draft is local until
 *   saved — the operator can abandon it, and nothing reaches the server until they press save.
 */
data class CustomExportState(
    val isLoading: Boolean = false,
    val subjects: List<CustomExportDto.SubjectDto> = emptyList(),
    val definitions: List<CustomExportDto.DefinitionDto> = emptyList(),
    val selectedId: Long? = null,
    val draft: DefinitionDraft? = null,
    val isSaving: Boolean = false,
    val isRunning: Boolean = false,
    /** Set after a run that hit the row cap, so the screen can say the file is incomplete. */
    val truncatedRowCount: Int? = null,
    val error: UiError? = null,
    /**
     * The draft as it was opened (the stored definition mapped to a draft, or the empty draft of a new
     * one). Kept beside [draft] so "unsaved" means "differs from this", never "was touched".
     */
    val baseline: DefinitionDraft? = null,
) {
    /** The open draft differs from [baseline]. Edit-then-undo is clean; transient flags are not part of it. */
    val isDirty: Boolean get() = draft != null && baseline != null && draft != baseline

    /** The catalogue entry the draft is built against, or null while no subject is chosen. */
    val draftSubject: CustomExportDto.SubjectDto?
        get() = draft?.let { d -> subjects.firstOrNull { it.key == d.subjectKey } }

    /**
     * True when the open draft is new or differs from its stored definition. A run uses the stored
     * definition, so a dirty draft is not what a download would produce. A heading shown as the
     * catalogue label in place of a raw stored key does not count (see [draftDiffersFromStored]).
     */
    val isDraftDirty: Boolean
        get() = draft?.let { d -> draftDiffersFromStored(d, definitions.firstOrNull { it.id == d.id }, draftSubject) } ?: false
}

/** The editable form. Separate from the DTO so an unsaved edit is never mistaken for stored state. */
data class DefinitionDraft(
    val id: Long? = null,
    val name: String = "",
    val subjectKey: String = "",
    val fields: List<DraftField> = emptyList(),
    val dateFrom: String? = null,
    val dateTo: String? = null,
    val enabledToggles: Set<String> = emptySet(),
    /**
     * Field keys the catalogue could not resolve when this definition was loaded.
     *
     * Carried into the draft so the editor can mark the dead rows. Without it, opening a broken
     * definition tells the operator to fix a field it cannot show them.
     */
    val unresolvedKeys: Set<String> = emptySet(),
)

/**
 * A chosen column.
 *
 * Carries no position: **its index in [DefinitionDraft.fields] is the position**, so the two cannot
 * disagree. The view model assigns positions when saving. The server rejects duplicate positions, and
 * a draft that tracked position separately would be able to produce them.
 */
data class DraftField(
    val fieldKey: String,
    val heading: String,
)
