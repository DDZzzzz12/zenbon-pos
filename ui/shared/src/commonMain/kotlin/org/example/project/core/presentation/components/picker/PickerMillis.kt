package org.example.project.core.presentation.components.picker

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/*
 * Material's DatePicker counts days in UTC: `selectedDateMillis` and `displayedMonthMillis` are the
 * *UTC* start of a day. Converting through the browser's zone instead put a German operator one day
 * early on the way in (00:00 CEST is 22:00 UTC the day before), so a field holding the 1st opened the
 * calendar on the previous month with the 31st selected. Both directions therefore go through UTC.
 */

/** [this] date as the UTC start-of-day millis Material's DatePicker expects. */
@OptIn(ExperimentalTime::class)
fun LocalDate.toPickerMillis(): Long = atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

/** The date a DatePicker millis value stands for; the inverse of [toPickerMillis]. */
@OptIn(ExperimentalTime::class)
fun pickerMillisToDate(millis: Long): LocalDate = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC).date
