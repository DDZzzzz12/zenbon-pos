package org.example.project.admin.features.product.presentation.components

/** The four catalog managers: the Large header buttons and the "Verwalten" menu ([ProductManageMenu]) open them. */
class ProductManagerActions(
    val onExtras: () -> Unit,
    val onChoiceGroups: () -> Unit,
    val onPromotions: () -> Unit,
    val onCategories: () -> Unit,
)
