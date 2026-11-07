package org.example.project.ui.components.inventory

/**
 * Form state for [CategoryEditForm]. Hosts (composeApp / adminApp) own this state and pass it
 * down, mapping it to/from their own domain models on load/save.
 */
data class CategoryFormState(
    val name: String = "",
    val enName: String = "",
    val chName: String = "",
    val description: String = "",
    val sortPosition: String = "",
    val isActive: Boolean = true,
    val visibility: String = "EVERYWHERE",
)
