package org.example.project.admin.features.promotion.presentation

import org.example.project.core.domain.dto.product.ProductDto
import org.example.project.core.domain.dto.promotion.PromotionDto
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.StringKey

sealed interface PromotionsState {
    data object Loading : PromotionsState

    data class Error(
        val error: UiError,
    ) : PromotionsState

    data class Data(
        val promotions: List<PromotionDto.PromotionResponseDto>,
        val products: List<ProductDto.ProductResponseDto>,
        val selectedId: Long? = null,
        val usage: PromotionDto.PromotionUsageDto? = null,
        /** A failed deactivate/usage call; surfaced by PosErrorWatcher without losing the list. */
        val actionError: UiError? = null,
    ) : PromotionsState
}

/**
 * Draft state for the promotion create/edit form.
 *
 * [validFromDate] and [validUntilDate] hold a bare `YYYY-MM-DD`; the ViewModel widens them to the
 * ISO datetime the server parses. [discountType], [discountValue], [scope] and [targetProductId]
 * are create-only — `UpdatePromotionRequestDto` carries none of them, so the editor renders them
 * disabled when editing rather than pretending the change would be saved.
 */
data class PromotionEditor(
    val id: Long? = null, // null = creating
    val name: String = "",
    val description: String = "",
    val discountType: String = "FIXED_AMOUNT",
    val discountValue: String = "",
    val scope: String = "BILL_WIDE",
    val targetProductId: Long? = null,
    val targetProductName: String = "",
    val isMultiUse: Boolean = false,
    /**
     * Whether the promotion may share a bill with OTHER promotions. Defaults to `true` — stacking
     * is the norm. Distinct from [isMultiUse], which is about repeating this same promotion.
     */
    val combinable: Boolean = true,
    val validFromDate: String = "",
    val validUntilDate: String = "",
    val isActive: Boolean = true,
    val saving: Boolean = false,
    /** "save" or a server message — a form-level failure. Field validation lives in [fieldErrors]. */
    val fieldError: String? = null,
    /** Per-field validation refusals from the Save click, shown at the field. */
    val fieldErrors: Map<PromotionField, StringKey> = emptyMap(),
    /** Bumped on every Save click the validation refuses; the dialog scrolls to the first error on each bump. */
    val validationAttempt: Int = 0,
) {
    val isCreate: Boolean get() = id == null

    /** This editor with the transient flags neutralised, so only user-visible edits are compared. */
    private fun comparable(): PromotionEditor = copy(saving = false, fieldError = null, fieldErrors = emptyMap(), validationAttempt = 0)

    /** True when the form differs from [original] (the state it was opened in). */
    fun isDirtyComparedTo(original: PromotionEditor?): Boolean = original != null && comparable() != original.comparable()
}
