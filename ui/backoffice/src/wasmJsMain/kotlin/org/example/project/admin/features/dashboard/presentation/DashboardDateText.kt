package org.example.project.admin.features.dashboard.presentation

import kotlinx.datetime.LocalDate
import kotlinx.datetime.number
import org.example.project.core.domain.enums.setup.Language

private val WeekdaysDe = listOf("Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag")
private val WeekdaysDeShort = listOf("Mo.", "Di.", "Mi.", "Do.", "Fr.", "Sa.", "So.")
private val WeekdaysEn = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
private val WeekdaysEnShort = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val WeekdaysZh = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")
private val WeekdaysZhShort = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

private val MonthsDe =
    listOf("Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember")
private val MonthsEn =
    listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
private val MonthsEnShort = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/**
 * The date of the header's "Stand" line with its weekday, in the UI language: the long form
 * ("Mittwoch, 21. Oktober 2026") on a window with room, the short one ("Mi., 21.10.2026") on a phone.
 */
internal fun dashboardDateText(
    date: LocalDate,
    language: Language,
    compact: Boolean,
): String {
    val weekday = date.dayOfWeek.ordinal // Monday = 0
    val month = date.month.number - 1
    val day = date.day
    val year = date.year
    val numeric = "${day.toString().padStart(2, '0')}.${date.month.number.toString().padStart(2, '0')}.$year"
    return when (language) {
        Language.GERMAN ->
            if (compact) "${WeekdaysDeShort[weekday]}, $numeric" else "${WeekdaysDe[weekday]}, $day. ${MonthsDe[month]} $year"
        Language.ENGLISH ->
            if (compact) {
                "${WeekdaysEnShort[weekday]}, $day ${MonthsEnShort[month]} $year"
            } else {
                "${WeekdaysEn[weekday]}, $day ${MonthsEn[month]} $year"
            }
        Language.CHINESE ->
            "${year}年${date.month.number}月${day}日 " + if (compact) WeekdaysZhShort[weekday] else WeekdaysZh[weekday]
    }
}
