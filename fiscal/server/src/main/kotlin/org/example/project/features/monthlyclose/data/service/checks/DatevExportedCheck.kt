package org.example.project.features.monthlyclose.data.service.checks

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus
import org.example.project.core.domain.dto.datevexportlog.DatevExportLogDto
import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDetailCodes
import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDto
import org.example.project.core.domain.enums.setting.DatevExportCadence

/**
 * Checks whether DATEV exports have been performed for the close period.
 *
 * Behaviour depends on [cadence]:
 * - MANUAL  → returns HIDDEN (user exports on demand; no automatic check)
 * - MONTHLY → PASSED iff at least one KASSENBUCH export (and, when [apLedgerRequired], one
 *             AP_LEDGER export) fully covers [periodStart, periodEnd) (log.periodStart <=
 *             periodStart AND log.periodEnd >= periodEnd)
 * - WEEKLY  → same per-type check, but applied per ISO week in the window; PASSED only
 *             if every ISO week has every required type fully covered
 *
 * AP_LEDGER is required only when the AP auto-export is on AND the period holds creditor
 * invoices: with none, the AP export has nothing to write and answers 404, so demanding it made
 * the month impossible to close green (E2E-2026-09-18 #10).
 * - other   → WARNING (unknown cadence value)
 *
 * Accepts pre-fetched [exportLogs] to avoid re-querying; caller is responsible for
 * passing logs that at minimum overlap the period.
 */
class DatevExportedCheck {
    fun run(
        periodStart: LocalDateTime,
        periodEnd: LocalDateTime,
        cadence: DatevExportCadence,
        exportLogs: List<DatevExportLogDto>,
        apLedgerRequired: Boolean = true,
    ): MonthlyCloseDto.ChecklistItemDto {
        if (cadence == DatevExportCadence.MANUAL) {
            return MonthlyCloseDto.ChecklistItemDto(
                id = "datev_exported",
                status = MonthlyCloseDto.ChecklistStatus.HIDDEN,
                detail = "",
                affectedCount = 0,
                jumpTo = MonthlyCloseDto.SectionId.DATEV_EXPORT,
            )
        }

        val status: MonthlyCloseDto.ChecklistStatus
        val detail: String
        val missingTypes: List<String>
        var missingWeekCount = 0

        when (cadence) {
            DatevExportCadence.MONTHLY -> {
                val kassenbuchOk = coversFullPeriod("KASSENBUCH", periodStart, periodEnd, exportLogs)
                val apOk = !apLedgerRequired || coversFullPeriod("AP_LEDGER", periodStart, periodEnd, exportLogs)
                status =
                    when {
                        kassenbuchOk && apOk -> MonthlyCloseDto.ChecklistStatus.PASSED
                        else -> MonthlyCloseDto.ChecklistStatus.FAILED
                    }
                missingTypes = missingTypes(kassenbuchOk, apOk)
                detail = buildMonthlyDetail(missingTypes)
            }

            DatevExportCadence.WEEKLY -> {
                val weeks = isoWeeksInRange(periodStart, periodEnd)
                val missingWeeks =
                    weeks.count { (weekStart, weekEnd) ->
                        !coversFullPeriod("KASSENBUCH", weekStart, weekEnd, exportLogs) ||
                            (apLedgerRequired && !coversFullPeriod("AP_LEDGER", weekStart, weekEnd, exportLogs))
                    }
                missingWeekCount = missingWeeks
                missingTypes = if (missingWeeks > 0) requiredTypes(apLedgerRequired) else emptyList()
                status =
                    if (missingWeeks == 0) {
                        MonthlyCloseDto.ChecklistStatus.PASSED
                    } else {
                        MonthlyCloseDto.ChecklistStatus.FAILED
                    }
                detail =
                    if (missingWeeks > 0) {
                        if (apLedgerRequired) {
                            "$missingWeeks ISO week(s) missing full KASSENBUCH + AP_LEDGER export coverage"
                        } else {
                            "$missingWeeks ISO week(s) missing full KASSENBUCH export coverage"
                        }
                    } else {
                        ""
                    }
            }

            DatevExportCadence.MANUAL -> {
                // Handled above — unreachable
                status = MonthlyCloseDto.ChecklistStatus.HIDDEN
                detail = ""
                missingTypes = emptyList()
            }
        }

        return MonthlyCloseDto.ChecklistItemDto(
            id = "datev_exported",
            status = status,
            detail = detail,
            affectedCount = 0,
            jumpTo = MonthlyCloseDto.SectionId.DATEV_EXPORT,
            detailCode = detailCodeFor(cadence, missingTypes),
            detailArgs = detailArgsFor(cadence, missingTypes, missingWeekCount),
        )
    }

    /**
     * Returns true if there is at least one log row of [exportType] whose covered
     * period fully contains [periodStart, periodEnd) — i.e.,
     * log.periodStart <= periodStart AND log.periodEnd >= periodEnd.
     */
    private fun coversFullPeriod(
        exportType: String,
        periodStart: LocalDateTime,
        periodEnd: LocalDateTime,
        logs: List<DatevExportLogDto>,
    ): Boolean =
        logs.any { log ->
            log.exportType == exportType &&
                LocalDateTime.parse(log.periodStart) <= periodStart &&
                LocalDateTime.parse(log.periodEnd) >= periodEnd
        }

    /**
     * Enumerates the ISO weeks (Monday–Sunday boundaries) that overlap
     * with [start, end). Each entry is (weekMondayStart, weekSundayEnd).
     */
    private fun isoWeeksInRange(
        start: LocalDateTime,
        end: LocalDateTime,
    ): List<Pair<LocalDateTime, LocalDateTime>> {
        val startDate = start.date
        val endDate = end.date

        // Find Monday of the week containing startDate
        var weekMonday = startDate
        while (weekMonday.dayOfWeek != DayOfWeek.MONDAY) {
            weekMonday = weekMonday.plus(-1, DateTimeUnit.DAY)
        }

        val weeks = mutableListOf<Pair<LocalDateTime, LocalDateTime>>()
        var cursor = weekMonday
        while (cursor < endDate) {
            val weekEnd = cursor.plus(7, DateTimeUnit.DAY) // exclusive end = next Monday
            val weekStartLdt = cursor.atStartOfDay()
            val weekEndLdt = weekEnd.atStartOfDay()
            weeks += weekStartLdt to weekEndLdt
            cursor = weekEnd
        }
        return weeks
    }

    private fun LocalDate.atStartOfDay(): LocalDateTime = LocalDateTime(this, LocalTime(0, 0))

    private fun missingTypes(
        kassenbuchOk: Boolean,
        apOk: Boolean,
    ): List<String> =
        buildList {
            if (!kassenbuchOk) add(MonthlyCloseDetailCodes.EXPORT_KASSENBUCH)
            if (!apOk) add(MonthlyCloseDetailCodes.EXPORT_AP_LEDGER)
        }

    private fun requiredTypes(apLedgerRequired: Boolean): List<String> =
        if (apLedgerRequired) {
            MonthlyCloseDetailCodes.EXPORT_TYPES
        } else {
            listOf(MonthlyCloseDetailCodes.EXPORT_KASSENBUCH)
        }

    private fun buildMonthlyDetail(missing: List<String>): String =
        if (missing.isEmpty()) "" else "Missing full-period export for: ${missing.joinToString(", ")}"

    private fun detailCodeFor(
        cadence: DatevExportCadence,
        missingTypes: List<String>,
    ): String? =
        when {
            missingTypes.isEmpty() -> null
            cadence == DatevExportCadence.WEEKLY -> MonthlyCloseDetailCodes.DATEV_MISSING_WEEKS
            else -> MonthlyCloseDetailCodes.DATEV_MISSING_PERIOD
        }

    /** WEEKLY leads with the number of uncovered ISO weeks, then the types; MONTHLY is the types alone. */
    private fun detailArgsFor(
        cadence: DatevExportCadence,
        missingTypes: List<String>,
        missingWeekCount: Int,
    ): List<String> =
        when {
            missingTypes.isEmpty() -> emptyList()
            cadence == DatevExportCadence.WEEKLY -> listOf(missingWeekCount.toString()) + missingTypes
            else -> missingTypes
        }
}
