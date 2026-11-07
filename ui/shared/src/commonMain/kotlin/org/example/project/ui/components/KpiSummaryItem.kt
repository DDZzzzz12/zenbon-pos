package org.example.project.ui.components

import org.example.project.core.utils.EMPTY_VALUE

/** One figure of a collapsed KPI summary; a null [value] means "not available", never zero. */
data class KpiSummaryItem(
    val label: String,
    val value: String?,
)

/** Joins [items] into one summary line, rendering a missing value as [EMPTY_VALUE]. */
fun kpiSummaryText(
    items: List<KpiSummaryItem>,
    separator: String = " · ",
): String = items.joinToString(separator) { "${it.label} ${it.value ?: EMPTY_VALUE}" }
