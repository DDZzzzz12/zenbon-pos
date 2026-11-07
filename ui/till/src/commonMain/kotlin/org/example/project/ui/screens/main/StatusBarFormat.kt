package org.example.project.ui.screens.main

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.number

/** Text of the right-hand end of the status bar. Pure so the format is testable and common to all targets. */
internal object StatusBarFormat {
    /** `dd.MM.yyyy  HH:mm:ss`, or just the date when [compact] (phones have the system clock). */
    fun stamp(
        now: LocalDateTime,
        compact: Boolean,
    ): String {
        val date = "${now.day.pad()}.${now.month.number.pad()}.${now.year}"
        if (compact) return date
        return "$date  ${now.hour.pad()}:${now.minute.pad()}:${now.second.pad()}"
    }

    private fun Int.pad(): String = toString().padStart(2, '0')
}
