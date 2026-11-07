package org.example.project.admin.features.staff.presentation.schedule

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.CommonKeys

private val TIME = Regex("""(\d{2}):(\d{2})""")
private const val MAX_HOUR = 23
private const val MAX_MINUTE = 59

/** True for a 24-hour `HH:MM` time the server can parse ("09:00", "23:59"). */
internal fun isScheduleTime(value: String): Boolean {
    val match = TIME.matchEntire(value) ?: return false
    val (hours, minutes) = match.destructured
    return hours.toInt() in 0..MAX_HOUR && minutes.toInt() in 0..MAX_MINUTE
}

/** The break in minutes; blank means none. Null when the text is not a non-negative whole number. */
internal fun ShiftDraft.breakMinutesOrNull(): Int? = breakDuration.ifBlank { "0" }.toIntOrNull()?.takeIf { it >= 0 }

/** Every validated key of [shiftCount] rows, in on-screen order (row by row, start → end → break). */
internal fun shiftFieldOrder(shiftCount: Int): List<ShiftField> =
    (0 until shiftCount).flatMap { index -> ShiftPart.entries.map { part -> ShiftField(index, part) } }

/** Client-side validation of a day's shifts: one message key per invalid input; empty = savable. */
internal fun validateDayEditor(e: DayEditor): Map<ShiftField, StringKey> =
    buildMap {
        e.shifts.forEachIndexed { index, shift ->
            timeError(shift.startTime)?.let { put(ShiftField(index, ShiftPart.START), it) }
            timeError(shift.endTime)?.let { put(ShiftField(index, ShiftPart.END), it) }
            if (shift.breakMinutesOrNull() == null) {
                put(ShiftField(index, ShiftPart.BREAK), AdminStringKeys.ScheduleErrorBreakInvalid)
            }
        }
    }

private fun timeError(value: String): StringKey? =
    when {
        value.isBlank() -> CommonKeys.fieldRequired
        !isScheduleTime(value) -> AdminStringKeys.ScheduleErrorTimeFormat
        else -> null
    }

/** Drops the error of every input of the shift at [index] whose value changed from [previous]. */
internal fun DayEditor.withShiftErrorsCleared(
    index: Int,
    previous: ShiftDraft,
): DayEditor {
    if (fieldErrors.isEmpty()) return this
    val current = shifts.getOrNull(index) ?: return this
    val changed =
        buildSet {
            if (current.startTime != previous.startTime) add(ShiftField(index, ShiftPart.START))
            if (current.endTime != previous.endTime) add(ShiftField(index, ShiftPart.END))
            if (current.breakDuration != previous.breakDuration) add(ShiftField(index, ShiftPart.BREAK))
        }
    return copy(fieldErrors = fieldErrors.filterKeys { it !in changed })
}
