/**
 * Shop Information UI State
 *
 * Represents the complete UI state for the shop information management screen.
 * Manages loading states, form data, validation errors, and user permissions.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.settings.presentation.state

import org.example.project.core.domain.dto.setup.ShopInfoDto
import org.example.project.core.presentation.error.UiError

/**
 * UI state for the shop information screen.
 *
 * Contains all state needed to render the shop info form including:
 * - Server data (original shop info with financial data)
 * - Form state (editable fields)
 * - Loading and saving states
 * - Validation errors
 * - User permissions
 * - Success/error feedback
 *
 * @property shopInfo Original shop information from server (includes financial data)
 * @property formState Current editable form data
 * @property isLoading Whether shop info is being loaded
 * @property isSaving Whether shop info is being saved
 * @property errorMessage General error message to display
 * @property fieldErrors Map of field-specific validation errors
 * @property saveSuccess Whether the last save operation succeeded
 * @property canEdit Whether the current user has permission to edit
 * @property hasUnsavedChanges Whether the form has been modified
 */
data class ShopInfoUiState(
    val shopInfo: ShopInfoDto.ShopInfoResponseDto? = null,
    val formState: ShopInfoFormState = ShopInfoFormState(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: UiError? = null,
    val successMessage: String? = null,
    val fieldErrors: Map<String, String> = emptyMap(),
    val saveSuccess: Boolean = false,
    val canEdit: Boolean = false,
    val hasUnsavedChanges: Boolean = false,
    val isUploadingLogo: Boolean = false,
) {
    /**
     * Checks if the form is currently disabled for input.
     *
     * The form is disabled during loading, saving, or when user lacks permissions.
     *
     * @return true if form inputs should be disabled
     */
    fun isFormDisabled(): Boolean = isLoading || isSaving || !canEdit

    /**
     * Checks if the save button should be enabled.
     *
     * Save is enabled only when user can edit, there are unsaved changes,
     * and the form is not currently being saved.
     *
     * @return true if save button should be enabled
     */
    fun canSave(): Boolean = canEdit && hasUnsavedChanges && !isSaving

    /**
     * Checks if the cancel button should be enabled.
     *
     * Cancel is enabled when there are unsaved changes and not currently saving.
     *
     * @return true if cancel button should be enabled
     */
    fun canCancel(): Boolean = hasUnsavedChanges && !isSaving

    /**
     * Checks if there are any validation errors.
     *
     * @return true if field errors exist
     */
    fun hasErrors(): Boolean = fieldErrors.isNotEmpty()
}
