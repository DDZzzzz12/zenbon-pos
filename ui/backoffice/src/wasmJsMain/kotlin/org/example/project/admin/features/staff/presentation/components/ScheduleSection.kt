package org.example.project.admin.features.staff.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.staff.presentation.schedule.ScheduleState
import org.example.project.admin.features.staff.presentation.schedule.ScheduleViewModel
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.formatHours
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.components.employee.ShiftChip
import org.example.project.ui.theme.PosSpacing
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.roundToInt

/** Read-only weekly schedule + manager edit-day entry point. Self-contained: owns its VM keyed to [employeeId]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScheduleSection(
    employeeId: String,
    employeeName: String,
    canManage: Boolean,
    modifier: Modifier = Modifier,
    viewModel: ScheduleViewModel = koinViewModel(),
) {
    LaunchedEffect(employeeId) { viewModel.load(employeeId) }

    Column(modifier.fillMaxWidth().padding(PosSpacing.xl), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(str(AdminStringKeys.StaffSectionSchedule), style = MaterialTheme.typography.titleMedium)

        when (val s = viewModel.state) {
            is ScheduleState.Loading -> CircularProgressIndicator()
            is ScheduleState.Error ->
                Text(
                    str(s.error.messageKey, *s.error.messageArgs.toTypedArray()),
                    color = MaterialTheme.colorScheme.error,
                )
            is ScheduleState.Data -> {
                val language = LocalAppLanguage.current
                Text(str(AdminStringKeys.ScheduleWeeklyHours, formatHours(s.weekly.scheduledWeeklyMinutes, language)))
                s.weekly.contractedWeeklyHours?.let { hours ->
                    // A duration, not money: whole minutes are enough.
                    val minutes = (hours * 60).roundToInt()
                    Text(
                        str(AdminStringKeys.ScheduleContractedHours, formatHours(minutes, language)),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                for (n in 1..7) {
                    val day = s.weekly.days.firstOrNull { it.dayOfWeek == n }
                    val shifts = day?.shifts?.takeIf { it.isNotEmpty() } ?: emptyList()
                    Column(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(str(weekdayKey(n)), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            if (canManage) {
                                PosTextButton(str(AdminStringKeys.ScheduleEditDay), onClick = { viewModel.openEditDay(n) })
                            }
                        }
                        if (shifts.isEmpty()) {
                            Text(
                                str(AdminStringKeys.ScheduleDayOff),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                shifts.forEach { shift ->
                                    ShiftChip(
                                        startTime = shift.startTime,
                                        endTime = shift.endTime,
                                        breakMinutes = shift.breakDuration,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    viewModel.dayEditor?.let { ed ->
        ScheduleDayDialog(
            editor = ed,
            employeeName = employeeName,
            dayLabel = str(weekdayFullKey(ed.dayOfWeek)),
            onAddShift = viewModel::addShift,
            onRemoveShift = viewModel::removeShift,
            onShiftField = viewModel::onShift,
            onSave = viewModel::saveDay,
            onDismiss = viewModel::closeDay,
            dirty = viewModel.dayEditorDirty,
        )
    }
}

private fun weekdayFullKey(n: Int) =
    when (n) {
        1 -> AdminStringKeys.WeekdayMonday
        2 -> AdminStringKeys.WeekdayTuesday
        3 -> AdminStringKeys.WeekdayWednesday
        4 -> AdminStringKeys.WeekdayThursday
        5 -> AdminStringKeys.WeekdayFriday
        6 -> AdminStringKeys.WeekdaySaturday
        else -> AdminStringKeys.WeekdaySunday
    }

private fun weekdayKey(n: Int) =
    when (n) {
        1 -> AdminStringKeys.ScheduleMon
        2 -> AdminStringKeys.ScheduleTue
        3 -> AdminStringKeys.ScheduleWed
        4 -> AdminStringKeys.ScheduleThu
        5 -> AdminStringKeys.ScheduleFri
        6 -> AdminStringKeys.ScheduleSat
        else -> AdminStringKeys.ScheduleSun
    }
