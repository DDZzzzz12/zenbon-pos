package org.example.project.admin.features.extras.presentation

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.BackOfficeErrorKeys
import org.example.project.core.utils.toCanonicalPriceOrNull

/** Client-side validation of an extra draft: one message key per invalid field; empty = savable. */
internal fun validateExtraDraft(e: ExtraEditor): Map<ExtraField, StringKey> =
    buildMap {
        if (e.name.isBlank()) put(ExtraField.NAME, AdminStringKeys.ProductFieldRequired)
        if (e.price.isNotBlank() && e.price.toCanonicalPriceOrNull() == null) {
            put(ExtraField.PRICE, BackOfficeErrorKeys.priceInvalid)
        }
    }

/** Drops the error of every field whose value changed from [previous]. */
internal fun ExtraEditor.withEditedErrorsCleared(previous: ExtraEditor): ExtraEditor {
    if (fieldErrors.isEmpty()) return this
    val changed =
        buildSet {
            if (name != previous.name) add(ExtraField.NAME)
            if (price != previous.price) add(ExtraField.PRICE)
        }
    return copy(fieldErrors = fieldErrors.filterKeys { it !in changed })
}
