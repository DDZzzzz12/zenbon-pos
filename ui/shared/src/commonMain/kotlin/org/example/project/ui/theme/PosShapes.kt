package org.example.project.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Zenbon draws every control and container with square corners — till, back office and kitchen alike.
 *
 * One switch rather than call sites, because the components already ask the theme: `PosCard`,
 * `PosStatusChip`, `PosSegmentedButtonRow`, `PosFlatField`, `PosDropdown`, `PosDialog`,
 * `PosSidebarNavItem`, `PosTileButton` and Material's own components resolve `MaterialTheme.shapes`.
 * `PosButton` and its siblings are already `RectangleShape`.
 *
 * A zero-radius [RoundedCornerShape] rather than `RectangleShape`: `Shapes` holds `CornerBasedShape`,
 * and Material's own components copy these with per-corner overrides — a segmented button rounds its
 * outer ends, a grouped chip its first and last — which only a corner-based shape supports.
 *
 * Deliberately not exhaustive over the apps: round tables, seat markers, badge counters and status
 * dots are round by nature and hardcode `CircleShape`.
 */
private val Square = RoundedCornerShape(0.dp)

val PosShapes =
    Shapes(
        extraSmall = Square,
        small = Square,
        medium = Square,
        large = Square,
        extraLarge = Square,
    )
