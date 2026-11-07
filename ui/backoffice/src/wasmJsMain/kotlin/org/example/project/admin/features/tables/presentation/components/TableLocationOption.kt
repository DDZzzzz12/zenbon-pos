package org.example.project.admin.features.tables.presentation.components

/** One location the panel offers: its id, name and number of tables (as on the former chips). */
data class TableLocationOption(
    val id: Long,
    val name: String,
    val count: Int,
) {
    /** "Terrasse (4)": the chip's and the picker row's text. */
    val label: String get() = "$name ($count)"
}
