package org.example.project.admin.features.extras.presentation

import org.example.project.core.domain.dto.product.ChoiceGroupDto
import org.example.project.core.domain.dto.product.ProductExtrasDto
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.StringKey

sealed interface ExtrasState {
    data object Loading : ExtrasState

    data class Error(
        val error: UiError,
    ) : ExtrasState

    data class Data(
        val extras: List<ProductExtrasDto.ProductExtrasResponseDto>,
        /** The choice groups, read for the NOTE-c21 guard; empty when they could not be loaded. */
        val groups: List<ChoiceGroupDto.ChoiceGroupResponseDto> = emptyList(),
    ) : ExtrasState {
        /**
         * Names of the active, required choice groups that would offer fewer options than their
         * minimum without extra [extraId] — client half of the NOTE-c21 guard (the server answers
         * 409 `CHOICE_GROUP_OPTION_REQUIRED`). The server lists only offered (active) options, so a
         * group's option list is exactly what remains selectable.
         */
        fun requiredGroupsNeeding(extraId: Int): List<String> =
            groups
                .filter { g -> g.isActive && g.minSelections > 0 && g.options.any { it.optionId == extraId } }
                .filter { g -> g.options.count { it.optionId != extraId } < g.minSelections }
                .map { it.name }
                .sorted()
    }
}

/** Draft state for the extra create/edit form. */
data class ExtraEditor(
    val id: Int? = null, // null = creating
    val name: String = "",
    val en: String = "",
    val ch: String = "",
    val description: String = "",
    val price: String = "",
    val isActive: Boolean = true,
    val saving: Boolean = false,
    /** "required_group" / "save" — a form-level refusal, rendered from a fixed key. Field validation lives in [fieldErrors]. */
    val fieldError: String? = null,
    /** Per-field validation refusals from the Save click, shown at the field. */
    val fieldErrors: Map<ExtraField, StringKey> = emptyMap(),
    /** Bumped on every Save click the validation refuses; the dialog scrolls to the first error on each bump. */
    val validationAttempt: Int = 0,
    /** A server refusal, rendered localized through `displayMessage()`. */
    val saveError: UiError? = null,
) {
    val isCreate: Boolean get() = id == null

    /** This editor with the transient flags neutralised, so only user-visible edits are compared. */
    private fun comparable(): ExtraEditor = copy(saving = false, fieldError = null, fieldErrors = emptyMap(), validationAttempt = 0, saveError = null)

    /** True when the form differs from [original] (the state it was opened in). */
    fun isDirtyComparedTo(original: ExtraEditor?): Boolean = original != null && comparable() != original.comparable()
}
