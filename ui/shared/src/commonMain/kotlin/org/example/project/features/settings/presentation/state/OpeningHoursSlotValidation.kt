package org.example.project.features.settings.presentation.state

/**
 * Client mirror of `OpeningHoursDto.DaySchedule.validate()` (`openTime < closeTime`): a slot that
 * opens at or after it closes is refused by the server with a 400, so the editor flags it inline
 * and withholds the save instead. Overnight hours are entered as two slots (… – 23:59 and
 * 00:00 – … on the next day), which is all the server model can store.
 *
 * Compares the `HH:mm` prefix: the server returns `HH:mm:ss`, the time picker writes `HH:mm`.
 */
fun DaySlot.opensBeforeCloses(): Boolean = openTime.take(5) < closeTime.take(5)

/** True when any open day holds a slot that does not open before it closes. Closed days are ignored. */
fun Map<Int, DayScheduleState>.hasInvalidSlot(): Boolean = values.any { day -> !day.isClosed && day.slots.any { !it.opensBeforeCloses() } }

/**
 * The same rule for a one-day override (admin20 NOTE-E): an open override must open before it
 * closes, or the server answers 400 with its raw English validation text. A closed day needs no times.
 */
fun overrideHoursAreValid(
    isClosed: Boolean,
    openTime: String?,
    closeTime: String?,
): Boolean = isClosed || (openTime != null && closeTime != null && DaySlot(openTime, closeTime).opensBeforeCloses())
