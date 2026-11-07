package org.example.project.ui.components.inventory

/**
 * A category choice for the product category dropdown, with the display name already
 * resolved for the active language by the host (composeApp / adminApp).
 */
data class CategoryOption(
    val id: Long,
    val name: String,
)
