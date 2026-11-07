package org.example.project.admin.features.choicegroup.presentation

import org.example.project.core.domain.dto.product.ChoiceGroupDto
import org.example.project.core.domain.dto.product.ProductExtrasDto
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.StringKey

sealed interface ChoiceGroupsState {
    data object Loading : ChoiceGroupsState

    data class Error(
        val error: UiError,
    ) : ChoiceGroupsState

    data class Data(
        val groups: List<ChoiceGroupDto.ChoiceGroupResponseDto>,
        val extras: List<ProductExtrasDto.ProductExtrasResponseDto>,
    ) : ChoiceGroupsState
}

/** One option row in the group editor — an extra + its price delta + default flag. Order = sortPosition. */
data class OptionRow(
    val extraId: Int,
    val name: String,
    val priceDelta: String = "0",
    val isDefault: Boolean = false,
)

/** Draft state for the choice-group create/edit dialog. */
data class GroupEditor(
    val id: Int? = null, // null = creating
    val name: String = "",
    val en: String = "",
    val ch: String = "",
    val minSelections: String = "0",
    val maxSelections: String = "1",
    val isActive: Boolean = true,
    val options: List<OptionRow> = emptyList(),
    val saving: Boolean = false,
    /** "save" — an unexpected save failure, rendered from a fixed key. Field validation lives in [fieldErrors]. */
    val fieldError: String? = null,
    /** Per-field validation refusals from the Save click, shown at the field. */
    val fieldErrors: Map<ChoiceGroupField, StringKey> = emptyMap(),
    /** Bumped on every Save click the validation refuses; the dialog scrolls to the first error on each bump. */
    val validationAttempt: Int = 0,
    /** A server refusal, rendered localized through `displayMessage()`. */
    val saveError: UiError? = null,
) {
    val isCreate: Boolean get() = id == null

    /** This editor with the transient flags neutralised, so only user-visible edits are compared. */
    private fun comparable(): GroupEditor = copy(saving = false, fieldError = null, fieldErrors = emptyMap(), validationAttempt = 0, saveError = null)

    /** True when the form differs from [original] (the state it was opened in). */
    fun isDirtyComparedTo(original: GroupEditor?): Boolean = original != null && comparable() != original.comparable()
}
