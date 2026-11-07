package org.example.project.features.reports.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.EmployeeHubKeys
import org.example.project.core.presentation.strings.keys.ReportsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.features.reports.presentation.ReportsGranularity
import org.example.project.features.reports.presentation.decadeFor
import org.example.project.features.reports.presentation.isFutureDay
import org.example.project.features.reports.presentation.isFutureMonth
import org.example.project.features.reports.presentation.isFutureWeek
import org.example.project.features.reports.presentation.isFutureYear
import org.example.project.features.reports.presentation.monthGrid
import org.example.project.features.reports.presentation.weekStart
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosIconButton

private val MONTH_FULL_NAMES: Map<Month, StringKey> =
    mapOf(
        Month.JANUARY to EmployeeHubKeys.january,
        Month.FEBRUARY to EmployeeHubKeys.february,
        Month.MARCH to EmployeeHubKeys.march,
        Month.APRIL to EmployeeHubKeys.april,
        Month.MAY to EmployeeHubKeys.may,
        Month.JUNE to EmployeeHubKeys.june,
        Month.JULY to EmployeeHubKeys.july,
        Month.AUGUST to EmployeeHubKeys.august,
        Month.SEPTEMBER to EmployeeHubKeys.september,
        Month.OCTOBER to EmployeeHubKeys.october,
        Month.NOVEMBER to EmployeeHubKeys.november,
        Month.DECEMBER to EmployeeHubKeys.december,
    )

private val MONTH_SHORT_NAMES: Map<Month, StringKey> =
    mapOf(
        Month.JANUARY to EmployeeHubKeys.janShort,
        Month.FEBRUARY to EmployeeHubKeys.febShort,
        Month.MARCH to EmployeeHubKeys.marShort,
        Month.APRIL to EmployeeHubKeys.aprShort,
        Month.MAY to EmployeeHubKeys.mayShort,
        Month.JUNE to EmployeeHubKeys.junShort,
        Month.JULY to EmployeeHubKeys.julShort,
        Month.AUGUST to EmployeeHubKeys.augShort,
        Month.SEPTEMBER to EmployeeHubKeys.sepShort,
        Month.OCTOBER to EmployeeHubKeys.octShort,
        Month.NOVEMBER to EmployeeHubKeys.novShort,
        Month.DECEMBER to EmployeeHubKeys.decShort,
    )

/**
 * Adaptive period picker for the Berichte stepper. The content adapts to [granularity]: DAY/WEEK show
 * a Mon-first month calendar (WEEK snaps a picked day to its ISO Monday), MONTH shows a 3×4 grid of
 * the twelve months, YEAR shows a decade pager. Any period after the current one is disabled — the
 * reports can never show the future. Opens on the page containing [anchor] so the owner never has to
 * page forward to find where they are. Stateless: the caller owns [anchor], [today], and [onPick].
 */
@Composable
fun PeriodPickerDialog(
    granularity: ReportsGranularity,
    anchor: LocalDate,
    today: LocalDate,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    PosDialog(
        onDismiss = onDismiss,
        title = str(ReportsKeys.jumpToPeriod),
    ) {
        when (granularity) {
            ReportsGranularity.DAY -> DayWeekGrid(anchor, today, weekMode = false, onPick)
            ReportsGranularity.WEEK -> DayWeekGrid(anchor, today, weekMode = true, onPick)
            ReportsGranularity.MONTH -> MonthGrid(anchor, today, onPick)
            ReportsGranularity.YEAR -> YearGrid(anchor, today, onPick)
            ReportsGranularity.CUSTOM ->
                error("PeriodPickerDialog has no CUSTOM grid; callers must use CustomDateRangePickerDialog")
        }
    }
}

@Composable
private fun DayWeekGrid(
    anchor: LocalDate,
    today: LocalDate,
    weekMode: Boolean,
    onPick: (LocalDate) -> Unit,
) {
    var viewYear by remember { mutableIntStateOf(anchor.year) }
    var viewMonth by remember { mutableStateOf(anchor.month) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PagerHeader(
            label = "${str(MONTH_FULL_NAMES.getValue(viewMonth))} $viewYear",
            // Forward is disabled while the current month is shown — the next month would be future.
            canStepForward = !(viewYear == today.year && viewMonth == today.month),
            onBack = {
                viewMonth = Month.entries[(viewMonth.ordinal - 1).mod(12)]
                if (viewMonth.ordinal == 11) viewYear -= 1
            },
            onForward = {
                viewMonth = Month.entries[(viewMonth.ordinal + 1).mod(12)]
                if (viewMonth.ordinal == 0) viewYear += 1
            },
        )

        WeekdayHeaderRow()

        monthGrid(viewYear, viewMonth).chunked(7).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    val future =
                        if (weekMode) {
                            date != null && isFutureWeek(date, today)
                        } else {
                            date != null && isFutureDay(date, today)
                        }
                    val selected =
                        if (weekMode) {
                            date != null && weekStart(date) == weekStart(anchor)
                        } else {
                            date == anchor
                        }
                    PeriodCell(
                        label = date?.day?.toString() ?: "",
                        selected = selected,
                        isCurrent = date == today,
                        enabled = date != null && !future,
                        onClick = {
                            if (date != null) onPick(if (weekMode) weekStart(date) else date)
                        },
                        modifier = Modifier.weight(1f).aspectRatio(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun WeekdayHeaderRow() {
    Row(modifier = Modifier.fillMaxWidth()) {
        listOf(
            CommonKeys.weekdayShortMon,
            CommonKeys.weekdayShortTue,
            CommonKeys.weekdayShortWed,
            CommonKeys.weekdayShortThu,
            CommonKeys.weekdayShortFri,
            CommonKeys.weekdayShortSat,
            CommonKeys.weekdayShortSun,
        ).forEach { key ->
            Text(
                text = str(key),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MonthGrid(
    anchor: LocalDate,
    today: LocalDate,
    onPick: (LocalDate) -> Unit,
) {
    var viewYear by remember { mutableIntStateOf(anchor.year) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PagerHeader(
            label = viewYear.toString(),
            // Forward is disabled at the current year; months of future years are blocked in-grid.
            canStepForward = viewYear < today.year,
            onBack = { viewYear -= 1 },
            onForward = { viewYear += 1 },
        )

        Month.entries.chunked(4).forEach { rowMonths ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowMonths.forEach { month ->
                    PeriodCell(
                        label = str(MONTH_SHORT_NAMES.getValue(month)),
                        selected = anchor.year == viewYear && anchor.month == month,
                        isCurrent = today.year == viewYear && today.month == month,
                        enabled = !isFutureMonth(viewYear, month, today),
                        onClick = { onPick(LocalDate(viewYear, month, 1)) },
                        modifier = Modifier.weight(1f).height(40.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun YearGrid(
    anchor: LocalDate,
    today: LocalDate,
    onPick: (LocalDate) -> Unit,
) {
    var decadeStart by remember { mutableIntStateOf(decadeFor(anchor.year).first) }
    val decadeYears = (decadeStart..decadeStart + 9).toList()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PagerHeader(
            label = "$decadeStart–${decadeStart + 9}",
            // Forward is disabled while the decade containing the current year is shown.
            canStepForward = decadeStart + 9 < today.year,
            onBack = { decadeStart -= 10 },
            onForward = { decadeStart += 10 },
        )

        decadeYears.chunked(5).forEach { rowYears ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowYears.forEach { year ->
                    PeriodCell(
                        label = year.toString(),
                        selected = anchor.year == year,
                        isCurrent = today.year == year,
                        enabled = !isFutureYear(year, today),
                        onClick = { onPick(LocalDate(year, Month.JANUARY, 1)) },
                        modifier = Modifier.weight(1f).height(44.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PagerHeader(
    label: String,
    canStepForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PosIconButton(
            icon = Icons.Default.ChevronLeft,
            contentDescription = str(ReportsKeys.stepBack),
            onClick = onBack,
            size = 32.dp,
            iconSize = 18.dp,
        )
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
        PosIconButton(
            icon = Icons.Default.ChevronRight,
            contentDescription = str(ReportsKeys.stepForward),
            onClick = onForward,
            enabled = canStepForward,
            size = 32.dp,
            iconSize = 18.dp,
        )
    }
}

/**
 * One selectable cell. Visual states: [selected] = filled primary chip; [isCurrent] (and not
 * selected) = outlined "today" marker; disabled ([enabled] == false) = dimmed, not clickable.
 */
@Composable
private fun PeriodCell(
    label: String,
    selected: Boolean,
    isCurrent: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = MaterialTheme.shapes.small
    val background = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val contentColor =
        when {
            selected -> MaterialTheme.colorScheme.onPrimary
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.onSurface
        }
    Box(
        modifier =
            modifier
                .clip(shape)
                .then(if (isCurrent && !selected) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
                .background(background)
                .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected || isCurrent) FontWeight.Bold else FontWeight.Normal,
            color = contentColor,
            maxLines = 1,
        )
    }
}
