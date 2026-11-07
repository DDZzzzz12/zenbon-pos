package org.example.project.admin.core.presentation.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** How far toward the selected fill a hovered, unselected row is tinted. Below 1, so hover never reaches selected. */
internal const val RAIL_ROW_HOVER_BLEND = 0.4f

/**
 * The fill of one rail row. Selected wins over hover; hover only tints an unselected row, and always
 * stays weaker than [selectedFill] (SYS-24). Pure so the ordering can be unit-tested without a composition.
 */
internal fun railRowFill(
    selected: Boolean,
    hovered: Boolean,
    selectedFill: Color,
    idleFill: Color,
): Color =
    when {
        selected -> selectedFill
        hovered -> lerp(idleFill, selectedFill, RAIL_ROW_HOVER_BLEND)
        else -> idleFill
    }
