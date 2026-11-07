package org.example.project.admin.features.product.presentation

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.StringKey
import org.example.project.ui.components.inventory.CategoryFormField

/** Client-side validation of a category draft: one message key per invalid field; empty = savable. */
internal fun validateCategoryDraft(e: CategoryEditor): Map<CategoryFormField, StringKey> =
    buildMap {
        if (e.name.isBlank()) put(CategoryFormField.NAME, AdminStringKeys.ProductFieldRequired)
    }

/** Drops the error of every field whose value changed from [previous]. */
internal fun CategoryEditor.withEditedErrorsCleared(previous: CategoryEditor): CategoryEditor {
    if (fieldErrors.isEmpty()) return this
    val changed =
        buildSet {
            if (name != previous.name) add(CategoryFormField.NAME)
        }
    return copy(fieldErrors = fieldErrors.filterKeys { it !in changed })
}
