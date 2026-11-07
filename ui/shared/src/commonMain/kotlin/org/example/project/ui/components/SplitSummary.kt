package org.example.project.ui.components

/** First non-blank cell as the label; every later non-blank cell as an indexed value. */
fun splitSummary(cells: List<String>): PosAdaptiveSummary {
    val filled = cells.withIndex().filter { it.value.isNotBlank() }
    return PosAdaptiveSummary(label = filled.firstOrNull()?.value.orEmpty(), values = filled.drop(1))
}
