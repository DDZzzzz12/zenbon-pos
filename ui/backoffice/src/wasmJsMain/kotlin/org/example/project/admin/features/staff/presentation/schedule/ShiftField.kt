package org.example.project.admin.features.staff.presentation.schedule

/**
 * One validated input of the day editor: [part] of the shift at [index].
 *
 * A data class rather than a plain enum because the day holds a variable number of shift rows;
 * [shiftFieldOrder] lists the keys in on-screen order for the reveal-first-error scroll.
 */
data class ShiftField(
    val index: Int,
    val part: ShiftPart,
)
