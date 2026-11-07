package org.example.project.ui.components.inventory

import org.example.project.core.domain.enums.product.ProductType

/**
 * Form state for [ProductEditForm]. Hosts (composeApp / adminApp) own this state and pass it
 * down, mapping it to/from their own domain models on load/save.
 */
data class ProductFormState(
    val productNumber: String = "",
    val name: String = "",
    val enName: String = "",
    val chName: String = "",
    val description: String = "",
    val price: String = "",
    val productType: ProductType? = null,
    val categoryId: Long = 0L,
    val isActive: Boolean = true,
    val allowCustomPrice: Boolean = false,
)
