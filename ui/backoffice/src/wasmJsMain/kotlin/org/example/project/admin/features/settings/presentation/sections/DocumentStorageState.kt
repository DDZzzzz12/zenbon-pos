package org.example.project.admin.features.settings.presentation.sections

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.StringKey

/** Mirrors `UpdateDocumentStorageConfigRequestDto.validate()`, which rejects anything outside 1..100. */
private val UPLOAD_SIZE_RANGE = 1..100

/** Mirrors the same `validate()`: a type name must be non-blank, ≤ 50 chars, and carry no path separator. */
private const val MAX_DOCUMENT_TYPE_LENGTH = 50

/**
 * State for the Belegablage section.
 *
 * The upload cap is held as the raw input string, not an `Int`: the field must be able to sit
 * transiently empty or non-numeric while being typed without the state silently substituting a
 * number the operator did not choose. It is a **quantity in MB, not money** — never route it through
 * `PosMoneyText`.
 *
 * The validation properties duplicate the request DTO's `validate()` deliberately: the server rejects
 * with a 400, and CLAUDE.md rule 8 requires the same guard client-side.
 */
data class DocumentStorageState(
    val path: String = "",
    val maxUploadSizeInput: String = "",
    val defaultDocumentTypes: List<String> = emptyList(),
    val additionalDocumentTypes: List<String> = emptyList(),
    val newDocumentType: String = "",
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val browser: DirectoryBrowserState? = null,
    val error: UiError? = null,
    val successMessage: StringKey? = null,
    /** What the server last returned for [path]; see [isDirty]. */
    val savedPath: String = "",
    /** What the server last returned for [maxUploadSizeInput]. */
    val savedMaxUploadSizeInput: String = "",
    /** What the server last returned for [additionalDocumentTypes]. */
    val savedAdditionalDocumentTypes: List<String> = emptyList(),
    /** Bumped by every Save refused for invalid input, so the section reveals the field; transient, not part of [isDirty]. */
    val validationAttempt: Int = 0,
) {
    /** The three editable settings differ from what was last loaded or saved. Edit-then-undo is clean. */
    val isDirty: Boolean
        get() =
            path != savedPath ||
                maxUploadSizeInput != savedMaxUploadSizeInput ||
                additionalDocumentTypes != savedAdditionalDocumentTypes

    /** The parsed cap, or null when the input is not a whole number in range. */
    val maxUploadSizeMb: Int?
        get() = maxUploadSizeInput.trim().toIntOrNull()?.takeIf { it in UPLOAD_SIZE_RANGE }

    val uploadSizeError: StringKey?
        get() = if (maxUploadSizeMb == null) AdminStringKeys.SettingsDocStorageMaxUploadInvalid else null

    /** Null while the field is empty — an untouched field is not an error the operator should see. */
    val newDocumentTypeError: StringKey?
        get() {
            val candidate = newDocumentType.trim()
            return when {
                candidate.isEmpty() -> null
                candidate.length > MAX_DOCUMENT_TYPE_LENGTH -> AdminStringKeys.SettingsDocStorageTypeTooLong
                candidate.contains('/') || candidate.contains('\\') -> AdminStringKeys.SettingsDocStorageTypeSeparator
                candidate in defaultDocumentTypes || candidate in additionalDocumentTypes ->
                    AdminStringKeys.SettingsDocStorageTypeDuplicate
                else -> null
            }
        }

    val canAddDocumentType: Boolean
        get() = newDocumentType.isNotBlank() && newDocumentTypeError == null

    /** Field errors in the shape the form-focus helper reads; shown live, revealed on a refused Save. */
    val fieldErrors: Map<DocumentStorageField, StringKey>
        get() = uploadSizeError?.let { mapOf(DocumentStorageField.MAX_UPLOAD_SIZE to it) } ?: emptyMap()

    /**
     * Whether the Save button is enabled. Validity is deliberately NOT part of it: an invalid upload
     * size is refused when Save is pressed (see `DocumentStorageViewModel.save`), which reveals the field.
     */
    val canSave: Boolean
        get() = !isSaving && !isLoading
}
