package org.example.project.admin.features.promotion.presentation

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.BackOfficeErrorKeys
import org.example.project.core.utils.toCanonicalPriceOrNull

/** Client-side validation of a promotion draft: one message key per invalid field; empty = savable. */
internal fun validatePromotionDraft(e: PromotionEditor): Map<PromotionField, StringKey> =
    buildMap {
        if (e.name.isBlank()) put(PromotionField.NAME, AdminStringKeys.ProductFieldRequired)
        if (e.isCreate) {
            when {
                e.discountValue.isBlank() -> put(PromotionField.DISCOUNT_VALUE, AdminStringKeys.ProductFieldRequired)
                // A typed "2,50" reached the server as-is and came back as a raw parser message.
                e.discountValue.toCanonicalPriceOrNull() == null ->
                    put(PromotionField.DISCOUNT_VALUE, BackOfficeErrorKeys.priceInvalid)
            }
            if (e.scope == "ITEM_SPECIFIC" && e.targetProductId == null) {
                put(PromotionField.TARGET_PRODUCT, AdminStringKeys.PromotionErrorProductRequired)
            }
        }
        val fromValid = isIsoDate(e.validFromDate)
        if (!fromValid) put(PromotionField.VALID_FROM, AdminStringKeys.PromotionErrorInvalidDate)
        if (e.validUntilDate.isNotBlank()) {
            when {
                !isIsoDate(e.validUntilDate) -> put(PromotionField.VALID_UNTIL, AdminStringKeys.PromotionErrorInvalidDate)
                // admin20 NOTE-E: the server refused this with raw English. ISO dates compare as
                // strings; one day is valid (00:00 - 23:59:59).
                fromValid && e.validUntilDate < e.validFromDate ->
                    put(PromotionField.VALID_UNTIL, AdminStringKeys.PromotionErrorDateOrder)
            }
        }
    }

/** Drops the error of every field whose value changed from [previous]. */
internal fun PromotionEditor.withEditedErrorsCleared(previous: PromotionEditor): PromotionEditor {
    if (fieldErrors.isEmpty()) return this
    val changed =
        buildSet {
            if (name != previous.name) add(PromotionField.NAME)
            if (discountValue != previous.discountValue || discountType != previous.discountType) {
                add(PromotionField.DISCOUNT_VALUE)
            }
            if (targetProductId != previous.targetProductId || scope != previous.scope) add(PromotionField.TARGET_PRODUCT)
            if (validFromDate != previous.validFromDate) {
                add(PromotionField.VALID_FROM)
                add(PromotionField.VALID_UNTIL)
            }
            if (validUntilDate != previous.validUntilDate) add(PromotionField.VALID_UNTIL)
        }
    return copy(fieldErrors = fieldErrors.filterKeys { it !in changed })
}
