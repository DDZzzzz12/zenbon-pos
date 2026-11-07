package org.example.project.ui.components

/** One footer button of a [PosDialog]: its label, click handler and state. */
data class PosDialogAction(
    val text: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val isLoading: Boolean = false,
)
