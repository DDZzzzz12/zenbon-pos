package org.example.project.ui.layout

import androidx.compose.runtime.Composable

/**
 * One peer section of a screen. Must be self-contained: it owns its own state and makes no layout
 * assumption about its parent, so it works in a phone-wide pane or a wide one.
 */
data class PaneSection(
    val id: String,
    val title: String,
    val content: @Composable () -> Unit,
)
