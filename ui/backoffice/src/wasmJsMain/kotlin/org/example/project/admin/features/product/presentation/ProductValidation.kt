package org.example.project.admin.features.product.presentation

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.BackOfficeErrorKeys
import org.example.project.core.utils.toCanonicalPriceOrNull
import org.example.project.ui.components.inventory.ProductFormField

/** Client-side validation of a product draft: one message key per invalid field; empty = savable. */
internal fun validateProductDraft(e: ProductEditor): Map<ProductFormField, StringKey> =
    buildMap {
        if (e.productNumber.isBlank()) put(ProductFormField.NUMBER, AdminStringKeys.ProductFieldRequired)
        if (e.name.isBlank()) put(ProductFormField.NAME, AdminStringKeys.ProductFieldRequired)
        if (e.categoryId == null) put(ProductFormField.CATEGORY, AdminStringKeys.ProductFieldRequired)
        // FO-54: the type drives the VAT mapping, so it is never defaulted — it must be chosen.
        if (e.productType == null) put(ProductFormField.TYPE, AdminStringKeys.ProductFieldRequired)
        if (e.price.isNotBlank() && e.price.toCanonicalPriceOrNull() == null) {
            put(ProductFormField.PRICE, BackOfficeErrorKeys.priceInvalid)
        }
    }

/** Drops the error of every field whose value changed from [previous]. */
internal fun ProductEditor.withEditedErrorsCleared(previous: ProductEditor): ProductEditor {
    if (fieldErrors.isEmpty()) return this
    val changed =
        buildSet {
            if (productNumber != previous.productNumber) add(ProductFormField.NUMBER)
            if (name != previous.name) add(ProductFormField.NAME)
            if (categoryId != previous.categoryId) add(ProductFormField.CATEGORY)
            if (productType != previous.productType) add(ProductFormField.TYPE)
            if (price != previous.price) add(ProductFormField.PRICE)
        }
    return copy(fieldErrors = fieldErrors.filterKeys { it !in changed })
}
