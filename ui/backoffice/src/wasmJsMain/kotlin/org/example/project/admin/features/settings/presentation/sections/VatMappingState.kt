package org.example.project.admin.features.settings.presentation.sections

import org.example.project.core.domain.dto.setup.VatDto
import org.example.project.core.domain.models.setup.VatMappingConfig
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.StringKey

/**
 * State for the MwSt-Zuordnung section.
 *
 * [draft] doubles as the dialog-open flag: it is non-null exactly while the editor is open, and it
 * holds the un-saved edit. A separate `showEditDialog` boolean would allow the two to disagree.
 *
 * [successMessage] is a [StringKey], not a resolved String: state is built outside a composition and
 * `str()` is `@Composable`, so the section resolves it and `PosSuccessWatcher` clears it.
 */
data class VatMappingState(
    val mapping: VatMappingConfig? = null,
    val vatRates: List<VatDto.VatResponseDto> = emptyList(),
    val draft: VatMappingConfig? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: UiError? = null,
    val successMessage: StringKey? = null,
) {
    /**
     * The open editor differs from what it was opened on (the stored mapping, or the defaults when none
     * is stored). Closed editor, or edit-then-undo, is clean.
     */
    val isDraftDirty: Boolean
        get() = draft?.let { it != (mapping ?: VatMappingConfig.getDefault()) } ?: false
}
