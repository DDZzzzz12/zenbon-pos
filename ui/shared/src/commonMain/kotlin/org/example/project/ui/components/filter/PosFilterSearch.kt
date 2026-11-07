package org.example.project.ui.components.filter

/** The search field of a [PosFilterBar]; [placeholder] is localized by the caller ("Belege suchen"). */
data class PosFilterSearch(
    val query: String,
    val onQueryChange: (String) -> Unit,
    val placeholder: String,
)
