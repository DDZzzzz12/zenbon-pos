package org.example.project.ui.components.inventory

/**
 * Opts [ProductEditForm] into the sectioned layout (Grunddaten, then Übersetzungen) of the web
 * admin's product form; the host supplies the localized copy. Without it the form keeps its flat
 * layout (composeApp).
 */
data class ProductFormSectionCopy(
    val basicsTitle: String,
    val translationsTitle: String,
    /** Helper line under the "allow custom price" switch. */
    val allowCustomPriceHint: String,
)
